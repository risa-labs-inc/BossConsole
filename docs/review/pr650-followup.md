# PR #650 review follow-up

This change addresses the four blocking findings in the [maintainer review](https://github.com/risa-labs-inc/BossConsole/pull/650#issuecomment-5663173004).
The companion transport consumer is updated in [fluck-browser #45](https://github.com/risa-labs-inc/boss-plugin-fluck-browser/pull/45).
The [latest host re-review](https://github.com/risa-labs-inc/BossConsole/pull/650#issuecomment-5671215711) is addressed as follows:

- Active records now carry the preceding native termination callback time. Deterministic tests pin ordinary release, direct replacement, publication order and disable/restart persistence. The companion rejects events at or before that cutoff while accepting old three-field active records.
- Input Monitoring instructions cover a fresh install where BOSS is absent from the pane, and the physical checklist makes that case unconditional. Permission denial intentionally retries after the documented off/on transition; failed observation may retry immediately.
- Native release vetoes are debug-logged, and the zoom check explicitly compares native point thresholds with the page's CSS-pixel decision.
- Listener callbacks are documented as enqueue-only while the tap monitor is held. The observable flag remains deliberate redundancy at the claim boundary, and the page-visible gesture token is recorded with browser telemetry.
- Redundant page hot-path gates, an unused test import and a self-comparison were removed. Node absence now reports a useful parity-test error; STARTING copy and leading horizontal jitter have regressions.
- The terminal field is named `nativeRejected` throughout the contract. No threshold, sign convention, permission prompt or locking design changed.

## Changes and evidence

| Finding | Change | Regression coverage |
| --- | --- | --- |
| Native/page cancellation differs | Native code retains leading vertical travel but evaluates cancellation only on horizontal samples, as the page does. Commit qualification waits for three horizontal samples; zero-crossing reversal matches the page. | Shared sample fixtures run through the actual native reducer and page script, including leading noise, vertical curl, easing, reversal, post-threshold drift, short flick and cancellation. Constants are pinned across languages. |
| Element blur cancels a swipe | Only a blur targeted at the window resets the detector. | Element focus transfer commits; window blur and hidden-document transitions cancel. |
| Off switch and failure lifecycle | Startup binds observation to the effective setting. Native setup/poll/cleanup runs on a daemon worker. Disabled sessions cancel immediately; their callbacks cannot join a replacement session. Setup failures release partial resources, and run-loop failures cancel and permit retry. | Disabled startup, queued startup cancellation, disable/re-enable, denied permission, run-loop return/exception, partial allocation, replacement, tap interruption and concurrent claim/release tests. Settings descriptions distinguish permission denial from failure, including environment-owned settings. |
| Wheel hot path and lossy companion transport | Disabled and line-mode wheels avoid host IPC. Vertical pixel samples still retain initial scroll ownership. Active publication occurs once per contact and carries the previous native termination cutoff. The host retains 32 terminal records, which the companion reconciles before a new pointer event or watchdog poll can discard the old contact. | Existing nested-scroller tests retained. Publication-order, cutoff and bounded-history tests cover rapid contacts; companion tests cover recovery, cancellation, missing/evicted evidence and stale watchdog ownership. |

Terminal history is a bounded, non-destructive record, not an unbounded delivery queue. A consumer delayed past 32 subsequent releases cancels if its record has been evicted. Updated companion code also recognizes that these hosts cancel on failure themselves, so the old ten-second watchdog does not cancel a stationary native hold.

## Other review observations

- Non-macOS regression and iframe release were ruled out by the maintainer: both settings and injection are macOS-gated, and the detector skips subframes. These gates remain intact.
- Nested horizontal scrollers retain the contact even at an edge, and momentum is excluded. Both are deliberate requirements and remain tested.
- No speculative changes were made to native delta units, the 1 ms renderer timestamp tolerance, or initial direction thresholds. The native/page direction policies now agree; the early-timestamp acceptance boundary is tested. Hardware calibration is still required below.
- The 400 ms same-direction host gate remains conservative across document replacement. Its comments no longer refer to the removed inactivity timer. It also limits repeated page-originated bridge calls; two intentional same-direction requests inside the window can still be refused.
- Native snapshot claims now consult `observable`. A replacement Begin/MayBegin cancels prior claimants. Source/tap resources and callback retention have explicit cleanup.
- The production bridge still supplies its native claim. Its nullable default remains useful to existing bridge-only tests; this was not classified as a production defect by the maintainer.
- The existing JNA boolean mapping remains unchanged; the maintainer requested evidence before speculative ABI changes.
- Duplicate/stale releases, unavailable observation and visibility reset have page regressions. The host-generated release statement itself is executed by the cross-layer suite.

## Validation

Local checks:

- `node scripts/test/test-swipe-nav.js`
- `:composeApp:desktopTest` restricted to BrowserSwipeNavTest, MacOSScrollGesturePhasesTest, ScrollPhaseObserverTest, SwipeNavParityTest and SwipeNavSettingsTest
- `:composeApp:detekt :composeApp:ktlintCheck`
- Companion: `CI=true ./gradlew test buildPluginJar` using its downloaded API dependency, matching CI

GitHub CI must pass for the final pushed commits. Local automated results do not establish physical trackpad behavior.

## Required physical macOS check

Still to be performed on the paired host/plugin build; no new hardware validation is claimed:

- Hold a completed swipe stationary, including beyond ten seconds: no navigation until release.
- Release a qualifying swipe: one navigation; repeat in both directions.
- Ease below the threshold and reverse through zero: no navigation.
- Try a short flick with a large momentum tail: momentum must not supply missing finger travel.
- Scroll nested horizontal content from its interior and either edge: retain ownership throughout.
- Repeat on the browser home surface, including rapid successive contacts while navigation is busy.
- Compare affordance progress and release behavior for slow/fractional drags and browser zoom. At non-100% zoom, explicitly check whether the native point-based vertical threshold vetoes a gesture the CSS-pixel page detector accepts; native rejection is logged for diagnosis. The companion's existing calibration is preserved.
- On a Mac that has never granted BOSS Input Monitoring, confirm BOSS appears in System Settings > Privacy & Security > Input Monitoring. The app only preflights access and does not prompt; if absent, use the pane's `+` control and select BOSS.app when that option is available. Grant access, then turn swipe navigation off and on to retry.

The reviewer requested this check before merge. Keep that requirement visible rather than treating green automated tests as a substitute.

## Follow-up review: page ownership and performance

- Scroll a heavy page rapidly with swipe navigation enabled and watch for jank or stalls.
  The native contact claim only reads observer state; it must not call back into renderer/navigation.
- Check an ordinary article containing a chart/canvas or an ARIA grid, as well as a page
  with root `overscroll-behavior-x: contain` or `none`. A gesture over an unknown canvas/grid
  or an explicitly contained scroll chain intentionally stays with the page. This is a
  conservative navigation veto, not proof that every such element can scroll horizontally.
- Google Sheets is the explicit exception: a fresh outward gesture at the horizontal edge
  can navigate. `.grid-container` and `.native-scrollbar-x` are private page selectors; if
  they change, the detector safely falls back to page ownership. Re-test after Sheets changes.
- Repeated Input Monitoring off/on retry should not double-navigate; obsolete observer
  generations are ignored even while the previous native tap is winding down.

Node is a required test dependency for `SwipeNavParityTest`; a missing interpreter or
source fixture fails rather than silently skipping the JavaScript parity check.
