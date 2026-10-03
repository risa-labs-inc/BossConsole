# Windows exact-window adapter

`windows_capture.cpp` implements the BSC1 helper contract documented in README.md.
It needs Windows 10 version 2004/build 19041 or newer, an active unlocked desktop
session, Windows Graphics Capture, and a D3D11 hardware device. Cursor suppression
is required, so the older 1903 `CreateForWindow` minimum is insufficient here.

The selected HWND must be a visible top-level window belonging to the helper's
actual live parent process. WGC binds directly to that HWND. The helper never
captures a monitor, calls PrintWindow, or searches for a title. A free-threaded
two-buffer frame pool feeds GPU scaling into the requested bounded BGRA output;
only the scaled output is read back to CPU memory. Physical HWND bounds and DWM
visible-frame bounds bind the capture pixels to input coordinates. When WGC
omits invisible resize margins, those margins are black-padded instead of
stretching the visible content over a different input coordinate system. Requested
output and native outer-window aspect ratios must agree within one output pixel
of rounding; incompatible AWT/native DPI geometry fails closed.

Resize, movement, destruction, minimization, ownership changes, or unknown frame
geometry end the helper. Kotlin may create a new exact stream after validating
its authorized geometry, or pause a minimized root until explicit restoration.
WTS lock/disconnect/logoff, secure-desktop changes, suspend/resume, and display-off
notifications end the helper permanently. Session state is checked at startup
and before frames. An independent watchdog bounds a stalled anonymous-pipe write
to 250 ms and stops if the parent process exits. The input pipe accepts only
four-byte big-endian 30/60 FPS updates; EOF and malformed/oversized queues stop it.

This is a GPU capture/downscale path with CPU readback and loopback transport;
it is not zero-copy native hardware video encoding. Output is SDR BGRA. HDR
tone-mapping fidelity has not been established.

Build with Visual Studio 2022 C++ tools and a Windows SDK >= 10.0.19041:

```powershell
cmake -S native/app-capture -B native/app-capture/build -A x64
cmake --build native/app-capture/build --config Release --parallel
ctest --test-dir native/app-capture/build -C Release --output-on-failure
```

The interactive integration test returns an explicit skip unless enabled. On
an unlocked Windows GPU desktop, run:

```powershell
$env:BOSS_TEST_WINDOWS_CAPTURE = '1'
ctest --test-dir native/app-capture/build -C Release -R exact-windows-window --output-on-failure
```

It creates only synthetic test windows. An unrelated red GPU window covers the
selected green GPU window; captured pixels must remain green, then reflect a
blue source update. It checks bounded framing, rate updates, rejection of a
desktop drawable, wrong parent PID, and mismatched aspect ratio before any pixels,
termination on a stalled reader, and
selected-window destruction. This test does not lock the user's desktop.

Compilation and a skipped test do not establish runtime support. Release evidence
still requires running this test and the actual BossConsole viewer on Windows:
accelerated browser/Compose/terminal/editor content, owned dialogs, mixed DPI,
resize/move/minimize/restore, session lock/unlock, secure-desktop transitions,
display/system sleep, and controller/viewer disconnect while output is blocked.
No Windows compilation or interactive test was available on the macOS development
machine when this adapter was introduced.

Primary API references:

- [Exact HWND capture item](https://learn.microsoft.com/en-us/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow)
- [Free-threaded capture pool](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframepool.createfreethreaded)
- [Cursor suppression](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.graphicscapturesession.iscursorcaptureenabled)
- [Capture frames and content-size bounds](https://learn.microsoft.com/en-us/windows/apps/develop/media-authoring-processing/screen-capture)
- [Initial WTS session state](https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/ns-wtsapi32-wtsinfoex_level1_w)
- [Session change notifications](https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/nf-wtsapi32-wtsregistersessionnotification)
- [Suspend/resume notifications](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registersuspendresumenotification)
- [Per-session display power events](https://learn.microsoft.com/en-us/windows/win32/power/power-setting-guids)
