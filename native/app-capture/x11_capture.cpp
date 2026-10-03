#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/extensions/Xcomposite.h>
#include <X11/extensions/XRes.h>
#include <X11/extensions/Xdamage.h>
#include <X11/extensions/Xrender.h>
#include <systemd/sd-bus.h>
#include <systemd/sd-login.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <limits>
#include <optional>
#include <poll.h>
#include <signal.h>
#include <stdexcept>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
uint64_t number(const char* value) {
    require(value && *value && *value != '-', "invalid argument");
    char* end = nullptr;
    errno = 0;
    auto result = std::strtoull(value, &end, 10);
    require(errno == 0 && end && !*end, "invalid argument");
    return result;
}

class Session {
    sd_bus* bus_ = nullptr;
    std::string path_;
    pid_t parent_;
    bool interrupted_ = false;
    static int unavailable(sd_bus_message*, void* context, sd_bus_error*) {
        static_cast<Session*>(context)->interrupted_ = true;
        return 0;
    }
public:
    explicit Session(pid_t parent) : parent_(parent) {
        char* session = nullptr;
        require(getppid() == parent_ && sd_pid_get_session(parent_, &session) >= 0,
                "capture must belong to an interactive parent session");
        std::string id(session);
        free(session);
        char* type = nullptr;
        require(sd_session_get_type(id.c_str(), &type) >= 0, "session type unavailable");
        bool supported = std::strcmp(type, "x11") == 0 || std::strcmp(type, "wayland") == 0;
        free(type);
        // Current JDK/Compose Linux windows are X11 clients even in a Wayland
        // session. Admission still requires a real X display and XRes-bound
        // client; native Wayland surfaces and portal guesses are not supported.
        require(supported && std::getenv("DISPLAY") && *std::getenv("DISPLAY"),
                "exact capture requires an X11 or XWayland client in a desktop session");
        require(sd_bus_open_system(&bus_) >= 0, "session bus unavailable");
        // No frame may wait behind the default 25-second D-Bus method timeout.
        sd_bus_set_method_call_timeout(bus_, 250000);
        sd_bus_message* reply = nullptr;
        int result = sd_bus_call_method(bus_, "org.freedesktop.login1",
            "/org/freedesktop/login1", "org.freedesktop.login1.Manager", "GetSession",
            nullptr, &reply, "s", id.c_str());
        const char* path = nullptr;
        if (result >= 0) result = sd_bus_message_read(reply, "o", &path);
        if (result >= 0 && path) path_ = path;
        sd_bus_message_unref(reply);
        require(!path_.empty(), "session identity unavailable");
        require(sd_bus_match_signal(bus_, nullptr, "org.freedesktop.login1", path_.c_str(),
            "org.freedesktop.login1.Session", "Lock", unavailable, this) >= 0,
            "session lock events unavailable");
        // Either PrepareForSleep edge is terminal: waking never silently restarts a share.
        require(sd_bus_match_signal(bus_, nullptr, "org.freedesktop.login1", "/org/freedesktop/login1",
            "org.freedesktop.login1.Manager", "PrepareForSleep", unavailable, this) >= 0,
            "system sleep events unavailable");
        check();
    }
    ~Session() { sd_bus_unref(bus_); }
    void check() {
        int result;
        do { result = sd_bus_process(bus_, nullptr); } while (result > 0);
        require(result >= 0 && !interrupted_, "OS session authority ended");
        require(getppid() == parent_, "capture parent ended");
        int active = 0, locked = 1;
        require(sd_bus_get_property_trivial(bus_, "org.freedesktop.login1", path_.c_str(),
            "org.freedesktop.login1.Session", "Active", nullptr, 'b', &active) >= 0 && active,
            "session is inactive or unavailable");
        require(sd_bus_get_property_trivial(bus_, "org.freedesktop.login1", path_.c_str(),
            "org.freedesktop.login1.Session", "LockedHint", nullptr, 'b', &locked) >= 0 && !locked,
            "session is locked or unavailable");
    }
};

class DisplayOwner {
public:
    Display* display = nullptr;
    DisplayOwner() {
        // The error handler is confined to this helper, never the application's JVM.
        XSetErrorHandler([](Display*, XErrorEvent*) -> int { _exit(72); });
        display = XOpenDisplay(nullptr);
        require(display != nullptr, "X11 display unavailable");
        int event = 0, error = 0, major = 0, minor = 0;
        require(XCompositeQueryExtension(display, &event, &error) &&
            XCompositeQueryVersion(display, &major, &minor) && (major > 0 || minor >= 2),
            "XComposite named pixmaps unavailable");
        require(XResQueryExtension(display, &event, &error) &&
            XResQueryVersion(display, &major, &minor) && (major > 1 || (major == 1 && minor >= 2)),
            "XRes process ownership unavailable");
    }
    ~DisplayOwner() { if (display) XCloseDisplay(display); }
};

pid_t windowOwner(Display* display, Window client) {
    XResClientIdSpec spec {client, XRES_CLIENT_ID_PID_MASK};
    long count = 0;
    XResClientIdValue* values = nullptr;
    require(XResQueryClientIds(display, 1, &spec, &count, &values) == Success,
            "window owner query failed");
    pid_t owner = count == 1 ? XResGetClientPid(&values[0]) : -1;
    XResClientIdsDestroy(count, values);
    require(owner > 1, "window process identity unavailable");
    return owner;
}

void verifyOwner(Display* display, Window client, pid_t expected) {
    require(windowOwner(display, client) == expected, "window does not belong to capture parent");
}

Window frameAncestor(Display* display, Window client) {
    Window current = client;
    for (int depth = 0; depth < 32; ++depth) {
        Window root = 0, parent = 0, *children = nullptr;
        unsigned count = 0;
        require(XQueryTree(display, current, &root, &parent, &children, &count), "window identity lost");
        if (children) XFree(children);
        require(current != root && parent != 0, "display capture is prohibited");
        if (parent == root) return current;
        current = parent;
    }
    throw std::runtime_error("window ancestry is unbounded");
}

// The WM frame may contain decorations, but no sibling application client.
// Stop at the selected client: its embedded browser/Compose descendants are the
// authorized content. All other branches must belong to the frame's WM process.
void verifyFrameTree(Display* display, Window node, Window client, pid_t decorator, int& budget) {
    require(--budget >= 0, "window frame tree is unbounded");
    if (node == client) return;
    verifyOwner(display, node, decorator);
    Window root = 0, parent = 0, *children = nullptr;
    unsigned count = 0;
    require(XQueryTree(display, node, &root, &parent, &children, &count), "frame tree unavailable");
    std::vector<Window> descendants;
    if (children) { descendants.assign(children, children + count); XFree(children); }
    for (auto child : descendants) verifyFrameTree(display, child, client, decorator, budget);
}

struct CaptureRegion { int x, y, width, height; };
struct HostWindowGeometry { int width, height, left, right, top, bottom; };
CaptureRegion captureRegion(Display* display, Window client, Window frame,
                            std::optional<HostWindowGeometry> host = std::nullopt) {
    XWindowAttributes clientBounds {}, frameBounds {};
    require(XGetWindowAttributes(display, client, &clientBounds) &&
        XGetWindowAttributes(display, frame, &frameBounds), "window geometry unavailable");
    if (client == frame && !host) return {0, 0, clientBounds.width, clientBounds.height};
    int x = 0, y = 0;
    Window child = 0;
    require(XTranslateCoordinates(display, client, frame, 0, 0, &x, &y, &child), "client origin unavailable");
    if (host) {
        // JDK17 may include WM shadow margins in AWT insets. Its local input
        // coordinates, not guessed EWMH/shadow heuristics, define the shared
        // logical window. Verify that contract against this exact native client.
        require(host->width > 0 && host->height > 0 && host->width <= 8192 && host->height <= 8192 &&
            host->left >= 0 && host->right >= 0 && host->top >= 0 && host->bottom >= 0,
            "host window geometry invalid");
        require(clientBounds.width + host->left + host->right == host->width &&
            clientBounds.height + host->top + host->bottom == host->height,
            "host geometry does not match native client and insets");
        CaptureRegion region {x - host->left, y - host->top, host->width, host->height};
        require(region.x >= 0 && region.y >= 0 && region.x + region.width <= frameBounds.width &&
            region.y + region.height <= frameBounds.height, "host crop escapes its verified frame");
        return region;
    }
    Atom actual = None;
    int format = 0;
    unsigned long count = 0, remaining = 0;
    unsigned char* data = nullptr;
    auto property = XInternAtom(display, "_NET_FRAME_EXTENTS", True);
    require(property != None && XGetWindowProperty(display, client, property, 0, 4, False, XA_CARDINAL,
        &actual, &format, &count, &remaining, &data) == Success, "window frame extents unavailable");
    bool valid = actual == XA_CARDINAL && format == 32 && count == 4 && remaining == 0 && data;
    std::array<unsigned long, 4> extents {};
    if (valid) std::copy_n(reinterpret_cast<unsigned long*>(data), 4, extents.begin());
    if (data) XFree(data);
    require(valid && std::all_of(extents.begin(), extents.end(), [](auto value) { return value <= 8192; }),
            "window frame extents invalid");
    CaptureRegion region {x - int(extents[0]), y - int(extents[2]),
        clientBounds.width + int(extents[0] + extents[1]),
        clientBounds.height + int(extents[2] + extents[3])};
    require(region.x >= 0 && region.y >= 0 && region.width > 0 && region.height > 0 &&
        region.x + region.width <= frameBounds.width && region.y + region.height <= frameBounds.height,
        "decorated window bounds escape its owned frame");
    return region;
}

void put32(uint8_t* output, uint32_t value) {
    for (int i = 3; i >= 0; --i) { output[i] = value & 255; value >>= 8; }
}
uint8_t channel(unsigned long pixel, unsigned long mask) {
    if (!mask) return 0;
    unsigned shift = 0;
    while (!(mask & 1)) { mask >>= 1; ++shift; }
    return static_cast<uint8_t>(((pixel >> shift) & mask) * 255 / mask);
}

// Nonblocking writes bound buffering to one complete frame. A reader which stops
// draining cannot leave a recording process alive indefinitely or queue old frames.
void writeFrame(const std::vector<uint8_t>& bytes, Session& session) {
    auto deadline = Clock::now() + std::chrono::milliseconds(250);
    size_t offset = 0;
    while (offset != bytes.size()) {
        require(Clock::now() < deadline, "capture reader stalled");
        pollfd fd {STDOUT_FILENO, POLLOUT, 0};
        int ready = poll(&fd, 1, 20);
        if (ready < 0 && errno == EINTR) continue;
        require(ready >= 0 && !(fd.revents & (POLLERR | POLLHUP | POLLNVAL)), "capture reader closed");
        if (ready == 0) { session.check(); continue; }
        auto count = write(STDOUT_FILENO, bytes.data() + offset, bytes.size() - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        require(count > 0, "capture write failed");
        offset += static_cast<size_t>(count);
    }
    session.check();
}

class WindowStream {
    Display* display_;
    Window client_, frame_;
    Pixmap pixmap_ = 0, scaled_ = 0;
    Picture sourcePicture_ = 0, scaledPicture_ = 0;
    pid_t parent_;
    int width_, height_;
    unsigned sourceWidth_ = 0, sourceHeight_ = 0;
    int sourceX_ = 0, sourceY_ = 0, frameWidth_ = 0, frameHeight_ = 0;
    int clientWidth_ = 0, clientHeight_ = 0;
    std::vector<uint8_t> bytes_;
    uint64_t sequence_ = 0;
    Damage damage_ = 0;
    int damageEvent_ = 0;
    bool dirty_ = true;
    std::optional<HostWindowGeometry> hostGeometry_;
public:
    WindowStream(Display* display, Window client, pid_t parent, int width, int height,
                 std::optional<HostWindowGeometry> hostGeometry = std::nullopt)
        : display_(display), client_(client), parent_(parent), width_(width), height_(height),
          hostGeometry_(hostGeometry) {
        verifyOwner(display_, client_, parent_);
        XWindowAttributes clientAttributes {};
        require(XGetWindowAttributes(display_, client_, &clientAttributes), "client geometry unavailable");
        clientWidth_ = clientAttributes.width;
        clientHeight_ = clientAttributes.height;
        frame_ = frameAncestor(display_, client_);
        XWindowAttributes attributes {};
        require(XGetWindowAttributes(display_, frame_, &attributes) && attributes.map_state == IsViewable,
                "window is not viewable");
        require(attributes.width > 0 && attributes.height > 0 &&
            attributes.width <= 8192 && attributes.height <= 8192, "source dimensions invalid");
        require(attributes.visual && attributes.visual->c_class == TrueColor, "unsupported capture visual");
        frameWidth_ = attributes.width;
        frameHeight_ = attributes.height;
        auto region = captureRegion(display_, client_, frame_, hostGeometry_);
        sourceX_ = region.x;
        sourceY_ = region.y;
        sourceWidth_ = region.width;
        sourceHeight_ = region.height;
        // Preserve the native frame's proportions. AWT and WM chrome can use
        // different DPI units; stretching into incompatible host dimensions is
        // neither faithful capture nor a valid pointer coordinate contract.
        const int64_t aspectError = int64_t(sourceWidth_) * height_ - int64_t(sourceHeight_) * width_;
        require(std::abs(aspectError) <= std::max(sourceWidth_, sourceHeight_),
                "host and native window aspect ratios disagree");
        XSelectInput(display_, client_, StructureNotifyMask);
        if (frame_ != client_) XSelectInput(display_, frame_, StructureNotifyMask);
        XCompositeRedirectWindow(display_, frame_, CompositeRedirectAutomatic);
        pixmap_ = XCompositeNameWindowPixmap(display_, frame_);
        XSync(display_, False);
        require(pixmap_ != 0, "window backing storage unavailable");
        int renderEvent = 0, renderError = 0;
        require(XRenderQueryExtension(display_, &renderEvent, &renderError), "native pixel scaling unavailable");
        auto* sourceFormat = XRenderFindVisualFormat(display_, attributes.visual);
        auto* outputFormat = XRenderFindStandardFormat(display_, PictStandardARGB32);
        require(sourceFormat && outputFormat, "native pixel formats unavailable");
        XRenderPictureAttributes pictureAttributes {};
        pictureAttributes.repeat = RepeatPad;
        sourcePicture_ = XRenderCreatePicture(display_, pixmap_, sourceFormat, CPRepeat, &pictureAttributes);
        scaled_ = XCreatePixmap(display_, frame_, width_, height_, 32);
        scaledPicture_ = XRenderCreatePicture(display_, scaled_, outputFormat, 0, nullptr);
        XTransform transform {};
        transform.matrix[0][0] = XDoubleToFixed(double(sourceWidth_) / width_);
        transform.matrix[1][1] = XDoubleToFixed(double(sourceHeight_) / height_);
        transform.matrix[0][2] = XDoubleToFixed(sourceX_);
        transform.matrix[1][2] = XDoubleToFixed(sourceY_);
        transform.matrix[2][2] = XDoubleToFixed(1.0);
        XRenderSetPictureTransform(display_, sourcePicture_, &transform);
        XRenderSetPictureFilter(display_, sourcePicture_, FilterBilinear, nullptr, 0);
        int damageError = 0;
        require(XDamageQueryExtension(display_, &damageEvent_, &damageError), "window damage events unavailable");
        damage_ = XDamageCreate(display_, frame_, XDamageReportNonEmpty);
        XSync(display_, False);
        bytes_.resize(24 + static_cast<size_t>(width_) * height_ * 4);
        std::memcpy(bytes_.data(), "BSC1", 4);
        put32(bytes_.data() + 4, width_);
        put32(bytes_.data() + 8, height_);
        put32(bytes_.data() + 12, bytes_.size() - 24);
    }
    ~WindowStream() {
        if (damage_) XDamageDestroy(display_, damage_);
        if (sourcePicture_) XRenderFreePicture(display_, sourcePicture_);
        if (scaledPicture_) XRenderFreePicture(display_, scaledPicture_);
        if (scaled_) XFreePixmap(display_, scaled_);
        if (pixmap_) XFreePixmap(display_, pixmap_);
        XCompositeUnredirectWindow(display_, frame_, CompositeRedirectAutomatic);
    }
    bool hasChanges() {
        while (XPending(display_)) {
            XEvent event {};
            XNextEvent(display_, &event);
            if (event.type == damageEvent_ + XDamageNotify) dirty_ = true;
            require(event.type != DestroyNotify && event.type != UnmapNotify &&
                event.type != ReparentNotify, "window lifecycle changed");
            if (event.type == ConfigureNotify && event.xconfigure.window == client_) {
                require(event.xconfigure.width == clientWidth_ && event.xconfigure.height == clientHeight_,
                        "client resized before its window manager frame");
            }
            if (event.type == ConfigureNotify && event.xconfigure.window == frame_) {
                require(event.xconfigure.width == frameWidth_ && event.xconfigure.height == frameHeight_, "window resized");
            }
        }
        return dirty_;
    }
    const std::vector<uint8_t>& next() {
        hasChanges();
        dirty_ = false;
        // Rearm before reading, so changes racing capture remain queued for the next frame.
        XDamageSubtract(display_, damage_, None, None);
        verifyOwner(display_, client_, parent_);
        require(frameAncestor(display_, client_) == frame_, "window ancestry changed");
        auto region = captureRegion(display_, client_, frame_, hostGeometry_);
        require(region.x == sourceX_ && region.y == sourceY_ &&
            region.width == int(sourceWidth_) && region.height == int(sourceHeight_), "window decorations changed");
        int budget = 256;
        verifyFrameTree(display_, frame_, client_, windowOwner(display_, frame_), budget);
        // Scale on the X server before readback; a 4K source does not cross the pipe
        // or enter the CPU at 4K when WebRTC only needs a smaller output. The source
        // is the verified named backing pixmap, never a screen/root drawable.
        XRenderComposite(display_, PictOpSrc, sourcePicture_, None, scaledPicture_,
                         0, 0, 0, 0, 0, 0, width_, height_);
        XImage* image = XGetImage(display_, scaled_, 0, 0, width_, height_, AllPlanes, ZPixmap);
        require(image != nullptr, "window pixels unavailable");
        // XGetImage on a pixmap has no visual; use the explicitly selected ARGB32
        // output format rather than its zero-initialized XImage mask fields.
        image->red_mask = 0xff0000;
        image->green_mask = 0xff00;
        image->blue_mask = 0xff;
        const bool packed = image->bits_per_pixel == 32 && image->byte_order == LSBFirst &&
            image->red_mask == 0xff0000 && image->green_mask == 0xff00 && image->blue_mask == 0xff;
        for (int y = 0; y < height_; ++y) {
            const auto sourceY = static_cast<unsigned>(y);
            for (int x = 0; x < width_; ++x) {
                const auto sourceX = static_cast<unsigned>(x);
                unsigned long pixel;
                if (packed) {
                    uint32_t value;
                    std::memcpy(&value, image->data + sourceY * image->bytes_per_line + sourceX * 4, 4);
                    pixel = value;
                } else {
                    pixel = XGetPixel(image, sourceX, sourceY);
                }
                auto at = 24 + (static_cast<size_t>(y) * width_ + x) * 4;
                bytes_[at] = channel(pixel, image->blue_mask);
                bytes_[at + 1] = channel(pixel, image->green_mask);
                bytes_[at + 2] = channel(pixel, image->red_mask);
                bytes_[at + 3] = 255;
            }
        }
        XDestroyImage(image);
        verifyOwner(display_, client_, parent_);
        require(frameAncestor(display_, client_) == frame_, "window ancestry changed during capture");
        budget = 256;
        verifyFrameTree(display_, frame_, client_, windowOwner(display_, frame_), budget);
        ++sequence_;
        put32(bytes_.data() + 16, sequence_ >> 32);
        put32(bytes_.data() + 20, sequence_ & 0xffffffff);
        return bytes_;
    }
};
} // namespace

int main(int argc, char** argv) {
    try {
        signal(SIGPIPE, SIG_IGN);
        require(argc == 3 || argc == 6 || argc == 12, "invalid capture invocation");
        bool probe = argc == 3 && std::strcmp(argv[1], "--probe") == 0;
        bool watch = argc == 3 && std::strcmp(argv[1], "--watch") == 0;
        require(argc == 6 || argc == 12 || probe || watch, "invalid capture invocation");
        auto parent = number(argv[(probe || watch) ? 2 : 1]);
        require(parent > 1 && parent <= std::numeric_limits<pid_t>::max(), "invalid parent");
        Session session(static_cast<pid_t>(parent));
        DisplayOwner display;
        if (probe) return 0;
        if (watch) {
            for (;;) {
                session.check();
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }
        auto client = number(argv[2]), width = number(argv[3]), height = number(argv[4]), fps = number(argv[5]);
        require(client != 0 && width > 0 && height > 0 && width <= 1920 && height <= 1920 &&
            width * height <= 4194304 && (fps == 30 || fps == 60), "invalid frame bounds");
        require(fcntl(STDOUT_FILENO, F_SETFL, O_NONBLOCK) == 0, "capture pipe unavailable");
        std::optional<HostWindowGeometry> host;
        if (argc == 12) {
            std::array<int, 6> values {};
            for (int i = 0; i < 6; ++i) {
                auto value = number(argv[i + 6]);
                require(value <= 8192, "host capture geometry is unbounded");
                values[i] = static_cast<int>(value);
            }
            host = HostWindowGeometry {values[0], values[1], values[2], values[3], values[4], values[5]};
        }
        WindowStream stream(display.display, client, static_cast<pid_t>(parent), width, height, host);
        require(fcntl(STDIN_FILENO, F_SETFL, O_NONBLOCK) == 0, "capture control pipe unavailable");
        std::array<uint8_t, 4> rateBytes {};
        size_t rateLength = 0;
        for (;;) {
            auto count = read(STDIN_FILENO, rateBytes.data() + rateLength, rateBytes.size() - rateLength);
            require(count != 0, "capture control pipe closed");
            if (count > 0) {
                rateLength += static_cast<size_t>(count);
                if (rateLength == rateBytes.size()) {
                    fps = (uint32_t(rateBytes[0]) << 24) | (uint32_t(rateBytes[1]) << 16) |
                          (uint32_t(rateBytes[2]) << 8) | rateBytes[3];
                    require(fps == 30 || fps == 60, "invalid capture rate");
                    rateLength = 0;
                }
            } else {
                require(errno == EAGAIN || errno == EINTR, "capture control pipe failed");
            }
            auto next = Clock::now() + std::chrono::microseconds(1000000 / fps);
            session.check();
            if (stream.hasChanges()) writeFrame(stream.next(), session);
            std::this_thread::sleep_until(next);
        }
    } catch (const std::exception& failure) {
        std::cerr << "Exact window capture stopped: " << failure.what() << '\n';
        return 1;
    }
}
