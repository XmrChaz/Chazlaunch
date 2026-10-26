#include "compositor.h"
#include "xdg-shell-server-protocol.h"

#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, CHAZ_LOG_TAG, __VA_ARGS__)

extern struct chaz_server *g_server;

static JavaVM *g_vm = NULL;
static jclass g_compositor_cls = NULL;      /* global ref to Compositor */
static jmethodID m_created, m_title, m_resized, m_closed;

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static JNIEnv *attach_env(bool *attached) {
    JNIEnv *env = NULL;
    *attached = false;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
        *attached = true;
    }
    return env;
}

static void cache_callbacks(JNIEnv *env) {
    if (g_compositor_cls) return;
    jclass local = (*env)->FindClass(env, "com/chazlaunch/compositor/Compositor");
    g_compositor_cls = (*env)->NewGlobalRef(env, local);
    m_created = (*env)->GetStaticMethodID(env, g_compositor_cls,
            "nativeOnToplevelCreated", "(JLjava/lang/String;Ljava/lang/String;)V");
    m_title = (*env)->GetStaticMethodID(env, g_compositor_cls,
            "nativeOnTitleChanged", "(JLjava/lang/String;)V");
    m_resized = (*env)->GetStaticMethodID(env, g_compositor_cls,
            "nativeOnResized", "(JII)V");
    m_closed = (*env)->GetStaticMethodID(env, g_compositor_cls,
            "nativeOnToplevelClosed", "(J)V");
}

/* ---- Notifications from compositor.c (on the compositor thread) ---------- */

void chaz_notify_toplevel_created(struct chaz_toplevel *t) {
    bool attached;
    JNIEnv *env = attach_env(&attached);
    cache_callbacks(env);
    jstring title = (*env)->NewStringUTF(env, t->title ? t->title : "");
    jstring appid = (*env)->NewStringUTF(env, t->app_id ? t->app_id : "");
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, m_created,
                                 (jlong) t->id, title, appid);
    (*env)->DeleteLocalRef(env, title);
    (*env)->DeleteLocalRef(env, appid);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

void chaz_notify_title_changed(struct chaz_toplevel *t) {
    bool attached;
    JNIEnv *env = attach_env(&attached);
    cache_callbacks(env);
    jstring title = (*env)->NewStringUTF(env, t->title ? t->title : "");
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, m_title, (jlong) t->id, title);
    (*env)->DeleteLocalRef(env, title);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

void chaz_notify_toplevel_closed(uint64_t id) {
    bool attached;
    JNIEnv *env = attach_env(&attached);
    cache_callbacks(env);
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, m_closed, (jlong) id);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

/* ---- Lifecycle ----------------------------------------------------------- */

static void *run_thread(void *arg) {
    chaz_server_run((struct chaz_server *) arg);
    return NULL;
}

JNIEXPORT jstring JNICALL
Java_com_chazlaunch_compositor_Compositor_start(JNIEnv *env, jobject thiz,
                                                jstring socket_dir, jfloat scale) {
    const char *dir = (*env)->GetStringUTFChars(env, socket_dir, NULL);
    if (!g_server) {
        struct chaz_server *s = chaz_server_create(dir, scale);
        pthread_create(&s->thread, NULL, run_thread, s);
    }
    (*env)->ReleaseStringUTFChars(env, socket_dir, dir);
    return (*env)->NewStringUTF(env, g_server->socket_name);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_stop(JNIEnv *env, jobject thiz) {
    if (!g_server) return;
    struct chaz_server *s = g_server;
    chaz_server_stop(s);
    pthread_join(s->thread, NULL);
    g_server = NULL;
    free(s);
}

JNIEXPORT jboolean JNICALL
Java_com_chazlaunch_compositor_Compositor_isRunning(JNIEnv *env, jobject thiz) {
    return (g_server && g_server->running) ? JNI_TRUE : JNI_FALSE;
}

/* ---- Per-window surface plumbing ---------------------------------------- */

struct attach_arg { uint64_t id; ANativeWindow *win; int w, h; };
struct id_arg { uint64_t id; };
struct resize_arg { uint64_t id; int w, h; };

static void do_attach(struct chaz_server *s, void *a) {
    struct attach_arg *arg = a;
    struct chaz_toplevel *t = chaz_find_toplevel(s, arg->id);
    if (t) {
        if (t->native_window) ANativeWindow_release(t->native_window);
        t->native_window = arg->win;   /* ref already held from fromSurface */
        t->out_width = arg->w;
        t->out_height = arg->h;
        chaz_present(t);
    } else if (arg->win) {
        ANativeWindow_release(arg->win);
    }
    free(arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_attachSurface(JNIEnv *env, jobject thiz,
        jlong id, jobject surface, jint w, jint h) {
    if (!g_server) return;
    struct attach_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id;
    arg->win = ANativeWindow_fromSurface(env, surface);
    arg->w = w;
    arg->h = h;
    chaz_post(g_server, do_attach, arg);
}

static void do_detach(struct chaz_server *s, void *a) {
    struct id_arg *arg = a;
    struct chaz_toplevel *t = chaz_find_toplevel(s, arg->id);
    if (t && t->native_window) {
        ANativeWindow_release(t->native_window);
        t->native_window = NULL;
    }
    free(arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_detachSurface(JNIEnv *env, jobject thiz, jlong id) {
    if (!g_server) return;
    struct id_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id;
    chaz_post(g_server, do_detach, arg);
}

static void do_resize(struct chaz_server *s, void *a) {
    struct resize_arg *arg = a;
    struct chaz_toplevel *t = chaz_find_toplevel(s, arg->id);
    if (t && t->xdg_toplevel) {
        t->out_width = arg->w;
        t->out_height = arg->h;
        struct wl_array states;
        wl_array_init(&states);
        uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
        *st = XDG_TOPLEVEL_STATE_ACTIVATED;
        xdg_toplevel_send_configure(t->xdg_toplevel, arg->w, arg->h, &states);
        wl_array_release(&states);
        xdg_surface_send_configure(t->xdg_surface, ++s->serial);
    }
    free(arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_resizeToplevel(JNIEnv *env, jobject thiz,
        jlong id, jint w, jint h) {
    if (!g_server) return;
    struct resize_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id;
    arg->w = w;
    arg->h = h;
    chaz_post(g_server, do_resize, arg);
}

static void do_close(struct chaz_server *s, void *a) {
    struct id_arg *arg = a;
    struct chaz_toplevel *t = chaz_find_toplevel(s, arg->id);
    if (t && t->xdg_toplevel) xdg_toplevel_send_close(t->xdg_toplevel);
    free(arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_requestClose(JNIEnv *env, jobject thiz, jlong id) {
    if (!g_server) return;
    struct id_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id;
    chaz_post(g_server, do_close, arg);
}

/* ---- Input forwarding ---------------------------------------------------- */

struct ptr_motion_arg { uint64_t id; float x, y; };
struct ptr_button_arg { uint64_t id; int button; bool pressed; };
struct ptr_scroll_arg { uint64_t id; float dx, dy; };
struct key_arg { uint64_t id; int keycode; bool pressed; };

static void do_ptr_motion(struct chaz_server *s, void *a) {
    struct ptr_motion_arg *arg = a;
    chaz_pointer_motion(s, chaz_find_toplevel(s, arg->id), arg->x, arg->y);
    free(arg);
}
static void do_ptr_button(struct chaz_server *s, void *a) {
    struct ptr_button_arg *arg = a;
    chaz_pointer_button(s, chaz_find_toplevel(s, arg->id), arg->button, arg->pressed);
    free(arg);
}
static void do_ptr_scroll(struct chaz_server *s, void *a) {
    struct ptr_scroll_arg *arg = a;
    chaz_pointer_scroll(s, chaz_find_toplevel(s, arg->id), arg->dx, arg->dy);
    free(arg);
}
static void do_key(struct chaz_server *s, void *a) {
    struct key_arg *arg = a;
    chaz_key(s, chaz_find_toplevel(s, arg->id), arg->keycode, arg->pressed);
    free(arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_sendPointerMotion(JNIEnv *env, jobject thiz,
        jlong id, jfloat x, jfloat y) {
    if (!g_server) return;
    struct ptr_motion_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id; arg->x = x; arg->y = y;
    chaz_post(g_server, do_ptr_motion, arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_sendPointerButton(JNIEnv *env, jobject thiz,
        jlong id, jint button, jboolean pressed) {
    if (!g_server) return;
    struct ptr_button_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id; arg->button = button; arg->pressed = pressed;
    chaz_post(g_server, do_ptr_button, arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_sendPointerScroll(JNIEnv *env, jobject thiz,
        jlong id, jfloat dx, jfloat dy) {
    if (!g_server) return;
    struct ptr_scroll_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id; arg->dx = dx; arg->dy = dy;
    chaz_post(g_server, do_ptr_scroll, arg);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_sendTouch(JNIEnv *env, jobject thiz,
        jlong id, jint pointerId, jfloat x, jfloat y, jint action) {
    /* Map single-touch to the pointer for now: ACTION_DOWN=0, UP=1, MOVE=2. */
    if (!g_server) return;
    if (action == 2 /* MOVE */ || action == 0 /* DOWN */) {
        struct ptr_motion_arg *m = calloc(1, sizeof(*m));
        m->id = (uint64_t) id; m->x = x; m->y = y;
        chaz_post(g_server, do_ptr_motion, m);
    }
    struct ptr_button_arg *b = calloc(1, sizeof(*b));
    b->id = (uint64_t) id; b->button = 0x110 /* BTN_LEFT */;
    b->pressed = (action == 0);
    if (action == 0 || action == 1) chaz_post(g_server, do_ptr_button, b); else free(b);
}

JNIEXPORT void JNICALL
Java_com_chazlaunch_compositor_Compositor_sendKey(JNIEnv *env, jobject thiz,
        jlong id, jint keyCode, jboolean pressed) {
    if (!g_server) return;
    struct key_arg *arg = calloc(1, sizeof(*arg));
    arg->id = (uint64_t) id; arg->keycode = keyCode; arg->pressed = pressed;
    chaz_post(g_server, do_key, arg);
}
