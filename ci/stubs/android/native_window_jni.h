/* CI-only stub of <android/native_window_jni.h> — host -fsyntax-only pass only.
 * The real header comes from the NDK at device-build time. */
#ifndef CI_STUB_ANDROID_NATIVE_WINDOW_JNI_H
#define CI_STUB_ANDROID_NATIVE_WINDOW_JNI_H

#include <jni.h>
#include <android/native_window.h>

ANativeWindow *ANativeWindow_fromSurface(JNIEnv *env, jobject surface);
jobject ANativeWindow_toSurface(JNIEnv *env, ANativeWindow *window);

#endif
