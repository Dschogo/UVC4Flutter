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

#ifndef AANDUSB_FLUTTER_UVC_FANOUT_H
#define AANDUSB_FLUTTER_UVC_FANOUT_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <android/native_window.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>

#include "aandusb_native.h"

namespace serenegiant::flutter {

/**
 * Session level GL fan-out. Owns one EGL context and one GL texture and can be
 * re-pointed to whichever UVC device is currently attached, so the stream keeps
 * running (black frames) across device disconnects.
 */
class FlutterUVCFanout {
public:
	FlutterUVCFanout(
		usb_manager_t *manager, const int32_t &device_id,
		const uint32_t &frame_type, const uint32_t &width, const uint32_t &height);

	~FlutterUVCFanout() noexcept;

	FlutterUVCFanout(const FlutterUVCFanout &) = delete;
	FlutterUVCFanout &operator=(const FlutterUVCFanout &) = delete;

	[[nodiscard]] int32_t device_id() const { return m_device_id.load(); }

	[[nodiscard]] bool is_running() const { return m_running.load(); }

	int set_device(usb_manager_t *manager, const int32_t &device_id);

	int set_output_size(const uint32_t &frame_type, const uint32_t &width, const uint32_t &height);

	int start();

	int stop();

	int set_preview_window(ANativeWindow *window);

	int set_encode_window(ANativeWindow *window);

	int set_encode_active(const bool &active);

	int set_mvp_matrix(const float *mvp_matrix);

private:
	void render_loop();

	void draw_black_locked(const int64_t &pts_us);

	bool init_egl();

	void term_egl();

	bool ensure_surface_locked(
		ANativeWindow *window, EGLSurface &egl_surface, int &width, int &height);

	void release_surface_locked(EGLSurface &egl_surface);

	bool upload_frame_locked(
		const uint8_t *data, const uint32_t &width, const uint32_t &height,
		const uint32_t &frame_type, const uint32_t &stride);

	void draw_locked(
		EGLSurface egl_surface, const int &surface_width, const int &surface_height,
		const int64_t &pts_us, const bool &draw_texture = true);

	std::atomic<usb_manager_t *> m_manager;
	std::atomic<int32_t> m_device_id;
	std::atomic<bool> m_device_alive;
	std::atomic<uint32_t> m_frame_type;
	std::atomic<uint32_t> m_width;
	std::atomic<uint32_t> m_height;

	std::atomic<bool> m_running;
	std::atomic<bool> m_encode_active;
	std::thread m_thread;

	std::mutex m_mutex;
	ANativeWindow *m_preview_window = nullptr;
	ANativeWindow *m_encode_window = nullptr;
	EGLSurface m_preview_surface = EGL_NO_SURFACE;
	EGLSurface m_encode_surface = EGL_NO_SURFACE;
	int m_preview_width = 0;
	int m_preview_height = 0;
	int m_encode_width = 0;
	int m_encode_height = 0;

	EGLDisplay m_egl_display = EGL_NO_DISPLAY;
	EGLContext m_egl_context = EGL_NO_CONTEXT;
	EGLConfig m_egl_config = nullptr;
	EGLSurface m_egl_pbuffer = EGL_NO_SURFACE;

	GLuint m_texture = 0;
	GLuint m_program = 0;
	GLint m_position_handle = -1;
	GLint m_texcoord_handle = -1;
	GLint m_mvp_handle = -1;
	GLint m_sampler_handle = -1;
	float m_mvp[16] = {1.f, 0.f, 0.f, 0.f,
					   0.f, 1.f, 0.f, 0.f,
					   0.f, 0.f, 1.f, 0.f,
					   0.f, 0.f, 0.f, 1.f};

	std::vector<uint8_t> m_scratch;
};

using FlutterUVCFanoutSp = std::shared_ptr<FlutterUVCFanout>;
using FlutterUVCFanoutUp = std::unique_ptr<FlutterUVCFanout>;

} // namespace serenegiant::flutter

#endif //AANDUSB_FLUTTER_UVC_FANOUT_H
