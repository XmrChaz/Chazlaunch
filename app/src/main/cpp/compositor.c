#include "compositor.h"
#include "xdg-shell-server-protocol.h"

#include <android/log.h>
#include <wayland-server-protocol.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <time.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, CHAZ_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CHAZ_LOG_TAG, __VA_ARGS__)

/* ======================================================================== */
/* wl_surface                                                               */
/* ======================================================================== */

static void surface_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}

static void surface_attach(struct wl_client *c, struct wl_resource *r,
                           struct wl_resource *buffer, int32_t x, int32_t y) {
    struct chaz_surface *s = wl_resource_get_user_data(r);
    s->pending_buffer = buffer;
}

static void surface_damage(struct wl_client *c, struct wl_resource *r,
                           int32_t x, int32_t y, int32_t w, int32_t h) {
    struct chaz_surface *s = wl_resource_get_user_data(r);
    s->has_damage = true;
}

static void surface_frame(struct wl_client *c, struct wl_resource *r, uint32_t cb) {
    /* Fire the frame callback immediately — Android drives vsync for us, so we
     * let clients render as fast as they commit; good enough for a launcher. */
    struct wl_resource *callback =
            wl_resource_create(c, &wl_callback_interface, 1, cb);
    wl_callback_send_done(callback, 0);
    wl_resource_destroy(callback);
}

static void surface_noop_region(struct wl_client *c, struct wl_resource *r,
                                struct wl_resource *region) {}
static void surface_noop_i32(struct wl_client *c, struct wl_resource *r, int32_t v) {}
static void surface_noop_i32x2(struct wl_client *c, struct wl_resource *r,
                               int32_t a, int32_t b) {}
static void region_noop_i32x4(struct wl_client *c, struct wl_resource *r,
                              int32_t x, int32_t y, int32_t w, int32_t h) {}
static void surface_damage_buffer(struct wl_client *c, struct wl_resource *r,
                                  int32_t x, int32_t y, int32_t w, int32_t h) {
    struct chaz_surface *s = wl_resource_get_user_data(r);
    s->has_damage = true;
}

static void surface_commit(struct wl_client *c, struct wl_resource *r) {
    struct chaz_surface *s = wl_resource_get_user_data(r);
    s->current_buffer = s->pending_buffer;

    if (s->toplevel) {
        if (!s->toplevel->mapped && s->current_buffer) {
            s->toplevel->mapped = true;
            LOGI("toplevel %llu mapped", (unsigned long long) s->toplevel->id);
            chaz_notify_toplevel_created(s->toplevel);
        }
        if (s->toplevel->mapped)
            chaz_present(s->toplevel);
    }
    s->has_damage = false;
}

static const struct wl_surface_interface surface_impl = {
        .destroy = surface_destroy,
        .attach = surface_attach,
        .damage = surface_damage,
        .frame = surface_frame,
        .set_opaque_region = surface_noop_region,
        .set_input_region = surface_noop_region,
        .commit = surface_commit,
        .set_buffer_transform = surface_noop_i32,
        .set_buffer_scale = surface_noop_i32,
        .damage_buffer = surface_damage_buffer,
        .offset = surface_noop_i32x2,
};

static void surface_resource_destroy(struct wl_resource *r) {
    struct chaz_surface *s = wl_resource_get_user_data(r);
    free(s);
}

/* ======================================================================== */
/* wl_compositor                                                            */
/* ======================================================================== */

static void compositor_create_surface(struct wl_client *client,
                                       struct wl_resource *r, uint32_t id) {
    struct chaz_surface *s = calloc(1, sizeof(*s));
    s->resource = wl_resource_create(client, &wl_surface_interface,
                                     wl_resource_get_version(r), id);
    wl_resource_set_implementation(s->resource, &surface_impl, s,
                                   surface_resource_destroy);
}

static void compositor_create_region(struct wl_client *client,
                                      struct wl_resource *r, uint32_t id) {
    /* Regions are only used for opaque/input hints we ignore; hand back a
     * no-op resource so clients don't error out. */
    struct wl_resource *region =
            wl_resource_create(client, &wl_region_interface, 1, id);
    static const struct wl_region_interface region_impl = {
            .destroy = surface_destroy,
            .add = region_noop_i32x4,
            .subtract = region_noop_i32x4,
    };
    wl_resource_set_implementation(region, &region_impl, NULL, NULL);
}

static const struct wl_compositor_interface compositor_impl = {
        .create_surface = compositor_create_surface,
        .create_region = compositor_create_region,
};

static void compositor_bind(struct wl_client *client, void *data,
                            uint32_t version, uint32_t id) {
    struct wl_resource *r =
            wl_resource_create(client, &wl_compositor_interface, version, id);
    wl_resource_set_implementation(r, &compositor_impl, data, NULL);
}

/* ======================================================================== */
/* xdg_shell                                                                */
/* ======================================================================== */

/* The compositor is a per-process singleton, so a module-global server handle
 * keeps the xdg glue readable (it is set in chaz_server_create). */
struct chaz_server *g_server = NULL;

static void toplevel_set_title(struct wl_client *c, struct wl_resource *r,
                               const char *title) {
    struct chaz_toplevel *t = wl_resource_get_user_data(r);
    free(t->title);
    t->title = strdup(title ? title : "");
    if (t->mapped) chaz_notify_title_changed(t);
}

static void toplevel_set_app_id(struct wl_client *c, struct wl_resource *r,
                                const char *app_id) {
    struct chaz_toplevel *t = wl_resource_get_user_data(r);
    free(t->app_id);
    t->app_id = strdup(app_id ? app_id : "");
}

static void toplevel_destroy(struct wl_client *c, struct wl_resource *r) {
    wl_resource_destroy(r);
}
static void tl_noop(struct wl_client *c, struct wl_resource *r) {}
static void tl_noop_res_u32x2(struct wl_client *c, struct wl_resource *r,
                              struct wl_resource *seat, uint32_t serial,
                              int32_t x, int32_t y) {}
static void tl_noop_res_u32(struct wl_client *c, struct wl_resource *r,
                            struct wl_resource *seat, uint32_t serial) {}
static void tl_noop_res_u32_u32(struct wl_client *c, struct wl_resource *r,
                                struct wl_resource *seat, uint32_t serial,
                                uint32_t edges) {}
static void tl_noop_i32x2(struct wl_client *c, struct wl_resource *r,
                          int32_t w, int32_t h) {}
static void tl_noop_res(struct wl_client *c, struct wl_resource *r,
                        struct wl_resource *out) {}

static const struct xdg_toplevel_interface xdg_toplevel_impl = {
        .destroy = toplevel_destroy,
        .set_parent = tl_noop_res,
        .set_title = toplevel_set_title,
        .set_app_id = toplevel_set_app_id,
        .show_window_menu = tl_noop_res_u32x2,
        .move = tl_noop_res_u32,
        .resize = tl_noop_res_u32_u32,
        .set_max_size = tl_noop_i32x2,
        .set_min_size = tl_noop_i32x2,
        .set_maximized = tl_noop,
        .unset_maximized = tl_noop,
        .set_fullscreen = tl_noop_res,
        .unset_fullscreen = tl_noop,
        .set_minimized = tl_noop,
};

static void toplevel_resource_destroy(struct wl_resource *r) {
    struct chaz_toplevel *t = wl_resource_get_user_data(r);
    if (!t) return;
    wl_list_remove(&t->link);
    chaz_notify_toplevel_closed(t->id);
    if (t->native_window) ANativeWindow_release(t->native_window);
    if (t->surface) t->surface->toplevel = NULL;
    free(t->title);
    free(t->app_id);
    free(t);
}

static void xdg_surface_get_toplevel(struct wl_client *c, struct wl_resource *r,
                                     uint32_t id) {
    struct chaz_surface *surface = wl_resource_get_user_data(r);

    struct chaz_toplevel *t = calloc(1, sizeof(*t));
    t->surface = surface;
    surface->toplevel = t;
    t->xdg_surface = r;
    t->xdg_toplevel = wl_resource_create(c, &xdg_toplevel_interface,
                                         wl_resource_get_version(r), id);
    wl_resource_set_implementation(t->xdg_toplevel, &xdg_toplevel_impl, t,
                                   toplevel_resource_destroy);

    t->id = g_server->next_id++;
    t->out_width = 1280;
    t->out_height = 720;
    wl_list_insert(&g_server->toplevels, &t->link);

    /* Initial configure: advertise a default size + activated state. The real
     * size is driven later by the Android task via chaz_resize_toplevel. */
    struct wl_array states;
    wl_array_init(&states);
    uint32_t *st = wl_array_add(&states, sizeof(uint32_t));
    *st = XDG_TOPLEVEL_STATE_ACTIVATED;
    xdg_toplevel_send_configure(t->xdg_toplevel, t->out_width, t->out_height, &states);
    wl_array_release(&states);
    xdg_surface_send_configure(r, ++g_server->serial);
}

/* xdg_positioner: clients (GTK especially) set many fields before creating a
 * popup. We don't reposition popups, but the requests must not hit a NULL impl,
 * so every setter is a no-op. */
static void pos_set_size(struct wl_client *c, struct wl_resource *r, int32_t w, int32_t h) {}
static void pos_set_anchor_rect(struct wl_client *c, struct wl_resource *r,
                                int32_t x, int32_t y, int32_t w, int32_t h) {}
static void pos_set_u32(struct wl_client *c, struct wl_resource *r, uint32_t v) {}
static void pos_set_offset(struct wl_client *c, struct wl_resource *r, int32_t x, int32_t y) {}
static void pos_noop(struct wl_client *c, struct wl_resource *r) {}
static void pos_set_parent_size(struct wl_client *c, struct wl_resource *r,
                                int32_t w, int32_t h) {}
static void pos_set_parent_configure(struct wl_client *c, struct wl_resource *r,
                                     uint32_t serial) {}

static const struct xdg_positioner_interface positioner_impl = {
        .destroy = toplevel_destroy,
        .set_size = pos_set_size,
        .set_anchor_rect = pos_set_anchor_rect,
        .set_anchor = pos_set_u32,
        .set_gravity = pos_set_u32,
        .set_constraint_adjustment = pos_set_u32,
        .set_offset = pos_set_offset,
        .set_reactive = pos_noop,
        .set_parent_size = pos_set_parent_size,
        .set_parent_configure = pos_set_parent_configure,
};

static void popup_reposition(struct wl_client *c, struct wl_resource *r,
                             struct wl_resource *positioner, uint32_t token) {}

static void xdg_surface_get_popup(struct wl_client *c, struct wl_resource *r,
                                  uint32_t id, struct wl_resource *parent,
                                  struct wl_resource *positioner) {
    /* Popups (menus/tooltips) are drawn by the client into their own surface;
     * we don't position them specially — create a plain surface role. */
    struct wl_resource *popup =
            wl_resource_create(c, &xdg_popup_interface, wl_resource_get_version(r), id);
    static const struct xdg_popup_interface popup_impl = {
            .destroy = toplevel_destroy,
            .grab = tl_noop_res_u32,
            .reposition = popup_reposition,
    };
    wl_resource_set_implementation(popup, &popup_impl, NULL, NULL);
    xdg_surface_send_configure(r, ++g_server->serial);
}

static void xdg_surface_set_geometry(struct wl_client *c, struct wl_resource *r,
                                     int32_t x, int32_t y, int32_t w, int32_t h) {}
static void xdg_surface_ack_configure(struct wl_client *c, struct wl_resource *r,
                                      uint32_t serial) {}

static const struct xdg_surface_interface xdg_surface_impl = {
        .destroy = toplevel_destroy,
        .get_toplevel = xdg_surface_get_toplevel,
        .get_popup = xdg_surface_get_popup,
        .set_window_geometry = xdg_surface_set_geometry,
        .ack_configure = xdg_surface_ack_configure,
};

static void xdg_get_xdg_surface(struct wl_client *c, struct wl_resource *r,
                                uint32_t id, struct wl_resource *surface_res) {
    struct chaz_surface *surface = wl_resource_get_user_data(surface_res);
    struct wl_resource *xdg_surface =
            wl_resource_create(c, &xdg_surface_interface, wl_resource_get_version(r), id);
    wl_resource_set_implementation(xdg_surface, &xdg_surface_impl, surface, NULL);
}

static void xdg_create_positioner(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *p =
            wl_resource_create(c, &xdg_positioner_interface, wl_resource_get_version(r), id);
    wl_resource_set_implementation(p, &positioner_impl, NULL, NULL);
}
static void xdg_pong(struct wl_client *c, struct wl_resource *r, uint32_t serial) {}

static const struct xdg_wm_base_interface xdg_wm_base_impl = {
        .destroy = toplevel_destroy,
        .create_positioner = xdg_create_positioner,
        .get_xdg_surface = xdg_get_xdg_surface,
        .pong = xdg_pong,
};

static void xdg_wm_base_bind(struct wl_client *client, void *data,
                             uint32_t version, uint32_t id) {
    struct wl_resource *r =
            wl_resource_create(client, &xdg_wm_base_interface, version, id);
    wl_resource_set_implementation(r, &xdg_wm_base_impl, data, NULL);
}

/* ======================================================================== */
/* wl_seat (pointer + keyboard capability advertisement)                    */
/* ======================================================================== */

static void pointer_set_cursor(struct wl_client *c, struct wl_resource *r, uint32_t serial,
                               struct wl_resource *surface, int32_t hx, int32_t hy) {
    /* We draw no cursor (touch-first); Android shows its own pointer. */
}

static void seat_get_pointer(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    g_server->pointer =
            wl_resource_create(c, &wl_pointer_interface, wl_resource_get_version(r), id);
    static const struct wl_pointer_interface pointer_impl = {
            .set_cursor = pointer_set_cursor,
            .release = toplevel_destroy,
    };
    wl_resource_set_implementation(g_server->pointer, &pointer_impl, NULL, NULL);
}
static void seat_get_keyboard(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    g_server->keyboard =
            wl_resource_create(c, &wl_keyboard_interface, wl_resource_get_version(r), id);
    static const struct wl_keyboard_interface kb_impl = {.release = toplevel_destroy};
    wl_resource_set_implementation(g_server->keyboard, &kb_impl, NULL, NULL);
}
static void seat_get_touch(struct wl_client *c, struct wl_resource *r, uint32_t id) {
    struct wl_resource *t =
            wl_resource_create(c, &wl_touch_interface, wl_resource_get_version(r), id);
    static const struct wl_touch_interface touch_impl = {.release = toplevel_destroy};
    wl_resource_set_implementation(t, &touch_impl, NULL, NULL);
}
static void seat_release(struct wl_client *c, struct wl_resource *r) {}

static const struct wl_seat_interface seat_impl = {
        .get_pointer = seat_get_pointer,
        .get_keyboard = seat_get_keyboard,
        .get_touch = seat_get_touch,
        .release = seat_release,
};

static void seat_bind(struct wl_client *client, void *data, uint32_t version, uint32_t id) {
    struct wl_resource *r =
            wl_resource_create(client, &wl_seat_interface, version, id);
    wl_resource_set_implementation(r, &seat_impl, data, NULL);
    wl_seat_send_capabilities(r,
                              WL_SEAT_CAPABILITY_POINTER | WL_SEAT_CAPABILITY_KEYBOARD |
                              WL_SEAT_CAPABILITY_TOUCH);
}

/* ======================================================================== */
/* Cross-thread task queue                                                  */
/* ======================================================================== */

static int task_pipe_readable(int fd, uint32_t mask, void *data) {
    struct chaz_server *s = data;
    char drain[64];
    while (read(fd, drain, sizeof(drain)) > 0) { /* drain wake bytes */ }

    for (;;) {
        pthread_mutex_lock(&s->task_lock);
        struct chaz_task *task = NULL;
        if (!wl_list_empty(&s->tasks)) {
            task = wl_container_of(s->tasks.next, task, link);
            wl_list_remove(&task->link);
        }
        pthread_mutex_unlock(&s->task_lock);
        if (!task) break;
        task->fn(s, task->arg);
        free(task);
    }
    wl_display_flush_clients(s->display);
    return 0;
}

void chaz_post(struct chaz_server *s, void (*fn)(struct chaz_server *, void *), void *arg) {
    struct chaz_task *task = calloc(1, sizeof(*task));
    task->fn = fn;
    task->arg = arg;
    pthread_mutex_lock(&s->task_lock);
    wl_list_insert(s->tasks.prev, &task->link);
    pthread_mutex_unlock(&s->task_lock);
    char wake = 1;
    if (write(s->task_pipe[1], &wake, 1) < 0 && errno != EAGAIN) { /* loop still wakes */ }
}

/* ======================================================================== */
/* Server lifecycle                                                         */
/* ======================================================================== */

struct chaz_server *chaz_server_create(const char *socket_dir, float scale) {
    struct chaz_server *s = calloc(1, sizeof(*s));
    s->scale = scale > 0 ? scale : 1.0f;
    s->next_id = 1;
    wl_list_init(&s->toplevels);
    wl_list_init(&s->tasks);
    pthread_mutex_init(&s->task_lock, NULL);

    /* Keep the Wayland socket inside the dir the app bind-mounts into the guest. */
    setenv("XDG_RUNTIME_DIR", socket_dir, 1);

    s->display = wl_display_create();
    s->loop = wl_display_get_event_loop(s->display);

    /* Self-pipe used to wake the loop when JNI threads enqueue tasks. */
    if (pipe(s->task_pipe) == 0) {
        fcntl(s->task_pipe[0], F_SETFL, O_NONBLOCK);
        fcntl(s->task_pipe[1], F_SETFL, O_NONBLOCK);
        wl_event_loop_add_fd(s->loop, s->task_pipe[0], WL_EVENT_READABLE,
                             task_pipe_readable, s);
    }

    const char *name = wl_display_add_socket_auto(s->display);
    strncpy(s->socket_name, name ? name : "wayland-0", sizeof(s->socket_name) - 1);

    wl_display_init_shm(s->display);    /* built-in wl_shm implementation */

    s->compositor_global = wl_global_create(s->display, &wl_compositor_interface, 4,
                                             s, compositor_bind);
    s->seat_global = wl_global_create(s->display, &wl_seat_interface, 7, s, seat_bind);
    s->xdg_wm_base_global = wl_global_create(s->display, &xdg_wm_base_interface, 3,
                                             s, xdg_wm_base_bind);

    g_server = s;
    LOGI("compositor socket=%s dir=%s scale=%.2f", s->socket_name, socket_dir, s->scale);
    return s;
}

void chaz_server_run(struct chaz_server *s) {
    s->running = true;
    wl_display_run(s->display);   /* blocks until wl_display_terminate */
}

void chaz_server_stop(struct chaz_server *s) {
    if (!s) return;
    s->running = false;
    wl_display_terminate(s->display);
}

struct chaz_toplevel *chaz_find_toplevel(struct chaz_server *s, uint64_t id) {
    struct chaz_toplevel *t;
    wl_list_for_each(t, &s->toplevels, link) {
        if (t->id == id) return t;
    }
    return NULL;
}

/* ======================================================================== */
/* Presentation: committed wl_shm buffer -> ANativeWindow                   */
/* ======================================================================== */

void chaz_present(struct chaz_toplevel *t) {
    if (!t->native_window || !t->surface || !t->surface->current_buffer) return;

    struct wl_shm_buffer *shm = wl_shm_buffer_get(t->surface->current_buffer);
    if (!shm) return;   /* dmabuf/other buffer types not supported here */

    int32_t w = wl_shm_buffer_get_width(shm);
    int32_t h = wl_shm_buffer_get_height(shm);
    int32_t stride = wl_shm_buffer_get_stride(shm);
    uint32_t format = wl_shm_buffer_get_format(shm);

    ANativeWindow_setBuffersGeometry(t->native_window, w, h, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer out;
    if (ANativeWindow_lock(t->native_window, &out, NULL) != 0) return;

    wl_shm_buffer_begin_access(shm);
    const uint8_t *src = wl_shm_buffer_get_data(shm);
    uint8_t *dst = out.bits;
    int copy_h = h < out.height ? h : out.height;
    int copy_w = w < out.width ? w : out.width;

    for (int y = 0; y < copy_h; y++) {
        const uint8_t *srow = src + (size_t) y * stride;
        uint8_t *drow = dst + (size_t) y * out.stride * 4;
        for (int x = 0; x < copy_w; x++) {
            /* wl_shm ARGB/XRGB8888 is little-endian B,G,R,A; Android RGBA wants
             * R,G,B,A — swap R/B and force opaque for XRGB. */
            uint8_t b = srow[x * 4 + 0];
            uint8_t g = srow[x * 4 + 1];
            uint8_t r = srow[x * 4 + 2];
            uint8_t a = (format == WL_SHM_FORMAT_XRGB8888) ? 0xFF : srow[x * 4 + 3];
            drow[x * 4 + 0] = r;
            drow[x * 4 + 1] = g;
            drow[x * 4 + 2] = b;
            drow[x * 4 + 3] = a;
        }
    }
    wl_shm_buffer_end_access(shm);
    ANativeWindow_unlockAndPost(t->native_window);

    /* Release the buffer back to the client so it can reuse it. */
    wl_buffer_send_release(t->surface->current_buffer);
}

/* ======================================================================== */
/* Input injection (runs on the compositor thread via chaz_post)            */
/* ======================================================================== */

static uint32_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint32_t) (ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void pointer_frame(struct chaz_server *s) {
    if (s->pointer && wl_resource_get_version(s->pointer) >= WL_POINTER_FRAME_SINCE_VERSION)
        wl_pointer_send_frame(s->pointer);
}

void chaz_pointer_motion(struct chaz_server *s, struct chaz_toplevel *t, float x, float y) {
    if (!s->pointer || !t || !t->surface) return;
    /* Scale from Android surface pixels to the client's buffer coordinates. */
    wl_fixed_t fx = wl_fixed_from_double(x * s->scale);
    wl_fixed_t fy = wl_fixed_from_double(y * s->scale);
    wl_pointer_send_enter(s->pointer, ++s->serial, t->surface->resource, fx, fy);
    wl_pointer_send_motion(s->pointer, now_ms(), fx, fy);
    pointer_frame(s);
}

void chaz_pointer_button(struct chaz_server *s, struct chaz_toplevel *t, int button, bool pressed) {
    if (!s->pointer) return;
    wl_pointer_send_button(s->pointer, ++s->serial, now_ms(), (uint32_t) button,
                           pressed ? WL_POINTER_BUTTON_STATE_PRESSED
                                   : WL_POINTER_BUTTON_STATE_RELEASED);
    pointer_frame(s);
}

void chaz_pointer_scroll(struct chaz_server *s, struct chaz_toplevel *t, float dx, float dy) {
    if (!s->pointer) return;
    if (dy != 0)
        wl_pointer_send_axis(s->pointer, now_ms(), WL_POINTER_AXIS_VERTICAL_SCROLL,
                             wl_fixed_from_double(dy));
    if (dx != 0)
        wl_pointer_send_axis(s->pointer, now_ms(), WL_POINTER_AXIS_HORIZONTAL_SCROLL,
                             wl_fixed_from_double(dx));
    pointer_frame(s);
}

void chaz_key(struct chaz_server *s, struct chaz_toplevel *t, int keycode, bool pressed) {
    if (!s->keyboard || !t || !t->surface) return;
    struct wl_array keys;
    wl_array_init(&keys);
    wl_keyboard_send_enter(s->keyboard, ++s->serial, t->surface->resource, &keys);
    wl_array_release(&keys);
    /* Android keycodes are offset from evdev by 8 (the X11 convention clients
     * still expect when no xkb keymap is negotiated). */
    wl_keyboard_send_key(s->keyboard, ++s->serial, now_ms(), (uint32_t) (keycode + 8),
                         pressed ? WL_KEYBOARD_KEY_STATE_PRESSED
                                 : WL_KEYBOARD_KEY_STATE_RELEASED);
}
