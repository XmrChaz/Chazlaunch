/* CI-only stub of <android/native_window.h> — host -fsyntax-only pass only.
 * The real header/behaviour comes from the NDK at device-build time. */
#ifndef CI_STUB_ANDROID_NATIVE_WINDOW_H
#define CI_STUB_ANDROID_NATIVE_WINDOW_H

#include <stdint.h>

typedef struct ANativeWindow ANativeWindow;

typedef struct ANativeWindow_Buffer {
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t format;
    void *bits;
    uint32_t reserved[6];
} ANativeWindow_Buffer;

typedef struct ARect {
    int32_t left, top, right, bottom;
} ARect;

enum {
    WINDOW_FORMAT_RGBA_8888 = 1,
    WINDOW_FORMAT_RGBX_8888 = 2,
    WINDOW_FORMAT_RGB_565 = 4,
};

int32_t ANativeWindow_setBuffersGeometry(ANativeWindow *window, int32_t width,
                                         int32_t height, int32_t format);
int32_t ANativeWindow_lock(ANativeWindow *window, ANativeWindow_Buffer *outBuffer,
                           ARect *inOutDirtyBounds);
int32_t ANativeWindow_unlockAndPost(ANativeWindow *window);
void ANativeWindow_acquire(ANativeWindow *window);
void ANativeWindow_release(ANativeWindow *window);

#endif
