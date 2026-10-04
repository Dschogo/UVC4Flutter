/**
 * uvc_stream
 * Copyright (c) 2024-2026 saki t_saki@serenegiant.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "FlutterUVCFanout"

#if 1
	#ifndef LOG_NDEBUG
		#define LOG_NDEBUG
	#endif
	#undef USE_LOGALL
	#define USE_LOGW
	#define USE_LOGE
#else
	#define USE_LOGALL
	#undef LOG_NDEBUG
	#undef NDEBUG
#endif

#include <chrono>
#include <cstring>
#include <ctime>
#include <unistd.h>

#define EGL_EGLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>

#include "utilbase.h"
#include "flutter_uvc_fanout.h"

namespace serenegiant::flutter {

	namespace {

		constexpr int64_t IDLE_SLEEP_US = 5000;
		constexpr int64_t BLACK_FRAME_INTERVAL_US = 33333;
		constexpr uint32_t BUFFER_HEADROOM = 1024 * 1024;

		const GLfloat QUAD_POSITIONS[] = {
			-1.0f, -1.0f,
			1.0f, -1.0f,
			-1.0f, 1.0f,
			1.0f, 1.0f,
		};

		const GLfloat QUAD_TEXCOORDS[] = {
			0.0f, 1.0f,
			1.0f, 1.0f,
			0.0f, 0.0f,
			1.0f, 0.0f,
		};

		const char *VERTEX_SHADER =
			"attribute vec4 a_position;\n"
			"attribute vec2 a_texcoord;\n"
			"uniform mat4 u_mvp;\n"
			"varying vec2 v_texcoord;\n"
			"void main() {\n"
			"  gl_Position = u_mvp * a_position;\n"
			"  v_texcoord = a_texcoord;\n"
			"}\n";

		const char *FRAGMENT_SHADER =
			"precision mediump float;\n"
			"varying vec2 v_texcoord;\n"
			"uniform sampler2D u_sampler;\n"
			"void main() {\n"
			"  gl_FragColor = texture2D(u_sampler, v_texcoord);\n"
			"}\n";

		int64_t monotonic_time_us() {
			timespec ts;
			clock_gettime(CLOCK_MONOTONIC, &ts);
			return static_cast<int64_t>(ts.tv_sec) * 1000000LL
				+ static_cast<int64_t>(ts.tv_nsec) / 1000LL;
		}

		GLuint compile_shader(GLenum type, const char *source) {
			const GLuint shader = glCreateShader(type);
			if (!shader) {
				return 0;
			}
			glShaderSource(shader, 1, &source, nullptr);
			glCompileShader(shader);
			GLint compiled = 0;
			glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
			if (!compiled) {
				GLint log_length = 0;
				glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &log_length);
				if (log_length) {
					std::vector<char> log(log_length);
					glGetShaderInfoLog(shader, log_length, nullptr, log.data());
					LOGE("shader compile failed:%s", log.data());
				}
				glDeleteShader(shader);
				return 0;
			}
			return shader;
		}

		GLuint link_program() {
			const GLuint vertex = compile_shader(GL_VERTEX_SHADER, VERTEX_SHADER);
			if (!vertex) {
				return 0;
			}
			const GLuint fragment = compile_shader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
			if (!fragment) {
				glDeleteShader(vertex);
				return 0;
			}
			const GLuint program = glCreateProgram();
			glAttachShader(program, vertex);
			glAttachShader(program, fragment);
			glLinkProgram(program);
			glDeleteShader(vertex);
			glDeleteShader(fragment);
			GLint linked = 0;
			glGetProgramiv(program, GL_LINK_STATUS, &linked);
			if (!linked) {
				GLint log_length = 0;
				glGetProgramiv(program, GL_INFO_LOG_LENGTH, &log_length);
				if (log_length) {
					std::vector<char> log(log_length);
					glGetProgramInfoLog(program, log_length, nullptr, log.data());
					LOGE("program link failed:%s", log.data());
				}
				glDeleteProgram(program);
				return 0;
			}
			return program;
		}

		uint32_t bytes_per_pixel(const uint32_t &frame_type) {
			switch (frame_type) {
			case RAW_FRAME_UNCOMPRESSED_RGB565:
				return 2;
			case RAW_FRAME_UNCOMPRESSED_YUYV:
				return 2;
			default:
				return 4;
			}
		}

	} // namespace

	FlutterUVCFanout::FlutterUVCFanout(
		usb_manager_t *manager, const int32_t &device_id,
		const uint32_t &frame_type, const uint32_t &width, const uint32_t &height)
		: m_manager(manager),
		  m_device_id(device_id),
		  m_device_alive((manager != nullptr) && (device_id > 0)),
		  m_frame_type(frame_type),
		  m_width(width),
		  m_height(height),
		  m_running(false),
		  m_encode_active(false) {
		ENTER();
		EXIT();
	}

	FlutterUVCFanout::~FlutterUVCFanout() noexcept {
		ENTER();
		stop();
		if (m_preview_window) {
			ANativeWindow_release(m_preview_window);
			m_preview_window = nullptr;
		}
		if (m_encode_window) {
			ANativeWindow_release(m_encode_window);
			m_encode_window = nullptr;
		}
		EXIT();
	}

	int FlutterUVCFanout::set_device(usb_manager_t *manager, const int32_t &device_id) {
		ENTER();

		const bool alive = (manager != nullptr) && (device_id > 0);
		// Pause frame reading while the device is reconfigured.
		const bool was_alive = m_device_alive.exchange(false);
		m_manager.store(manager);
		m_device_id.store(device_id);

		if (m_running.load() && alive) {
			uvc_resize(manager, device_id, m_frame_type.load(), m_width.load(), m_height.load());
			uvc_start(manager, device_id);
			LOGW("fanout attached to device %d", device_id);
		} else if (!alive && was_alive) {
			LOGW("fanout detached (no device)");
		}

		m_device_alive.store(alive);
		RETURN(0, int);
	}

	int FlutterUVCFanout::set_output_size(
		const uint32_t &frame_type, const uint32_t &width, const uint32_t &height) {
		ENTER();
		m_frame_type.store(frame_type);
		m_width.store(width);
		m_height.store(height);
		RETURN(0, int);
	}

	int FlutterUVCFanout::start() {
		ENTER();
		if (m_running.exchange(true)) {
			RETURN(0, int);
		}
		if (m_device_alive.load()) {
			const int result = uvc_start(m_manager.load(), m_device_id.load());
			if (result) {
				LOGE("uvc_start failed,err=%d", result);
			}
		}
		m_thread = std::thread(&FlutterUVCFanout::render_loop, this);
		RETURN(0, int);
	}

	int FlutterUVCFanout::stop() {
		ENTER();
		if (!m_running.exchange(false)) {
			RETURN(0, int);
		}
		if (m_thread.joinable()) {
			m_thread.join();
		}
		if (m_device_alive.load()) {
			uvc_stop(m_manager.load(), m_device_id.load());
		}
		RETURN(0, int);
	}

	int FlutterUVCFanout::set_preview_window(ANativeWindow *window) {
		ENTER();
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			if (window) {
				ANativeWindow_acquire(window);
			}
			if (m_preview_window) {
				ANativeWindow_release(m_preview_window);
			}
			m_preview_window = window;
			if (m_preview_surface != EGL_NO_SURFACE) {
				release_surface_locked(m_preview_surface);
			}
			m_preview_width = 0;
			m_preview_height = 0;
		}
		RETURN(0, int);
	}

	int FlutterUVCFanout::set_encode_window(ANativeWindow *window) {
		ENTER();
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			if (window) {
				ANativeWindow_acquire(window);
			}
			if (m_encode_window) {
				ANativeWindow_release(m_encode_window);
			}
			m_encode_window = window;
			if (m_encode_surface != EGL_NO_SURFACE) {
				release_surface_locked(m_encode_surface);
			}
			m_encode_width = 0;
			m_encode_height = 0;
		}
		RETURN(0, int);
	}

	int FlutterUVCFanout::set_encode_active(const bool &active) {
		ENTER();
		LOGW("set_encode_active:%d", active);
		m_encode_active.store(active);
		RETURN(0, int);
	}

	int FlutterUVCFanout::set_mvp_matrix(const float *mvp_matrix) {
		ENTER();
		std::lock_guard<std::mutex> lock(m_mutex);
		if (mvp_matrix) {
			memcpy(m_mvp, mvp_matrix, sizeof(m_mvp));
		} else {
			memset(m_mvp, 0, sizeof(m_mvp));
			m_mvp[0] = m_mvp[5] = m_mvp[10] = m_mvp[15] = 1.0f;
		}
		RETURN(0, int);
	}

	bool FlutterUVCFanout::init_egl() {
		ENTER();

		m_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
		if (m_egl_display == EGL_NO_DISPLAY) {
			LOGE("eglGetDisplay failed");
			RETURN(false, bool);
		}
		EGLint major = 0;
		EGLint minor = 0;
		if (!eglInitialize(m_egl_display, &major, &minor)) {
			LOGE("eglInitialize failed");
			m_egl_display = EGL_NO_DISPLAY;
			RETURN(false, bool);
		}

		const EGLint config_attribs[] = {
			EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
			EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
			EGL_RED_SIZE, 8,
			EGL_GREEN_SIZE, 8,
			EGL_BLUE_SIZE, 8,
			EGL_ALPHA_SIZE, 8,
			EGL_NONE,
		};
		EGLint num_configs = 0;
		if (!eglChooseConfig(m_egl_display, config_attribs, &m_egl_config, 1, &num_configs)
			|| (num_configs < 1)) {
			LOGE("eglChooseConfig failed");
			term_egl();
			RETURN(false, bool);
		}

		eglBindAPI(EGL_OPENGL_ES_API);
		const EGLint context_attribs[] = {
			EGL_CONTEXT_CLIENT_VERSION, 2,
			EGL_NONE,
		};
		m_egl_context = eglCreateContext(
			m_egl_display, m_egl_config, EGL_NO_CONTEXT, context_attribs);
		if (m_egl_context == EGL_NO_CONTEXT) {
			LOGE("eglCreateContext failed");
			term_egl();
			RETURN(false, bool);
		}

		const EGLint pbuffer_attribs[] = {
			EGL_WIDTH, 1,
			EGL_HEIGHT, 1,
			EGL_NONE,
		};
		m_egl_pbuffer = eglCreatePbufferSurface(m_egl_display, m_egl_config, pbuffer_attribs);
		if (m_egl_pbuffer == EGL_NO_SURFACE) {
			LOGE("eglCreatePbufferSurface failed");
			term_egl();
			RETURN(false, bool);
		}
		if (!eglMakeCurrent(m_egl_display, m_egl_pbuffer, m_egl_pbuffer, m_egl_context)) {
			LOGE("eglMakeCurrent failed");
			term_egl();
			RETURN(false, bool);
		}

		m_program = link_program();
		if (!m_program) {
			term_egl();
			RETURN(false, bool);
		}
		m_position_handle = glGetAttribLocation(m_program, "a_position");
		m_texcoord_handle = glGetAttribLocation(m_program, "a_texcoord");
		m_mvp_handle = glGetUniformLocation(m_program, "u_mvp");
		m_sampler_handle = glGetUniformLocation(m_program, "u_sampler");

		glGenTextures(1, &m_texture);
		glBindTexture(GL_TEXTURE_2D, m_texture);
		glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		RETURN(true, bool);
	}

	void FlutterUVCFanout::term_egl() {
		ENTER();
		if (m_egl_display == EGL_NO_DISPLAY) {
			EXIT();
		}
		// Delete the GL objects while the context is still current, otherwise
		// the driver logs "call to OpenGL ES API with no current context".
		if ((m_egl_context != EGL_NO_CONTEXT) && (m_egl_pbuffer != EGL_NO_SURFACE)) {
			eglMakeCurrent(
				m_egl_display, m_egl_pbuffer, m_egl_pbuffer, m_egl_context);
			if (m_texture) {
				glDeleteTextures(1, &m_texture);
				m_texture = 0;
			}
			if (m_program) {
				glDeleteProgram(m_program);
				m_program = 0;
			}
			eglMakeCurrent(
				m_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
		}
		if (m_preview_surface != EGL_NO_SURFACE) {
			release_surface_locked(m_preview_surface);
		}
		if (m_encode_surface != EGL_NO_SURFACE) {
			release_surface_locked(m_encode_surface);
		}
		if (m_egl_pbuffer != EGL_NO_SURFACE) {
			eglDestroySurface(m_egl_display, m_egl_pbuffer);
			m_egl_pbuffer = EGL_NO_SURFACE;
		}
		if (m_egl_context != EGL_NO_CONTEXT) {
			eglDestroyContext(m_egl_display, m_egl_context);
			m_egl_context = EGL_NO_CONTEXT;
		}
		eglTerminate(m_egl_display);
		m_egl_display = EGL_NO_DISPLAY;
		EXIT();
	}

	bool FlutterUVCFanout::ensure_surface_locked(
		ANativeWindow *window, EGLSurface &egl_surface, int &width, int &height) {
		if (!window) {
			return false;
		}
		const int w = ANativeWindow_getWidth(window);
		const int h = ANativeWindow_getHeight(window);
		if ((egl_surface == EGL_NO_SURFACE) || (w != width) || (h != height)) {
			release_surface_locked(egl_surface);
			egl_surface = eglCreateWindowSurface(
				m_egl_display, m_egl_config, window, nullptr);
			if (egl_surface == EGL_NO_SURFACE) {
				LOGE("eglCreateWindowSurface failed,err=0x%x", eglGetError());
				width = 0;
				height = 0;
				return false;
			}
			width = w;
			height = h;
		}
		return true;
	}

	void FlutterUVCFanout::release_surface_locked(EGLSurface &egl_surface) {
		if (egl_surface != EGL_NO_SURFACE) {
			eglDestroySurface(m_egl_display, egl_surface);
			egl_surface = EGL_NO_SURFACE;
		}
	}

	bool FlutterUVCFanout::upload_frame_locked(
		const uint8_t *data, const uint32_t &width, const uint32_t &height,
		const uint32_t &frame_type, const uint32_t &stride) {

		if ((!data) || (!width) || (!height)) {
			return false;
		}

		static uint32_t logged_type = 0xffffffffu;
		if (frame_type != logged_type) {
			LOGW("first frame: type=0x%08x,%ux%u,stride=%u", frame_type, width, height, stride);
			logged_type = frame_type;
		}

		GLenum format;
		GLenum type;
		switch (frame_type) {
		case RAW_FRAME_UNCOMPRESSED_RGB565:
			format = GL_RGB;
			type = GL_UNSIGNED_SHORT_5_6_5;
			break;
		case RAW_FRAME_UNCOMPRESSED_RGBX:
			format = GL_RGBA;
			type = GL_UNSIGNED_BYTE;
			break;
		default:
			LOGW("unsupported frame type 0x%08x, requesting RGBX", frame_type);
			return false;
		}
		const uint32_t row_bytes = width * bytes_per_pixel(frame_type);

		if (stride != row_bytes) {
			m_scratch.resize(row_bytes * height);
			for (uint32_t y = 0; y < height; y++) {
				memcpy(m_scratch.data() + (y * row_bytes), data + (y * stride), row_bytes);
			}
			data = m_scratch.data();
		}

		glBindTexture(GL_TEXTURE_2D, m_texture);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(
			GL_TEXTURE_2D, 0, format, width, height, 0,
			format, type, data);
		glBindTexture(GL_TEXTURE_2D, 0);

		return true;
	}

	void FlutterUVCFanout::draw_locked(
		EGLSurface egl_surface, const int &surface_width, const int &surface_height,
		const int64_t &pts_us, const bool &draw_texture) {

		if ((egl_surface == EGL_NO_SURFACE) || (!surface_width) || (!surface_height)) {
			return;
		}
		if (!eglMakeCurrent(m_egl_display, egl_surface, egl_surface, m_egl_context)) {
			LOGW("eglMakeCurrent failed,err=0x%x", eglGetError());
			return;
		}

		glViewport(0, 0, surface_width, surface_height);
		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
		glClear(GL_COLOR_BUFFER_BIT);

		if (draw_texture) {
			glUseProgram(m_program);
			glActiveTexture(GL_TEXTURE0);
			glBindTexture(GL_TEXTURE_2D, m_texture);
			glUniform1i(m_sampler_handle, 0);
			glUniformMatrix4fv(m_mvp_handle, 1, GL_FALSE, m_mvp);

			glEnableVertexAttribArray(m_position_handle);
			glVertexAttribPointer(
				m_position_handle, 2, GL_FLOAT, GL_FALSE, 0, QUAD_POSITIONS);
			glEnableVertexAttribArray(m_texcoord_handle);
			glVertexAttribPointer(
				m_texcoord_handle, 2, GL_FLOAT, GL_FALSE, 0, QUAD_TEXCOORDS);

			glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

			glDisableVertexAttribArray(m_position_handle);
			glDisableVertexAttribArray(m_texcoord_handle);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		eglPresentationTimeANDROID(m_egl_display, egl_surface, pts_us * 1000LL);
		if (!eglSwapBuffers(m_egl_display, egl_surface)) {
			LOGW("eglSwapBuffers failed,err=0x%x", eglGetError());
		}
	}

	void FlutterUVCFanout::draw_black_locked(const int64_t &pts_us) {
		if (ensure_surface_locked(
			m_preview_window, m_preview_surface, m_preview_width, m_preview_height)) {
			draw_locked(
				m_preview_surface, m_preview_width, m_preview_height, pts_us, false);
		}
		if (m_encode_active.load() && ensure_surface_locked(
			m_encode_window, m_encode_surface, m_encode_width, m_encode_height)) {
			draw_locked(
				m_encode_surface, m_encode_width, m_encode_height, pts_us, false);
		}
	}

	void FlutterUVCFanout::render_loop() {
		ENTER();

		if (!init_egl()) {
			m_running.store(false);
			EXIT();
		}

		std::vector<uint8_t> buffer;
		int64_t last_black_us = 0;

		while (m_running.load()) {
			if (!m_device_alive.load()) {
				// No device: keep the pipeline alive with black frames.
				const int64_t now = monotonic_time_us();
				if ((now - last_black_us) >= BLACK_FRAME_INTERVAL_US) {
					last_black_us = now;
					std::lock_guard<std::mutex> lock(m_mutex);
					draw_black_locked(now);
				} else {
					usleep(IDLE_SLEEP_US);
				}
				continue;
			}

			usb_manager_t *manager = m_manager.load();
			const int32_t device_id = m_device_id.load();
			const uint32_t capacity =
				(m_width.load() ? m_width.load() : 1920)
				* (m_height.load() ? m_height.load() : 1080) * 4 + BUFFER_HEADROOM;
			if (buffer.size() < capacity) {
				buffer.resize(capacity);
			}

			uint32_t frame_type = RAW_FRAME_UNCOMPRESSED_RGBX;
			uint32_t width = 0;
			uint32_t height = 0;
			uint32_t flags = 0;
			int64_t pts_us = 0;
			uint32_t data_len = static_cast<uint32_t>(buffer.size());

			const int result = uvc_get_frame(
				manager, device_id,
				&frame_type, &width, &height,
				buffer.data(), &data_len, &pts_us, &flags);

			{
				std::lock_guard<std::mutex> lock(m_mutex);
				if (result || (!data_len) || (!width) || (!height)) {
					const int64_t now = monotonic_time_us();
					if ((now - last_black_us) >= BLACK_FRAME_INTERVAL_US) {
						last_black_us = now;
						draw_black_locked(now);
					}
					usleep(IDLE_SLEEP_US);
					continue;
				}

				uint32_t stride = data_len / height;
				if (stride < (width * bytes_per_pixel(frame_type))) {
					stride = width * bytes_per_pixel(frame_type);
				}
				if (!upload_frame_locked(
					buffer.data(), width, height, frame_type, stride)) {
					continue;
				}
				if (ensure_surface_locked(
					m_preview_window, m_preview_surface, m_preview_width, m_preview_height)) {
					draw_locked(
						m_preview_surface, m_preview_width, m_preview_height, pts_us);
				}
				if (m_encode_active.load() && ensure_surface_locked(
					m_encode_window, m_encode_surface, m_encode_width, m_encode_height)) {
					draw_locked(
						m_encode_surface, m_encode_width, m_encode_height, pts_us);
				}
			}
		}

		term_egl();
		EXIT();
	}

} // namespace serenegiant::flutter
