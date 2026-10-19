# Windows exact-window adapter

`windows_capture.cpp` implements the BSC1 helper contract documented in README.md.
It needs Windows 10 version 2004/build 19041 or newer, an active unlocked desktop
session, Windows Graphics Capture, and a D3D11 device created with the hardware
driver request. A virtual machine may fulfill that request with Microsoft Basic
Render Driver; this does not establish physical GPU acceleration. Cursor suppression
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
Verified movement or resize of a still-live, exact owned and session-authorized
HWND exits with code 75 before writing another frame. This permits only a bounded
host geometry retry at a complete frame boundary. Closed or minimized windows,
ownership/session changes, API/capture failures, and malformed or partial output
remain terminal; code 75 never authorizes resuming a stopped share.
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

A separate `owned-windows-popup` opt-in case creates a `WS_POPUP` window owned
by a normal app root, retaining `WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW` and no
`WS_EX_APPWINDOW`. It requires exact popup pixels beneath an unrelated occluder,
live GPU updates, unchanged ownership/focus style, and terminal popup close while
the owner stays alive. Manual runtime dispatch runs both cases separately; a popup
failure cannot be reported as coverage from the normal-root fixture. This is
native-style coverage, not yet an actual AWT/Compose popup integration test.
Three additional, separately reported cases use NOACTIVATE only, TOOLWINDOW
only, and neither flag to isolate eligibility. They retain the same owned
`WS_POPUP` geometry, exact pixel/update checks, and no-activation assertions;
they never substitute for the required nonfocusable utility case.

Compilation and a skipped test do not establish runtime support. Release evidence
still requires running this test and the actual BossConsole viewer on Windows:
accelerated browser/Compose/terminal/editor content, owned dialogs, mixed DPI,
resize/move/minimize/restore, session lock/unlock, secure-desktop transitions,
display/system sleep, and controller/viewer disconnect while output is blocked.
[Native CI run 37096968583](https://github.com/risa-labs-inc/BossConsole/actions/runs/37096968583)
compiled and packaged both Windows x64 and ARM64 helpers and test fixtures at
commit `b371e26c0dc4f1492ce7693f77968f40650be03c`, using MSVC with warnings as
errors. The two noninteractive tests (invalid parent and unknown mode) passed
on each architecture. `exact-windows-window` explicitly skipped because no
interactive GPU session was authorized. This proves compilation and those CLI
rejections, not capture fidelity or real BossConsole input. Windows targets use
C++20 to select supported standard coroutines in current C++/WinRT/MSVC.

## Hosted runtime evidence

Manual [run 37100783220](https://github.com/risa-labs-inc/BossConsole/actions/runs/37100783220)
at commit `60521beb8` passed the actual synthetic fixture on both hosted Windows
Server 2025 x64 and Windows 11 ARM64, in unlocked interactive session 2. These
were not skipped tests. Both captured green pixels through the red occluder and
then the blue GPU source update. The fixture also passed rate/framing bounds,
invalid source rejection, stalled reader shutdown, clean geometry exit 75, and
terminal destruction exit 72. Runtime was 1.20 s on x64 and 1.39 s on ARM64.
The adapter was Microsoft Basic Render Driver on both, so this proves functional
capture on those runners, not physical GPU performance or the actual BossConsole
app.

The selected fixture is a normal `WS_OVERLAPPEDWINDOW` root with extended
`WS_EX_APPWINDOW`, shown with `SW_SHOWNOACTIVATE`. Diagnostics confirmed it
remained out of the foreground, exact-owned, non-minimized, non-cloaked, with
display affinity zero and DWM composition enabled. The unrelated occluder
retained `WS_EX_NOACTIVATE`. No foreground activation, global input, or system
settings were needed.

Earlier x64 [run 37100547159](https://github.com/risa-labs-inc/BossConsole/actions/runs/37100547159)
rejected the fixture's `WS_EX_NOACTIVATE`, non-`WS_EX_APPWINDOW` source at
`GraphicsCaptureItem CreateForWindow` with `E_INVALIDARG (0x80070057)`, before
the renderer or first frame. ARM64 accepted that source setup. Matching the
selected synthetic source to a normal app root resolved the x64 fixture failure;
the production helper's capture path and authority checks were unchanged.
The focused owned popup [run 37101631823](https://github.com/risa-labs-inc/BossConsole/actions/runs/37101631823)
at commit `30f4abf44` then established a remaining platform difference.
Windows 11 ARM64 passed both the normal root and the owned
`WS_POPUP | WS_CLIPCHILDREN` source with
`WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW`; exact popup pixels and live updates
passed, and closing it ended only its capture. Windows Server 2025 x64 passed
the normal root but rejected that same valid, non-cloaked owned popup at
`CreateForWindow` with `E_INVALIDARG` before a frame (terminal exit 72).
Parenting alone therefore does not establish capture support for the required
nonfocusable utility style on that Server runner. The helper does not change
the window's style, focus, or owner to bypass the failure, and does not include
other windows or desktop pixels as a fallback.

The follow-up [style matrix run 37102012451](https://github.com/risa-labs-inc/BossConsole/actions/runs/37102012451)
at commit `3377855bb` retained that required case and separately varied the
two extended style flags, keeping the exact owned `WS_POPUP` constant:

| Source | Server 2025 x64 | Windows 11 ARM64 |
|---|---|---|
| Normal application root | Pass | Pass |
| Owned NOACTIVATE + TOOLWINDOW | CreateForWindow E_INVALIDARG | Pass |
| Owned NOACTIVATE only | CreateForWindow E_INVALIDARG | First frame opaque black; pixel assertion failed |
| Owned TOOLWINDOW only | CreateForWindow E_INVALIDARG | Pass |
| Owned with neither flag | CreateForWindow E_INVALIDARG | First frame opaque black; pixel assertion failed |

All Server popup variants failed before producing pixels, so removing
NOACTIVATE alone is not a demonstrated solution. The two ARM black-frame cases
needed compositor-readiness investigation in that run; they were failures, not
validated capture. These results compare different operating-system products as well as
CPU architectures, and do not demonstrate the same failure on Windows 11 x64.
Popup ownership versus WS_POPUP itself has not been isolated.
[Run 37102404790](https://github.com/risa-labs-inc/BossConsole/actions/runs/37102404790)
at commit `25a0b6e08` checked same-process `CreateForWindow` eligibility for
each exact synthetic popup before the child capture. All four Server popup
variants returned the same E_INVALIDARG in-process, ruling out the helper
process boundary as the cause for that runner. Windows 11 ARM64 item creation
succeeded; the required utility popup again passed capture, while the other
three variants returned an initial black frame, including TOOLWINDOW-only
which had passed the previous run. That isolated compositor readiness as the
next fixture question; no pixel assertion was relaxed. The same-process diagnostic
creates only a capture item, never a frame pool/session or another source.
The documented API minimum lists Windows 10 build 18362 for both client and
server, without a per-style eligibility contract. Actual Windows BossConsole,
transparent overlays, mixed DPI, owned dialogs, and session-transition testing
remain required.

The latest [runtime run 37103419502](https://github.com/risa-labs-inc/BossConsole/actions/runs/37103419502)
at commit `e497d2886` added `DwmFlush` after each synthetic swapchain present,
waiting for the fixture's queued surfaces before placing the occluder. Windows
11 ARM64 passed all five cases with zero skips: the normal root and all four
owned-popup styles. Every popup produced exact green `BGRA=0,255,0,255`, then
blue `BGRA=255,0,0,255`, retained its original ownership/focus styles, excluded
the unrelated occluder, and stopped on popup destruction. This run resolves the
observed initial-black fixture failures without retrying/discarding frames or
relaxing the pixel assertions. It is one successful runtime observation, not
a claim that every compositor timing or actual Compose popup is covered.

Windows Server 2025 x64 still passed only the normal root and rejected all four
owned popup styles at `CreateForWindow`, in both the same-process diagnostic
and the child helper, with `E_INVALIDARG`. The overall manual run therefore
failed; these popup cases were not skipped or reclassified as supported. Both
runners still used Microsoft Basic Render Driver. Windows 11 x64, real physical
GPU performance, transparent overlay capture, and the release matrix above
remain unverified. The helper's production Windows capture behavior was
unchanged by the fixture readiness fix.

Primary API references:

- [Exact HWND capture item](https://learn.microsoft.com/en-us/windows/win32/api/windows.graphics.capture.interop/nf-windows-graphics-capture-interop-igraphicscaptureiteminterop-createforwindow)
- [Free-threaded capture pool](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.direct3d11captureframepool.createfreethreaded)
- [Cursor suppression](https://learn.microsoft.com/en-us/uwp/api/windows.graphics.capture.graphicscapturesession.iscursorcaptureenabled)
- [Capture frames and content-size bounds](https://learn.microsoft.com/en-us/windows/apps/develop/media-authoring-processing/screen-capture)
- [Initial WTS session state](https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/ns-wtsapi32-wtsinfoex_level1_w)
- [Session change notifications](https://learn.microsoft.com/en-us/windows/win32/api/wtsapi32/nf-wtsapi32-wtsregistersessionnotification)
- [Suspend/resume notifications](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-registersuspendresumenotification)
- [Per-session display power events](https://learn.microsoft.com/en-us/windows/win32/power/power-setting-guids)
