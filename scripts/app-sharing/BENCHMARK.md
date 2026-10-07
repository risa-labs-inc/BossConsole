# Sharing performance measurements

## Portable encrypted browser pipeline benchmark

From the repository root, with Node 20 or newer:

```sh
node --test scripts/app-sharing/benchmark-metrics.test.mjs
node scripts/app-sharing/benchmark-server.mjs /tmp/boss-sharing-benchmark-reports
```

On Windows, use an output directory such as `C:\Temp\boss-sharing-benchmark-reports`.
Open the printed **loopback** URL in the browser being measured. The existing
BossConsole browser measures its actual shipped Chromium, without building or
restarting the application. The harness loads production modules directly from
this checkout; record the checkout revision and dirty changes alongside results.
Choose BGRA/NV12, 30/60 FPS, and one/three local peers, then Run benchmark. Keep the
page visible until complete. Stop closes the peers and source; closing the page
also releases them. Stop the Node server with Ctrl+C when finished.

For automation through Boss MCP `browser_run_js`, start without awaiting the
long-running promise and poll `window.benchmarkReport` / `window.benchmarkError`:

```js
window.runBenchmark({format: 'NV12', fps: 60, viewers: 1,
  width: 1280, height: 720, warmupSeconds: 20, seconds: 10,
  contentHint: 'motion', markerEvery: 1});
JSON.stringify({started: true, visible: !document.hidden});
```

`contentHint: 'detail'` is available for controlled comparison; the production
default is not changed. `markerEvery` accepts `1` (read every callback), `5`, `10`,
`30`, or `0` (compositor counters only, no pixel readback/latency/PSNR). Compare
`1`, `10`, and `0` to reveal observer overhead. The native fixture exposes the
same choice through `BOSS_SHARING_BENCHMARK_MARKER_EVERY`, default `1`. Each default run takes about 51 seconds plus negotiation:
20 seconds warmup, 10 seconds normal operation, 10 seconds with the sender capped
at 300 kbps, one second with source production paused, and 10 seconds recovery.
The 300 kbps phase is **an encoder budget constraint, not simulated packet loss**.
The resumed frame ID measures fresh-frame recovery rather than counting a stale
frame still visible during the pause as recovered.

Compare at least three runs per case on the same foreground display, with the
same workload, browser version, resolution, and background load. Compare the
decoded widths as well as FPS: a fast 320-pixel frame is not equivalent to a fast
1280-pixel frame. Twenty seconds of warmup reduces bandwidth-estimator startup
bias but does not promise convergence; the result retains actual resolutions.

```sh
node scripts/app-sharing/benchmark-report.mjs /tmp/boss-sharing-benchmark-reports/*.json
```

PowerShell users can pass an expanded list from `Get-ChildItem` instead of the
shell glob. The summary reports whether presented FPS reaches 80% of the target
as an observation, not a universal performance acceptance threshold.

## What is measured

The path is a deterministic synthetic full-frame text/scroll/motion workload →
canvas readback → BGRA or BT.709 limited-range NV12 → the **production**
`RawVideoFrameTrack` → the **production** authenticated/encrypted VP8 transform →
real loopback WebRTC RTP → the production receiver decryptor → actual video
presentation callbacks. Fresh cryptographic keys remain in memory and are never
included in reports. No user pixels, account, ICE addresses, SDP, session keys,
or external relay are used. Only explicit aggregate fields are emitted.

An embedded frame sequence with a checksum is decoded from presented pixels.
This supplies sampled source-to-presentation latency without treating
`framesDecoded` as equivalent to frames shown to the user. Schema
`boss-sharing-benchmark/2` measures compositor FPS with deltas of
`requestVideoFrameCallback`'s `metadata.presentedFrames`, independently of pixel
marker observations. JavaScript callbacks may be skipped while the compositor
continues displaying frames; missed callbacks and callback lateness are reported.
The old `/1` reports called observed marker callbacks `presentedFrames/FPS` and
can undercount actual composition. The summary labels that difference; do not
compare old marker FPS directly with new compositor FPS.
The latency starts before synthetic drawing and ends at
`requestVideoFrameCallback.expectedDisplayTime`, on one browser monotonic clock.
It is **not** a photodiode display latency or physical scan-out measurement.
Callbacks over one target frame late are excluded from marker latency because
the current video pixels may have advanced beyond that callback's metadata.
The remaining latency is still a sampled browser estimate. Unknown/corrupt
markers are counted, and excessive failures invalidate a run. Counter-only mode
intentionally leaves latency and quality unavailable. See the
[video callback specification](https://wicg.github.io/video-rvfc/) for compositor
counter and callback scheduling semantics.

Reports include p50/p95/max and sample counts for source preparation, VideoFrame
upload, latency, presentation intervals, and observer cost; average encode,
decode and jitter-buffer time from RTC counter deltas; actual decoded dimensions;
generated/compositor-presented/marker-observed/receiver-dropped frames; and static-text-region luma PSNR.
PSNR measures this rendered synthetic text region after scaling back to source
dimensions; it is not OCR accuracy or a claim about all UI legibility.
Synthetic JavaScript NV12 conversion is included in preparation and separated
from upload; its CPU cost does **not** estimate native SCStream conversion cost.

At the default density, the marker sampler reads a cropped 480×1 strip per
eligible callback and text about once per 30 observed frames. Its cost is reported separately. Hidden-tab runs,
missing authenticated transform frames, insufficient fresh frames, and excessive
marker failures are explicitly marked invalid. Hardware/display/OS scheduling
and other running apps still affect valid observations.

## Coverage boundaries and next gates

This harness runs on Windows, Linux and macOS where the browser exposes encoded
transforms and the production direct VideoFrame API. A missing API produces a
visible unsupported result. An executable on those platforms is **not evidence
that each platform has been tested**.

It does not exercise OS window acquisition, native capture consent, native popup
composition, the raw localhost HTTP bridge, remote input, traffic-light buttons,
Cloudflare SFU, WAN jitter/loss, or controller takeover. Validate those separately
with native capture/input smoke tests and a real shared window. The existing
`AppContinuousCaptureSmokeTest` measures actual native capture rate/ownership;
`AppSharingMediaSmokeTest` tests encrypted decode and tamper/replay rejection.
Neither by itself proves native-capture-to-presentation latency.

Two or three peers in this harness mean **independent local publishing encoders**
sharing one source. This is a CPU stress comparison, not the production
single-publisher/SFU fanout topology. Capacity claims require one real publisher
and multiple SFU subscribers under measured network conditions. Never multiply a
loopback FPS figure into a claimed user/session capacity.

## Native capture-to-presentation fixture (opt in)

`AppSharingNativeBenchmarkTest` adds a controlled, owned, undecorated
ComposeWindow containing only a synthetic frame pattern. It connects the
production `AppContinuousWindowCapture` and latest-only mailbox to the same
production encrypted loopback/presentation harness through a **fixture** HTTP
server. The HTTP route is not `AppSharingAssets`, so that boundary still requires
its own integration checks. This test does not capture any preexisting window.

Stop the debug JVM before rebuilding its jar. With existing matching Chromium
binaries and the normal `local.properties` license configuration:

```sh
BOSS_TEST_APP_SHARING_BENCHMARK=1 \
BOSS_TEST_APP_MEDIA_CHROMIUM_DIR=/path/to/existing/boss-chromium \
BOSS_SHARING_BENCHMARK_FORMAT=NV12 \
BOSS_SHARING_BENCHMARK_FPS=60 \
BOSS_SHARING_BENCHMARK_OUTPUT=/tmp/boss-native-benchmark \
./gradlew :composeApp:desktopTest --tests '*AppSharingNativeBenchmarkTest' \
  --no-watch-fs --no-configuration-cache
```

Use `BGRA` to compare the fallback and `30` for a rate comparison. In PowerShell,
set the same values with `$env:NAME = 'value'` and invoke `gradlew.bat`. Linux
requires the supported display session and a visible display; Windows requires
an interactive desktop; macOS requires capture permission. An unsupported native
capture capability fails this explicit opt-in test rather than silently skipping
and producing a misleading green performance result.

### Choose the native source renderer

`BOSS_SHARING_BENCHMARK_SOURCE_RENDERER=compose` selects a direct Compose Canvas
workload driven by `withFrameNanos`, with cached text layouts and the same
logical marker/text/motion geometry. This removes the synthetic Swing Timer and
SwingPanel bridge from the source side. The default `swing` retains earlier
baselines for comparison. Reports and external-viewer options record
`scenario.sourceRenderer`; do not pool the two renderer workloads as identical
measurements. Both retain actual paint counters and native monotonic source
stamps. Runtime results, rather than the requested rate, determine whether a
source renderer sustained the target frame rate.

### Compare polling with bounded next-frame delivery

Set `BOSS_SHARING_BENCHMARK_WAIT_FRAME=1` to prototype next-frame delivery in the
fixture. The default remains polling. The native manifest includes
`waitForFrame: true`; the client requests it explicitly, and the server
acknowledges acceptance on each response. Only acknowledged responses remove the
extra client frame-period delay. Unsupported/unacknowledged servers retain
polling; reports record requested and effective `frameDelivery` (`polling`,
`wait`, or `mixed`) with acknowledgement/fallback counts.

The fixture allows one active waiter among its three HTTP threads and waits no
longer than 250 ms. Empty responses carry the mailbox sequence, which the client
advances before its next wait; stale empty reads therefore do not create a busy
loop. Closing the fixture retires the mailbox and wakes its waiter. The benchmark still uses its fixture transport. Production `AppSharingAssets`
now also negotiates bounded waiting with a separate delivery pool and deadline.
A real Chromium smoke covers that production endpoint, both pixel formats, encrypted
decode, clearing and retirement; the performance numbers here remain fixture measurements.
Native `preparationMs` includes time intentionally spent waiting for the next
frame in this mode; use paint-to-capture/HTTP ages and total presentation latency
when comparing transport delay, rather than interpreting all fetch time as copy
cost.

### Separate the viewer from the capture JVM

The default native fixture places the source SwingPanel and receiver BrowserView
in the same JVM, so both use the same AWT event dispatch thread. To measure
without that shared-EDT contention, add:

```sh
BOSS_SHARING_BENCHMARK_EXTERNAL_VIEWER=1
BOSS_SHARING_BENCHMARK_OUTPUT=/tmp/boss-native-benchmark
```

Export those values or prefix them to the same Gradle invocation. This mode
creates **no browser engine or receiver window in the test JVM**. It writes
`external-viewer-<test-process>.json` in the output directory with only the
synthetic localhost `url` and bounded benchmark `options`. The file has POSIX
mode `0600` (or an owner-only ACL on a non-POSIX filesystem). Open that URL in the
designated Boss release benchmark tab, then start through Boss MCP:

```js
window.runBenchmark(/* the manifest's options object */);
JSON.stringify({started: true, visible: !document.hidden});
```

Do not navigate an existing user sharing session. The fixture waits up to 180
seconds for its aggregate result, then closes capture/server; the manifest is
deleted on success, failure or timeout. Reports retain
`scenario.viewerPlacement` as `embedded-shared-jvm` or `external-browser`;
standalone browser runs use `browser-only`. The label describes the selected test
arrangement, not a detected OS process identity. Keep browser version, content,
format, frame rate and marker density equal when comparing. This changes viewer
placement only: it still uses local encrypted loopback peers rather than an SFU.

The fixture records the native paint timestamp for each source marker. Twelve
same-origin clock requests map native `System.nanoTime` to browser
`performance.now` using the midpoint of the lowest RTT; the report includes its
half-RTT uncertainty. This is an estimated clock mapping, not a zero-error timing
claim. Source IDs that predate a new phase are excluded. Native phase
`reader-pause` stops raw ingestion while painting/capture continue, then verifies
fresh-frame recovery through the latest-only mailbox. `sourceFps` in native mode
means unique source frames delivered through the bridge; it is not an independent
count of all paints or all native capture callbacks.

Each native phase now also includes `nativeStages`: independent synthetic paint,
capture-delivery, unique captured-source, mailbox-read, and HTTP-response rates;
200/204 counts and raw bytes served; mean paint-to-capture and paint-to-HTTP ages;
JVM GC collection count/time deltas; and heap used/committed/max snapshots. Native
rates use the native snapshot interval, not the browser clock mapping. These are
scalar aggregates: source IDs and raw frames are not persisted. Capture delivery
means `AppContinuousWindowCapture` output, not every internal SCStream callback.
GC collection time is the JVM collector's cumulative collection-time delta, not
an assertion that every reported millisecond was a stop-the-world pause. Chromium
child-process memory/GC is not included. Snapshots are close in time rather than
atomic across counters.

Compare paint → capture delivery → unique captured source → HTTP 200 → browser
source → encode → compositor counts to locate the first significant loss. A high
204 count distinguishes repeated polling of the same mailbox frame from slow
WebRTC; a gap between HTTP 200 and unique browser source can also mean repeated
source pixels. GC deltas contextualize allocation pressure but do not establish
causality without a controlled heap/format comparison.

Native `preparationMs` is fixture HTTP fetch and copy time. Quality PSNR is left
unavailable because native Java2D and browser canvas text rasterization differ;
pretending those renderers were an identical reference would give a misleading
quality score. Native decoded dimensions can exceed the logical 1280×720 workload
on HiDPI displays and are retained in the report. The undecorated synthetic
window does not test traffic lights or remote input.

### Real production transport mode

Set `BOSS_SHARING_BENCHMARK_TRANSPORT=production-assets-native-client` to run
native capture through the real `AppSharingAssets` mailbox/HTTP endpoint and
`NativeRawFrameCanvas`, then the existing authenticated VP8 peers and receiver.
The omitted default is `fixture-http`, preserving previous comparisons.
Production mode always requests negotiated next-frame waits. No production
authorization, CSP or module changes are needed: the benchmark scripts are
available through a desktop-test-only resource root.

For an external browser, open the manifest's `url`, execute its `bootstrap`
string, wait until `window.runBenchmark` exists, then invoke it with `options`.
Production mode opens the harmless protected `viewer.css` resource and imports
the benchmark module from that same origin; it does not start host SFU workflow.
The bootstrap is empty in fixture mode. The owner-only temporary manifest
contains a synthetic page capability; never copy it into a report or issue.

Clock, capture/paint/GC counters, bounded batched timestamp lookup and aggregate
result submission use the real authenticated RPC route with test-only handlers.
The browser observes source markers before the production client's paint call,
then resolves their native paint timestamps after each phase. Raw IDs and
capabilities remain in memory; reports contain only aggregates. Missing timestamp
lookups invalidate a run. The timestamp ring holds 4800 paints and each request
is limited to 2400 distinct IDs. Marker-free runs skip raw marker decoding too.

Reports explicitly distinguish the transport and `coverage.nativeHttpBridge`.
`clientTransport` counts actual response headers observed by the client;
native HTTP-write/mailbox-read counters are unavailable in this mode and remain
null. `preparationMs` includes the actual client wait/fetch/body copy;
`rawUploadMs` includes the small benchmark observer plus the awaited production
paint. No frames are suppressed or deduplicated before production paint.
Consequently source FPS includes repeated captured content, unlike fixture mode's
unique-marker filtering. Compare `nativeStages.uniqueCapturedPaintFps` alongside
source/encoded/compositor FPS. The deliberate reader-pause gates the next client
fetch; an already in-flight frame may still arrive.

Both modes now record `getVideoPlaybackQuality()` total/dropped frame deltas and
their difference as displayed frames, independently of RTP decode and compositor
callback counts. Unsupported/reset counters remain null. These low-cost phase
snapshots help identify display-deadline losses without GPU pixel readback.
See the [playback-quality specification](https://w3c.github.io/media-playback-quality/).
The 5% marker-failure gate applies to active phases. During the intentional reader
pause, an in-flight frame can arrive without a new source record; its marker
counters remain reported but do not invalidate the run. Counter-reset and native
timestamp-integrity checks still apply to every phase. Previously saved invalid
reports remain unchanged and require a fresh run after a measurement correction.
The production mode still uses local encrypted peers, not SFU/network impairment,
and its benchmark observer means it is an instrumented production path.

### Receiver layout validation

Both fixture and production modes now load the same `benchmark.css`, hide the
unused source canvas, and size receiver videos in a responsive grid. The module
waits for the stylesheet before exposing `runBenchmark`. Reports label this
`scenario.layout=shared-css-hidden-source-v1` and record each video's viewport
intersection fraction and dimensions before/after every phase. A single-viewer
active phase requires at least 95% intersection; multi-viewer runs retain the
measurements without a blanket visibility gate. Intersection does not detect
another application covering the browser or prove actual compositor visibility.

### Observed production-path run before layout normalization

`native-1791008459627-NV12-60.json` is a fresh report with `valid=true` under its
then-current measurement rules. It used real Assets/native-client transport,
external-browser Compose NV12/60, every-callback markers, default jitter and one
local encrypted peer. All 2892 observed responses acknowledged waits, with no
polling fallback. No active phase had missing native timestamps.

| Phase | Decoded dimensions | Source / presented FPS | Latency p50 / p95 ms | Playback total / dropped / displayed |
|---|---|---:|---:|---:|
| Steady | 1920×1080 | 57.32 / 53.54 | 72.99 / 83.24 | 571 / 33 / 538 |
| Sender cap | 1920×1080 → 1280×720 → 960×540 | 57.24 / 30.42 | 73.90 / 83.02 | 311 / 5 / 306 |
| Recovery | 960×540 | 57.38 / 53.19 | 66.76 / 76.19 | 558 / 26 / 532 |

Steady paint/capture rates were 60.00/57.41 FPS, with mean paint age at capture
28.50 ms. Source upload p95 was 0.20 ms, mean encode/decode 2.68/1.66 ms, and mean
actual jitter buffering 12.55 ms. The 26.70 ms fetch-preparation p95 includes
waiting for a fresh frame; it is not an HTTP overhead measurement. JVM GC
reported 96 collections and 67 ms aggregate collection time during the steady
phase, which does not establish individual pause durations.

The 33 steady playback drops exactly explain decoded 571 versus presented 538;
this is display-pipeline loss, rather than merely missed JavaScript callbacks.
Marker readback cost was 4.30 ms median/6.50 ms p95 and may perturb presentation.
Recovery remained half-width/half-height with sender limitation still reported
as bandwidth, so it did not demonstrate full-resolution recovery.

**This is descriptive evidence, not the canonical production baseline.** The
production bootstrap had not loaded the fixture's sizing styles, and its unused
1280×720 canvas could push the video below the viewport. The artifact has no
viewport measurements. The normalized-layout run must be repeated before causal
comparisons. Sender and receiver also share one browser page here, unlike a
separate host/viewer deployment; SFU, network impairment and native text PSNR are
not covered. Reported latency has 0.45 ms clock-mapping uncertainty.

The preceding `native-1791007969762-NV12-60.json` remains **invalid** because its
old marker gate counted one unmatched in-flight marker during the deliberate
reader pause. It has not been rewritten or promoted to valid. Both temporary
artifacts remain in `/tmp/boss-native-benchmark`.

### First production-path run with normalized layout

`native-1791008896700-NV12-60.json` is the first valid production-path run using
`shared-css-hidden-source-v1`. Every phase's before/after receiver viewport
intersection was 1.00; the receiver box measured 562×317 in a 751×850 viewport.
It retained the preceding run's external-browser Compose NV12/60, one local peer,
default jitter preference, 20-second warmup and every-callback marker sampling.
All 2890 observed responses acknowledged waits; no polling fallback was recorded.

| Phase | Decoded dimensions | Source / presented FPS | Latency p50 / p95 ms | Playback total / dropped / displayed |
|---|---|---:|---:|---:|
| Steady | 1920×1080 | 56.45 / 54.45 | 75.08 / 91.29 | 563 / 18 / 545 |
| Sender cap | 1920×1080 → 1280×720 → 960×540 | 57.63 / 24.13 | 67.43 / 83.28 | 247 / 5 / 242 |
| Recovery | 960×540 | 57.67 / 54.77 | 67.74 / 83.50 | 570 / 23 / 547 |

Steady paint/capture/unique-source rates were 59.76/56.36/55.90 FPS. Mean source
paint age at capture was 29.43 ms, upload p95 0.20 ms, mean encode/decode
5.26/1.70 ms, and actual jitter buffering 11.95 ms. The 18 playback drops
represent 3.20% of playback frames and account for most decode-to-display loss;
compositor callbacks counted 546 presentations versus playback's 545, a
one-frame difference between non-atomic phase snapshots. No steady receiver
freeze or RTP-side frame drop was reported. Capture/source rates and the
receiver's display stage remain the observable throughput constraints.

Marker observation cost was 4.10 ms median/5.00 ms p95 at full resolution.
The next controlled case should keep this layout and all settings fixed while
setting `BOSS_SHARING_BENCHMARK_MARKER_EVERY=0`. That removes pixel readback and
raw marker observation, retaining source, encode/decode, compositor and playback
counters; latency is deliberately unavailable. Repeat interleaved marker-on/off
cases before attributing display drops to observer cost.

This is **one controlled-layout observation**, not evidence that the layout
change caused the difference from the prior run. Capture rate, encode cost,
GC timing and clock calibration also differed. This run's clock-mapping
uncertainty was 1.05 ms. Recovery still remained 960×540 with bandwidth
limitation; full-resolution recovery was not established. The same-browser
sender/receiver, local-network and missing native-text-quality limits above
continue to apply. The report is retained locally in
`/tmp/boss-native-benchmark`; no prior artifact was rewritten.

### Matched-layout counter-only observation

`native-1791009452264-NV12-60.json` passed with the same production transport,
shared layout, external browser, Compose NV12/60 and default jitter preference,
but `markerEvery=0`. Every receiver viewport intersection remained 1.00 and
all 2903 responses acknowledged waits. Pixel-readback and raw-marker observation
were disabled; reported callback observer cost was 0.00 ms median/p95. Native
paint-to-presentation latency is intentionally unavailable.

| Phase | Decoded dimensions | Source / presented FPS | Playback total / dropped / displayed |
|---|---|---:|---:|
| Steady | 1920×1080 | 57.46 / 55.17 | 575 / 22 / 553 |
| Sender cap | 1920×1080 → 1280×720 → 960×540 | 57.88 / 29.29 | 300 / 7 / 293 |
| Recovery | 960×540 | 58.01 / 55.81 | 576 / 17 / 559 |

Steady paint/capture/unique-source rates were 60.04/57.35/57.15 FPS. Mean paint
age at capture was 31.11 ms; mean encode/decode times were 2.85/1.66 ms. There
were no steady receiver freezes. Playback still dropped 22 of 575 frames
(3.83%), despite removing readback. This pair does not establish that observer
cost caused the prior drops: the marker-on run dropped 18 of 563 (3.20%), and
native capture rate, encode cost and GC timing also changed. Both runs indicate
some capture-stage loss and some display-stage loss, but currently do not measure
native complete-callback cadence, copy time, latest-slot overwrite count or EDT
queue wait. Those measurements are needed to test the fixed-delay polling
hypothesis. Recovery again stayed at 960×540; no full-resolution recovery claim
is supported. This is one counter-only observation, retained locally beside the
preceding reports, not a repeatability or SFU/network result.

### Optional receiver jitter experiment

Set `BOSS_SHARING_BENCHMARK_JITTER_TARGET_MS=default` (also the omitted default),
`0`, or `10` for native runs. Browser-only callers can pass
`jitterBufferTargetMs: null`, `0`, or `10` to `window.runBenchmark`.
This fixture-only option does not alter production receiver settings. The default
does not write the receiver property. Requested values are applied before the
answer and warmup; unsupported APIs, exceptions and ignored writes are labeled
`unsupported`, `rejected`, or `mismatch`, rather than treated as successful.
Inspect `scenario.receiverJitterTargets` before comparing an experimental case.

The API readback is the requested preference, not measured delay; the browser
may retain a larger buffer. See the
[WebRTC receiver specification](https://w3c.github.io/webrtc-pc/#dom-rtcrtpreceiver-jitterbuffertarget).
Per-phase reports now include average actual, target and minimum jitter-buffer
delay from cumulative stats divided by emitted-frame deltas, and rendered-frame
interval mean/standard deviation from rendered counts, plus freeze count/duration.
Missing browser statistics remain null. Rendered interval statistics are separate
from marker-observation intervals and compositor presentation counts; phase
boundary frames can straddle the sampling interval. See the
[WebRTC statistics definitions](https://w3c.github.io/webrtc-stats/).

Compare repeated default/0/10 runs with identical content, readback density,
capture format, delivery mode and warmup. Judge latency together with presented
FPS, drops, freezes and cadence variation. This loopback experiment does not
establish a safe target for packet loss or jitter on real networks.

### Observed native delivery comparison

Two single local macOS NV12/60 runs used an external browser, Compose source,
every-callback marker sampling and the default receiver jitter preference:

| Delivery | Phase | Presented FPS | Latency p50/p95 ms | Mean jitter ms | Paint-to-capture / HTTP mean ms |
|---|---|---:|---:|---:|---:|
| Polling | steady | 47.12 | 93.17 / 110.07 | 12.95 | 35.93 / 42.63 |
| Wait | steady | 50.98 | 73.97 / 83.17 | 13.03 | 29.22 / 29.25 |
| Polling | recovery | 48.44 | 90.33 / 91.60 | 12.98 | 36.28 / 43.37 |
| Wait | recovery | 54.64 | 74.98 / 84.12 | 13.73 | 35.04 / 35.06 |

These observations come from `native-1791002304321-NV12-60.json` (polling) and
`native-1791003646818-NV12-60.json` (wait), retained locally in
`/tmp/boss-native-benchmark`; the temporary artifacts are not committed.
Both steady phases decoded at width 1920; recovery remained at width 960 after
the sender cap. Recovery FPS is therefore not a full-resolution recovery result.
Native capture scheduling also differed, so the entire latency reduction cannot
be attributed to the HTTP wait option. These are one run per condition, not a
repeatability result, network/SFU result, or justification for a production
transport or jitter setting. Repeat interleaved trials before drawing that
conclusion.

### Observed receiver-target comparison

A subsequent wait/default repeat and wait/target-0 run used the same external
Compose NV12/60 arrangement and marker density. Both reports are valid and the
receiver API reported support; target 0 was applied and read back as 0.

| Receiver preference | Phase | Presented FPS | Latency p50/p95 ms | Actual / target / minimum jitter mean ms | Paint-to-capture mean ms |
|---|---|---:|---:|---:|---:|
| Default repeat | steady | 53.20 | 83.18 / 96.19 | 21.91 / 23.65 / 23.65 | 26.82 |
| Target 0 | steady | 55.32 | 74.66 / 83.17 | 13.47 / 15.45 / 15.45 | 28.41 |
| Default repeat | recovery | 54.44 | 66.53 / 75.23 | 18.67 / 19.86 / 19.86 | 19.88 |
| Target 0 | recovery | 55.13 | 66.54 / 75.47 | 12.73 / 13.91 / 13.91 | 28.02 |

Reports: `native-1791004356117-NV12-60.json` (default repeat) and
`native-1791004490686-NV12-60.json` (target 0), in the same temporary directory.
Both steady phases decoded width 1920; both recoveries stayed at width 960.
Both steady cases had zero reported receiver drops and freezes. Each cap phase
reported four freezes, and each recovery one freeze following the deliberate
reader pause. Freeze accounting can include a gap begun in the preceding phase;
these aggregate counts do not isolate a target-related smoothness effect.
Rendered-interval mean/standard deviation remained unavailable in this browser.

This evidence is insufficient to change production jitter defaults. The first
unchanged-default wait run already averaged 13.03 ms of actual jitter delay,
close to target 0's 13.47 ms. In the new pair, measured target and minimum delays
were identical within each case, consistent with the browser's estimated minimum
governing the buffer. The API's requested 0 was not an achieved zero delay.
Recovery latency was essentially unchanged even though capture age and buffering
differed. Run-to-run scheduling/network estimates remain confounding factors.
Keep the production preference unchanged pending repeated interleaved trials,
including target 10 and controlled network jitter/loss, and assess latency
together with drops, freezes and full-resolution recovery.

A fixture existing and compiling does not prove native runtime behavior. Repeat
and inspect actual reports on macOS, Windows and Linux before claiming
cross-platform native latency or a universal performance improvement.

A counter-only wait/default run (`native-1791004757633-NV12-60.json`, markerEvery 0)
measured 52.51 presented FPS at width 1920 with near-zero observer cost. It carries
no marker latency samples. Source capture timing also varied, so this single run
does not establish the causal effect of marker readback or a production frame-rate guarantee.

### Opt-in capture-stage diagnostics

Set `BOSS_SHARING_BENCHMARK_CAPTURE_DIAGNOSTICS=1` to add a per-capture observer.
The scheduling policy remains the existing 8 ms fixed delay. Normal captures and
benchmarks without this flag allocate no observer/frame stamps or new per-frame
timing objects and perform no diagnostic clock reads/counter updates.

Phase `nativeStages.captureDiagnostics` separates native callbacks, complete
samples, copied/published buffers, distinct latest reads and skipped sequences.
First-read gaps are counted separately from frames skipped between consumer
reads. Stream counters reset per stream; report counters aggregate the capture's
streams without identities. Native copy timing includes native plane/layout
lookup and byte copying, excluding the preceding sample validation and buffer
lock/unlock. Helper timing is explicitly `helper_read_wait`: IPC waiting plus
frame parsing/copy, not an OS native-copy measurement.

Worker pass period, time between passes, work, composition, native-ready-to-read
age and oldest selected-frame age at delivery are recorded. Each normal EDT
minimize/initial/final geometry check separates queue wait from execution. Error
recovery EDT calls remain inside worker-work timing but have no separate queue
histogram. Histograms have fixed bounds and an overflow bucket; reported p50/p95
are bucket upper bounds, not exact percentiles, and null when in overflow.
Snapshots are non-atomic across counters, so sample/bucket counts can differ at
phase boundaries. Only aggregate counts/histograms leave the JVM: no per-frame
stamps, native handles, source IDs or pixels are serialized.

Compare complete callback count against native publications, distinct latest
reads/sequence gaps, worker publications and source FPS. Then compare native
ready-to-read age against worker gap and EDT queue timing before attributing a
loss to polling. No scheduling or performance improvement is asserted merely
by enabling this probe; its enabled/disabled overhead also requires measurement.

### Observed capture-stage diagnostic run

`native-1791011263776-NV12-60.json` in `/tmp/boss-native-benchmark` is a valid
single macOS run with diagnostics enabled: Compose source, NV12/60, production
assets/native client, external browser, negotiated wait, default jitter, 20 s
warmup and markerEvery 0. The receiver remained fully inside the viewport.
Steady output decoded at 1920×1080; end-to-end marker latency and text quality
were not measured in this counter-only run.

| Steady measurement | Observed value |
|---|---:|
| Source paints / native complete callbacks | 59.96 / 57.97 per second |
| Capture delivery / client source / presented | 57.67 / 57.69 / 54.40 FPS |
| Worker passes / repeated latest reads | 103.78 per second / 464 of 1043 reads |
| Native pixel copy mean / p95 upper bound | 0.26 / 1 ms |
| Native-ready-to-read mean / p95 upper bound | 4.78 / 12 ms |
| Worker gap / worker work mean | 9.48 / 0.15 ms |
| Minimize / initial / final EDT queue mean | 0.038 / 0.037 / 0.028 ms |
| Paint-to-capture mean | 34.26 ms |
| Encoded / decoded / displayed frames | 575 / 574 / 546 |
| Browser playback drops | 28 of 574 frames (4.88%) |
| Mean encode / decode / jitter-buffer delay | 3.29 / 1.70 / 13.45 ms |

All 582 native complete callbacks were copied and published. There were four
inter-read skipped sequences, no startup gaps and no worker geometry rejections
or failures in steady state. Counters are non-atomic phase snapshots; their
one-frame boundary differences must not be interpreted as exact loss locations.
Native copying and EDT queueing were small here. The 4.78 ms ready-to-read delay
is consistent with a roughly 9.48 ms polling gap, making worker wakeup latency a
specific experiment worth testing. Only a few native publications were skipped:
removing polling alone cannot establish 60 FPS when native complete callbacks
already average about 58/s, nor explain all receiver playback drops. The 34.26 ms
paint age also includes upstream rendering/capture timing absent from the
native-ready stamp; it is not wholly a polling delay.

During the intentional 300 kbps sender cap, presentation fell to 26.58 FPS and
resolution stepped through 1920, 1280 and 960 widths. Recovery presented 54.55 FPS
but stayed at 960×540. The recovery freeze counter includes the preceding reader
pause and does not independently establish a new freeze. This is one run;
diagnostic overhead and scheduling variation are not isolated by comparing it
with earlier diagnostics-disabled results.

The next targeted experiment should retain the existing authority checks and
8 ms fallback while adding an opt-in, coalesced fresh-frame wakeup on the same
capture worker. Compare repeated interleaved polling and wakeup cases for
ready-to-read age, worker passes, sequence gaps, source/presented FPS and playback
drops; add a matched sparse-marker case to measure actual end-to-end latency.
Keep native rate, bitrate, jitter preference, layout and renderer fixed. A lower
capture-ready age is the primary hypothesis; increased FPS remains unproven.
