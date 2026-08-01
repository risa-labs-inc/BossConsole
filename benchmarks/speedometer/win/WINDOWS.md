# Speedometer 3.1 on Windows: why the fluck browser was slow, and what fixed it

Machine: Windows 11 Pro 10.0.22631, Intel Core Ultra 7 155H (6 P-cores + 8 E-cores
+ 2 LP-E, 22 threads), 16 GB, Intel Arc integrated graphics, 1920x1200 at 150%
scaling (60 Hz), Balanced power plan, on AC.
Date: 2026-07-31.

**Result: the fix is the JxBrowser rendering mode, not Chromium flags.** Six
Chromium switch sets were measured and none helped. Switching Windows from
`RenderingMode.OFF_SCREEN` to `HARDWARE_ACCELERATED` closes almost the whole gap to
Edge.

## Headline

Final measurement, all four arms on a quiet machine, same session, same build:

| Arm | Runs | Median |
|---|---|---:|
| Microsoft Edge 150.0.4078.105 | — | **24.7** |
| fluck, HARDWARE_ACCELERATED (new Windows default) | 23.8 / 22.7 | **23.8** |
| fluck, OFF_SCREEN (previous behaviour) | 17.9 / 17.1 | **17.9** |

**+33%**, and fluck lands at **96% of Edge** on the same Chromium generation.

Two sanity checks on that number. The OFF_SCREEN control of 17.9 reproduces the
operator's own independently-reported 18.1 almost exactly, which calibrates the whole
harness. And an earlier, more tightly controlled interleaved experiment (alternating
modes, 3 pairs) gave the same direction at **+47% median, 3 of 3 pairs**:

| Pair | OFF_SCREEN | HARDWARE_ACCELERATED | Gain |
|---|---:|---:|---:|
| 1 | 11.0 | 14.3 | +30% |
| 2 | 10.8 | 15.9 | +47% |
| 3 | 9.56 | 15.3 | +60% |

Those absolute values are roughly half the final ones because that experiment ran
while this machine was also building and benchmarking continuously. **Absolute scores
on this machine move by ~2x with ambient load, so only same-session comparisons mean
anything here** — which is exactly why the modes were interleaved.

## The starting gap

| Browser | Engine | Score | Viewport |
|---|---|---:|---|
| Microsoft Edge 150.0.4078.105 | Chromium 150 | **24.7** | 1258x726 @1.5 |
| BOSS fluck tab, OFF_SCREEN | Chromium 150.0.7871.47 | **17.9** quiet / 7.5-11.5 loaded | 1202x602 @1.5 |

Same Chromium generation, so the gap is not the engine version. Edge ran on a
throwaway profile; the machine was quiet (3-5% ambient during the Edge run).

The diagnosis below was carried out during the loaded period, so its absolute numbers
are the depressed ones. The ratios and the process-level evidence are unaffected.

## What it is NOT

Each of these was killed by direct measurement, not by reasoning about it. This
matters: every one of them is a plausible-sounding cause that would have led to a
flag change that did nothing.

| Hypothesis | How it was refuted |
|---|---|
| Software-rasterizer fallback | `SystemInfo.getInfo`: `ANGLE (Intel Arc ... Direct3D11)`, `gpu_compositing: enabled`, `rasterization: enabled`, `skiaBackendType: GaneshGL` |
| Renderer backgrounded / EcoQoS demotion to E-cores | Read the live Win32 process state: the active renderer is `Normal` priority, EcoQoS `default`. Only an idle background tab was `Idle` + ECO, which is correct |
| Windows timer resolution (15.6ms vs 1ms) | `setTimeout(0)` median **4.16 ms in both** fluck and Edge |
| Frame clock throttled | rAF median **16.65 ms in both** (a clean 60 Hz) |
| Slow JIT / low CPU clocks | Tight arithmetic loop: 44.5 ms vs Edge 38.0 ms — only **1.17x** |
| DEBUG logging / app startup burst | Pinning `BOSS_LOG_LEVEL=INFO` and adding a 60s settle changed nothing (7.87 -> 7.51) |

## The signature that pointed at the frame path

Per-metric comparison of a fluck run against an Edge run (`compare-suites.ps1`):

| Metric | fluck | Edge | Ratio |
|---|---:|---:|---:|
| Sync total | 2086 ms | 695 ms | 3.00x |
| Async total | 1405 ms | 292 ms | 4.82x |
| median per-test (206 metrics) | | | **3.13x** |

Two things stand out. The slowdown is **uniform** — no suite carries it, unlike the
Chrome/Comet DOM-mutation signature in [`../../benchmark.md`](../../benchmark.md).
And it coexists with near-parity on raw arithmetic. Work that penalizes DOM and
layout but barely touches a register-bound loop is per-frame overhead, not slower
script execution.

CPU attribution during a run (`measure-cpu-split.ps1`) confirmed it:

```
chromium:renderer        14.5s CPU   0.26 cores
chromium:gpu-process      9.8s CPU   0.18 cores
BOSS host (JVM/Compose)   7.5s CPU   0.14 cores
chromium:browser          3.5s CPU   0.06 cores
total 36.1s over 55.7s wall
```

Speedometer is CPU-bound single-threaded work, so a healthy renderer sits near a
full core. **This one used 0.26 of a core** while the whole machine sat at 0.65 —
nothing was starved, the renderer was *blocked waiting for frames*. Note also that
the JVM side is cheap (0.14 cores): the cost is inside Chromium's own off-screen
pipeline, not in the Compose integration.

## Chromium flags: six arms, zero wins

Each arm is one `BOSS_CHROMIUM_EXTRA_SWITCHES` value, fresh app per run, identical
forced single-browser-tab layout. Short runs (`iterationCount=5`) for triage.

| Arm | Score |
|---|---:|
| control | 11.5 |
| `--use-angle=gl` | 11.5 |
| `--disable-gpu-compositing` | 11.0 |
| `--disable-gpu` | 11.0 |
| `--disable-gpu-vsync --disable-frame-rate-limit` | 9.56 |
| `--force-device-scale-factor=1` | **4.85** |
| control (re-run alongside the last arm) | 10.2 |

`--force-device-scale-factor=1` is the interesting failure: it renders *fewer*
physical pixels (1201x602 at dpr 1 instead of dpr 1.5) and is **half the speed**,
which suggests forcing the browser scale out of step with the 150% Compose window
scale adds a per-frame resample.

## The fix: rendering mode

Interleaved pairs (alternating modes, never grouped — see caveat 4 in
[`../../benchmark.md`](../../benchmark.md) for why grouping is untrustworthy here):

| Pair | OFF_SCREEN | HARDWARE_ACCELERATED | Gain |
|---|---:|---:|---:|
| 1 | 11.0 | 14.3 | +30% |
| 2 | 10.8 | 15.9 | +47% |
| 3 | 9.56 | 15.3 | +60% |
| **median** | | | **+47%** |

Won 3 of 3. This is why macOS is unaffected and must not be changed: fluck measures
47.9 there, *ahead* of Chrome, because the off-screen surface can be shared with the
GPU. Windows/D3D11 needs a real per-frame readback instead.

`JxBrowserConfig` now defaults Windows to `HARDWARE_ACCELERATED`; macOS and Linux
keep `OFF_SCREEN` (Linux is unmeasured and does not inherit a Windows finding).

### The overlay consequence, and the fixes ported from BossConsoleLite

`HARDWARE_ACCELERATED` renders into a native surface that composites *against* the
Compose scene rather than inside it, so lightweight Compose overlays draw **under**
browser content. This is real, and it is not theoretical: `BossConsoleLite`
(`risa-labs-inc/BossConsoleLite`) defaulted to HARDWARE first and its Windows fleet
hit three regressions, fixed in commit `539fbb48`. All three are ported here:

| Regression | Fix |
|---|---|
| Browser surface sits ~toolbar-height too high — overlaps the URL bar, gap at the bottom | `BrowserHandleImpl` offsets the surface down (`offset`, not `padding`, which shrank it). `BOSS_BROWSER_TOP_INSET_DP`, HARDWARE-only. **Default 0 here, not Lite's 24** — see below |
| Ctrl/Cmd+R does nothing when focus is inside the page | `FluckEngine` reloads the focused browser directly from the key callback instead of routing through the focused *window* (which is unset when the native surface holds focus) |
| Hover tooltips render behind the browser | `SwingTooltip`: a tiny non-focusable `JWindow` clamped to the cursor's monitor, wired through `OverlayConfig.heavyweightTooltip` |

Plus `OverlayConfig` + `HeavyweightPopup` / `HeavyweightModal`, which route context
menus and modals into heavyweight windows. Every one of these is gated on
`OverlayConfig.useHeavyweightPopups`, which is false on OFF_SCREEN — so macOS and
Linux take the unchanged Compose path.

**The top inset does not transfer, and was re-measured rather than copied.** Loading
a marker page (`inset-test.html`, a red band at the very top of the document) into a
HARDWARE-mode browser tab on this machine:

| `BOSS_BROWSER_TOP_INSET_DP` | Result |
|---|---|
| 0 | Red top edge sits flush under the URL bar — **correct** |
| 24 (Lite's default) | ~24dp dark gap above the page; bottom edge pushed off-screen |

So BossConsole defaults it to **0**. Lite is a browser-only build with different
chrome heights, so the amount that corrects its layout over-corrects this one — the
inset belongs to the install, not to the platform. Anyone who does see the overlap
can set the env var. Copying Lite's constant unexamined would have shipped a visible
gap on this machine.

**Still moving:** Lite marks its own `HeavyweightPopup` and `HeavyweightModal` as
DRAFT, with known gaps — positioning is cursor-based (right for context menus, wrong
for widget-anchored dropdowns), the popup window is a fixed 320x480 that leaves a
transparent click-capturing margin, and HiDPI px→dp mapping is unverified. Expect
overlay polish to continue.

### What was verified here, and what was not

Honest status per fix, on this machine, with HARDWARE as the default:

| Item | Status |
|---|---|
| Page renders correctly in HARDWARE | **Verified** — screenshot, real page |
| Surface alignment | **Verified and corrected** — marker page; inset 0 is right here, 24 is wrong (see above) |
| Tooltips layer above the browser | **Verified** — the sidebar hover tooltip draws over page content as a native window |
| Keyboard input still reaches the page | **Verified** — `keydown` for `hello` observed by page JS; HARDWARE does not suppress input |
| Ctrl+R reload | **Not verified.** Synthetic `SendKeys ^r` fails to reload in **both** HARDWARE and OFF_SCREEN on this machine, so the harness cannot exercise it. Identical in both modes, so the port is not a regression; the fix (reload the focused browser directly rather than routing through the focused window) is a strict logic improvement but is unproven here |
| Context menus / modals over the browser | **Not verified.** Compose dropdowns could not be driven with synthetic clicks. Needs a human |

If anything still regresses: `BOSS_RENDERING_MODE=OFF_SCREEN` restores the old
behaviour with no rebuild.

### Lite's independent corroboration

Lite reached the same conclusion by a different measurement — power and memory rather
than throughput. Content-matched A/B, same page, clean 94-sample idle windows
(commit `6e637198`): idle CPU **0.59 → 0.06 cores (~10x)**, RSS **3095 → 1974 MB
(-1.1 GB, -36%)**, peak CPU -14%, peak RSS -25%. Two unrelated methods, same answer.

Lite defaults HARDWARE on **every** platform; BossConsole defaults it on Windows only,
because macOS measures faster than Chrome on OFF_SCREEN here and Lite notes HARDWARE
costs macOS the two-finger swipe-back gesture.

## Reproducing

The macOS harness (`../run-all.sh`) shells to `osascript`/`pgrep` and exits on other
platforms. This directory is the Windows counterpart. It needs **only a JDK** —
single-file Java plus `java.net.http`'s WebSocket client, because this machine had no
Node, Python, or Deno.

```powershell
cd benchmarks\speedometer\win
javac -d out Json.java SpeedometerCdp.java

# Reference browser (launches it, throwaway profile)
java -cp out SpeedometerCdp --name Edge `
  --binary "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" `
  --port 9333 --iterations 10 --out results-win\edge-1.json

# The fluck tab (attaches to a running BOSS)
.\run-boss-arm.ps1 -Label baseline -Repeats 3
.\run-paired-rendering.ps1 -Pairs 3          # OFF_SCREEN vs HARDWARE_ACCELERATED
.\screen-arms.ps1                            # triage a set of Chromium switches
```

| Script | Role |
|---|---|
| `SpeedometerCdp.java` | Runs the benchmark over CDP. `--binary` launches a browser, `--attach` drives an already-running one. Also `--info <port>` (GPU/feature status) and `--probe-timing <port>` (JS speed vs rAF cadence vs `setTimeout(0)`) |
| `Json.java` | Minimal JSON reader/writer, so the harness needs no dependency |
| `run-boss-arm.ps1` | One Chromium-switch arm against the fluck tab, N repeats, fresh app each time |
| `run-paired-rendering.ps1` | Interleaved OFF_SCREEN vs HARDWARE_ACCELERATED |
| `screen-arms.ps1` | Fast single-run triage of several switch sets |
| `compare-suites.ps1` | Per-metric Sync/Async diff between two result files |
| `measure-cpu-split.ps1` | Attributes CPU-seconds to the JVM host vs each Chromium process |
| `probe-process-qos.ps1` | Per-process priority and EcoQoS state (run during a benchmark) |
| `restore-last-session.ps1` | Puts the operator's workspace layout back |
| `inset-test.html` | Marker page (red band at the document's top edge) for measuring browser-surface alignment against the chrome above it |
| `keyinput-test.html` | Records `keydown` into `window.__keys`, for checking that keyboard input still reaches the page |

`SpeedometerCdp --eval <port> --expr "<js>"` evaluates one expression in the first
page target — used to mark a page, drive real input from outside the app, then check
what the page saw.

### Things that will bite you

- **Driving the fluck tab needs `BOSS_BROWSER_REMOTE_DEBUGGING_PORT`.** It is off
  unless set: an open DevTools port is full control of the browser profile (cookies,
  session tokens) for any local process, with no prompt. Unset it when done.
- **Workspaces are shared between dev mode and the real install**
  (`~/Documents/BOSS/workspaces`), so a `BOSS_DEV_MODE=true` run restores — and
  overwrites — the operator's "Last Session". `run-boss-arm.ps1` backs it up once and
  forces a known single-browser-tab layout; `restore-last-session.ps1` undoes it.
- **`BOSS_DEV_MODE=true` alone forces logging to DEBUG**
  (`BossLogger.configureFromEnvironment`), which measures the app doing work a
  production run never does. Pin `BOSS_LOG_LEVEL=INFO`.
- **Measure a settled app.** Benchmarking straight after launch measures BOSS's
  plugin/service startup burst.
- **The layout decides the viewport.** A restored terminal pane beside the browser
  both halves the viewport and repaints continuously next to it.
- **The fluck viewport here is 1202x602, under Speedometer's 850x650 minimum on
  height** (BOSS chrome plus a 1280x800 logical screen). Speedometer still reports
  `valid: true`, but these absolute numbers should not be compared to published
  browserbench figures — only to each other.
- **Run-to-run spread on one fixed configuration was 7.5 to 11.5.** Single runs are
  triage only; anything believed should be an interleaved pair or a 3-run median.
- Dev-mode BOSS needs a Chromium build. A directory junction from
  `~/.boss_debug/boss-chromium` to `~/.boss/boss-chromium` avoids a second download.
- A killed instance leaves `~/.boss_debug/run/single-instance` behind; the next
  launch forwards to the dead process and exits. The scripts clear it.

## Not established

- **Why** the off-screen path costs this much on Windows specifically. The evidence
  narrows it to per-frame frame delivery (renderer blocked at 0.26 cores, uniform
  slowdown, GPU process burning 0.18 cores) and rules out Chromium configuration, but
  the exact mechanism inside JxBrowser's OSR transport was not isolated. Do not quote
  a cause from this document beyond that.
- **Whether these absolute numbers match a normal install.** Every fluck figure here
  came from an isolated `BOSS_DEV_MODE` instance that baselined at ~7.5 where the
  operator's own install reported 18.1 on the same benchmark. The *ratios* between
  arms are the usable signal; the absolute scores are not the operator's.
- **Linux.** Unmeasured, and left on `OFF_SCREEN` for that reason.
