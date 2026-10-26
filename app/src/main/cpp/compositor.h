#ifndef CHAZ_COMPOSITOR_H
#define CHAZ_COMPOSITOR_H

#include <stdbool.h>
#include <stdint.h>
#include <pthread.h>
#include <wayland-server-core.h>
#include <android/native_window.h>

#define CHAZ_LOG_TAG "chazcompositor"

/* A mapped xdg_toplevel == one window the launcher surfaces as an Android task. */
struct chaz_toplevel {
    struct wl_list link;            /* chaz_server.toplevels */
    uint64_t id;                    /* stable id shared with the JVM */
    struct chaz_surface *surface;
    struct wl_resource *xdg_toplevel;
    struct wl_resource *xdg_surface;
    char *title;
    char *app_id;
    bool mapped;

    /* Android side: the Surface of this window's WaylandWindowActivity. */
    ANativeWindow *native_window;
    int32_t out_width;
    int32_t out_height;
};

struct chaz_surface {
    struct wl_resource *resource;   /* wl_surface */
    struct wl_resource *pending_buffer;
    struct wl_resource *current_buffer;
    struct chaz_toplevel *toplevel; /* non-NULL once given an xdg role */
    struct wl_listener buffer_destroy;
    bool has_damage;
};

/* A unit of work marshalled onto the compositor (event-loop) thread. */
struct chaz_task {
    struct wl_list link;
    void (*fn)(struct chaz_server *, void *arg);
    void *arg;
};

struct chaz_server {
    struct wl_display *display;
    struct wl_event_loop *loop;
    char socket_name[32];

    /* Cross-thread task queue: JNI threads enqueue, the loop drains. */
    int task_pipe[2];               /* [0] read (in loop), [1] write (JNI) */
    pthread_mutex_t task_lock;
    struct wl_list tasks;           /* chaz_task.link */

    struct wl_global *compositor_global;
    struct wl_global *shm_global;
    struct wl_global *seat_global;
    struct wl_global *xdg_wm_base_global;

    struct wl_list toplevels;       /* chaz_toplevel.link */
    struct wl_resource *pointer;    /* the (single) client pointer, if bound */
    struct wl_resource *keyboard;

    float scale;
    uint64_t next_id;
    uint32_t serial;

    pthread_t thread;
    bool running;
};

/* compositor.c */
struct chaz_server *chaz_server_create(const char *socket_dir, float scale);
void chaz_server_run(struct chaz_server *server);    /* blocks: wl_display_run */
void chaz_server_stop(struct chaz_server *server);
struct chaz_toplevel *chaz_find_toplevel(struct chaz_server *server, uint64_t id);

/* Enqueue [fn] to run on the compositor thread with [arg] (ownership of arg
 * passes to the task; free it in fn). Safe to call from any thread. */
void chaz_post(struct chaz_server *server,
               void (*fn)(struct chaz_server *, void *), void *arg);

/* Input injection + window ops. Called on the compositor thread (via chaz_post)
 * so they may touch Wayland resources safely. */
void chaz_pointer_motion(struct chaz_server *s, struct chaz_toplevel *t, float x, float y);
void chaz_pointer_button(struct chaz_server *s, struct chaz_toplevel *t, int button, bool pressed);
void chaz_pointer_scroll(struct chaz_server *s, struct chaz_toplevel *t, float dx, float dy);
void chaz_key(struct chaz_server *s, struct chaz_toplevel *t, int keycode, bool pressed);

/* Present a surface's committed buffer into its Android window (compositor.c). */
void chaz_present(struct chaz_toplevel *toplevel);

/* jni_bridge.c — called from compositor.c to notify the JVM. */
void chaz_notify_toplevel_created(struct chaz_toplevel *t);
void chaz_notify_title_changed(struct chaz_toplevel *t);
void chaz_notify_toplevel_closed(uint64_t id);

#endif /* CHAZ_COMPOSITOR_H */
