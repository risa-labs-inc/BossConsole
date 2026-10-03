# Exact native window stream

The X11 helper isolates Xlib's process-global error handler from the JVM. It is
an owned-window capture adapter, never a screen recorder: the supplied client XID
must belong to the supplied, live parent PID according to XRes. Its topmost
non-root ancestor is the decorated frame captured using a named XComposite
pixmap. XDamage emits only changed frames; XRender scales into the requested
output resolution on the server before pixel readback, retaining the final changed
frame when the window becomes idle. Reparenting, resizing, losing the client, or losing the active unlocked
logind session terminates the helper. Kotlin retires/recreates it when the
authorized window geometry changes. No source is selected by title.

Build on Linux with CMake, a C++17 compiler, pkg-config, and development packages
for X11, XComposite, XRes, XDamage, XRender, and libsystemd:

```
cmake -S native/app-capture -B native/app-capture/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/app-capture/build --parallel
```

The binary has no network interface, reads no paths supplied by a remote peer,
and writes bounded raw BGRA frames to its parent's anonymous stdout pipe. The
parent closes/terminates it immediately when sharing demand or authority ends.
It checks the OS session before every frame and after every backpressured write.
Blocked writes time out instead of keeping a hidden recording alive.

Protocol version 1 is a 24-byte big-endian header: magic `BSC1`, width, height,
payload length, and 64-bit sequence, followed by tightly packed opaque BGRA.
Dimensions are positive and at most 1920; pixel count is at most 4,194,304.
The command is `boss-app-capture PID XID WIDTH HEIGHT FPS`; PID must be the
actual parent process, FPS is 30 or 60. X11 additionally accepts six trusted host
geometry arguments: source width/height and left/right/top/bottom AWT insets in
physical pixels. These must match the exact client drawable dimensions and form
a crop wholly inside its verified WM frame. This preserves AWT's local input
coordinates on compositors whose native frames include shadow margins; source
size or insets are never accepted from a remote viewer. Stdin accepts four-byte big-endian rate
updates (30 or 60); EOF terminates capture. `--watch PID` keeps monitoring session
authority even while pixels are paused for minimization or missing demand.
`--probe PID` verifies extensions and
session monitoring without capturing pixels. Exit 75 identifies only a verified
live source's geometry transition. The host can retry it at most three times after
rechecking session/window authority, and only after a clean between-frame EOF;
truncated frames, malformed protocol and every other exit remain terminal. No exit
permits fallback to a display or another window.

The adapter accepts active unlocked X11 or Wayland logind sessions only when a
real X display and the required extensions are available. In a Wayland session,
current JDK/Compose windows remain X11 clients: their exact XWayland XID, XRes
parent-PID ownership, WM frame tree and AWT crop are verified identically. It does
not capture native Wayland surfaces, invoke a portal, or infer a source from a
title or portal parent-window identifier. A future native Wayland backend needs
a separate locally authorized portal flow with source/input identity binding.

The real JDK17 fixture passes under Weston (compositor scale 1 and 2) and KWin
with Breeze decorations at scale 1. Forced Java UI scale 2 under a scale-1
compositor can give AWT and WM chrome incompatible pixel units; that case is
refused. Native aspect ratio and crop bounds are enforced, never stretched to
make a mismatched pointer contract appear valid. The Windows adapter uses WGC on Windows
10 2004+ (required for cursor suppression), and has the same pipe and parent
identity contract. Build it with CMake, MSVC, and Windows SDK 10.0.19041 or newer.
Its interactive capture, DPI, lock, and accelerated-content checks require a
Windows desktop; a Linux/macOS compile cannot establish Windows runtime support.
Configure with `-DBUILD_TESTING=ON` and run CTest with
`BOSS_TEST_WINDOWS_CAPTURE=1` in an unlocked Windows GPU session to execute the
interactive fixture. The default CI compile reports that fixture as skipped,
while malformed-invocation checks still run.

Required release evidence remains a real X11 desktop with a compositor and
logind: accelerated browser/terminal/editor content, occlusion, decorated frames,
owned dialogs, DPI, resize/reparent, unrelated windows, lock/switch/sleep, and
viewers disconnecting during a blocked write. Compilation and protocol tests do
not establish that runtime coverage.
