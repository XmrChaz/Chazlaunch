/* CI-only stub of <android/log.h> — just enough for a host -fsyntax-only pass.
 * The real header comes from the NDK at device-build time. */
#ifndef CI_STUB_ANDROID_LOG_H
#define CI_STUB_ANDROID_LOG_H

enum {
    ANDROID_LOG_VERBOSE = 2,
    ANDROID_LOG_DEBUG = 3,
    ANDROID_LOG_INFO = 4,
    ANDROID_LOG_WARN = 5,
    ANDROID_LOG_ERROR = 6,
};

int __android_log_print(int prio, const char *tag, const char *fmt, ...);

#endif
