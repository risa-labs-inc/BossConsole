package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.parseTopInsetDp
import ai.rever.boss.plugin.browser.pointerInsideBounds
import ai.rever.boss.plugin.browser.shouldAllowPinch
import ai.rever.boss.plugin.browser.shouldRetainSurface
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.testsupport.repoRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the pure decisions behind the HARDWARE_ACCELERATED overlay path.
 *
 * These are the pieces with no other safety net: they are consumed inside composables and their
 * failure mode is visual (a menu in the wrong place, a tab that paints blank), which no other test
 * in this module would catch.
 */
class HeavyweightOverlayTest {
    // --- contentOffsetFor: the coordinate-space trap ---

    @Test
    fun `the cursor wins over the caller's anchor, and is converted from screen space`() {
        // The anchor must NOT be preferred. Callers compute it for the lightweight Popup branch,
        // which positions relative to the parent LAYOUT NODE: BossTabButton and BossActionButton
        // pass positionInParent(), and the Modifier.contextMenu path passes the pointer's
        // element-local position. Treating any of those as window-local displaces the menu by the
        // distance from the widget's parent to the window origin. Only the cursor is in a space
        // this function can convert, so only the cursor is trusted.
        val parentRelativeAnchor = IntOffset(120, 48)
        assertEquals(
            IntOffset(100, 200),
            contentOffsetFor(
                cursorX = 1300,
                cursorY = 500,
                anchor = parentRelativeAnchor,
                windowX = 1200,
                windowY = 300,
            ),
        )
    }

    @Test
    fun `a zero anchor is no different from any other - the cursor still wins`() {
        assertEquals(
            IntOffset(100, 200),
            contentOffsetFor(cursorX = 1300, cursorY = 500, anchor = IntOffset.Zero, windowX = 1200, windowY = 300),
        )
    }

    @Test
    fun `an unresolvable window origin is treated as zero rather than skewing the position`() {
        assertEquals(
            IntOffset(1300, 500),
            contentOffsetFor(cursorX = 1300, cursorY = 500, anchor = IntOffset.Zero, windowX = null, windowY = null),
        )
    }

    @Test
    fun `with no cursor the anchor is used unconverted, as an approximate fallback`() {
        // Keyboard-invoked menu: there is no pointer to read. The anchor is in the wrong space,
        // but it is small and near the widget, so it beats not appearing. Critically it must NOT
        // have the window origin subtracted - that put the menu at -windowX,-windowY, off-screen
        // for any window not at the origin, which is the regression this pins.
        assertEquals(
            IntOffset(120, 48),
            contentOffsetFor(
                cursorX = null,
                cursorY = null,
                anchor = IntOffset(120, 48),
                windowX = 1200,
                windowY = 300,
            ),
        )
        assertEquals(
            IntOffset.Zero,
            contentOffsetFor(cursorX = null, cursorY = null, anchor = IntOffset.Zero, windowX = 1200, windowY = 300),
        )
    }

    // --- browser surface top inset ---

    @Test
    fun `top inset clamps out values that would silently misplace the surface`() {
        // This offset moves a heavyweight native surface inside its slot with no error reporting,
        // so a stray negative shifts the page up under the toolbar and a huge one pushes it off
        // the bottom - both look like a rendering bug, not a mistyped setting.
        assertEquals(24, parseTopInsetDp("24"))
        assertEquals(0, parseTopInsetDp("0"))
        assertEquals(0, parseTopInsetDp("-200"))
        assertEquals(200, parseTopInsetDp("9999"))
        // Unset or unparseable means 0, the correct default on the measured machine.
        for (raw in listOf(null, "", "   ", "24px", "abc")) {
            assertEquals(0, parseTopInsetDp(raw), "expected 0 for '$raw'")
        }
    }

    // --- surface retention ---

    @Test
    fun `surfaces are retained only under HARDWARE_ACCELERATED`() {
        // OFF_SCREEN keeps the original close-on-hide lifecycle: an off-screen surface is a cheap
        // CPU bitmap, so rebuilding it on tab re-entry costs nothing worth retaining native
        // resources for.
        assertTrue(shouldRetainSurface(RenderingMode.HARDWARE_ACCELERATED))
        assertFalse(shouldRetainSurface(RenderingMode.OFF_SCREEN))
    }

    // --- pinch gating ---

    /**
     * The regression this guards is silent: under HARDWARE the browser is a foreign native window,
     * so Compose never reports the pointer entering it and the old hover gate rejected every pinch
     * with nothing but a debug line. Asserted as "hover alone is not enough", not just "geometry
     * works", because a fix that ORed the two signals would pass a geometry-only test while still
     * letting a background split zoom on a stale hover flag.
     */
    @Test
    fun `under HARDWARE the pinch gate uses geometry, and hover alone is never enough`() {
        assertTrue(
            shouldAllowPinch(
                RenderingMode.HARDWARE_ACCELERATED,
                isValid = true,
                pointerOverComposeView = false,
                pointerInsideBounds = true,
            ),
        )
        // The old gate's inputs, which HARDWARE can produce indefinitely: hover true is not a
        // licence to zoom when the pointer is demonstrably elsewhere.
        assertFalse(
            shouldAllowPinch(
                RenderingMode.HARDWARE_ACCELERATED,
                isValid = true,
                pointerOverComposeView = true,
                pointerInsideBounds = false,
            ),
        )
    }

    @Test
    fun `an undeterminable pointer position refuses the pinch rather than guessing`() {
        // Pre-layout, no window, or headless. A pinch that does nothing is recoverable by pinching
        // again; one that zooms an unpointed browser in another split is not something the user
        // asked for and may not even notice.
        for (hover in listOf(true, false)) {
            assertFalse(
                shouldAllowPinch(
                    RenderingMode.HARDWARE_ACCELERATED,
                    isValid = true,
                    pointerOverComposeView = hover,
                    pointerInsideBounds = null,
                ),
                "expected no zoom with an unknown pointer (hover=$hover)",
            )
        }
    }

    @Test
    fun `OFF_SCREEN keeps hover, so the platforms that already worked cannot regress`() {
        assertTrue(
            shouldAllowPinch(
                RenderingMode.OFF_SCREEN,
                isValid = true,
                pointerOverComposeView = true,
                pointerInsideBounds = null,
            ),
        )
        // Geometry must not be able to override an accurate hover signal here — an OFF_SCREEN view
        // really is a component, and its hover is the authoritative answer.
        assertFalse(
            shouldAllowPinch(
                RenderingMode.OFF_SCREEN,
                isValid = true,
                pointerOverComposeView = false,
                pointerInsideBounds = true,
            ),
        )
    }

    @Test
    fun `a disposed handle never zooms, whatever the pointer says`() {
        for (mode in listOf(RenderingMode.HARDWARE_ACCELERATED, RenderingMode.OFF_SCREEN)) {
            assertFalse(
                shouldAllowPinch(mode, isValid = false, pointerOverComposeView = true, pointerInsideBounds = true),
                "expected no zoom on an invalid handle in $mode",
            )
        }
    }

    /**
     * The px-vs-dp conversion the pinch gate depends on.
     *
     * `boundsInWindow()` is DEVICE PIXELS; an AWT pointer through `convertPointFromScreen` is
     * LOGICAL UNITS. They coincide only at density 1.0, so comparing them raw is correct on an
     * unscaled external monitor and wrong by the density factor on a Retina panel — which is
     * exactly the shape of bug no test on an unscaled CI runner would catch, hence the table.
     */
    @Test
    fun `the pinch bounds test converts the pointer into pixel space`() {
        // Browser occupies window px (0,100)-(2000,1300); at density 2 that is logical
        // (0,50)-(1000,650), with a terminal split beneath it.
        val browserPx = Rect(0f, 100f, 2000f, 1300f)

        // The regression: logical (500,700) is over the TERMINAL, but compared raw it satisfies
        // 700 < 1300 and 500 < 2000, so an unconverted gate would zoom a browser the pointer is
        // not over — the one thing the gate exists to prevent.
        assertFalse(pointerInsideBounds(browserPx, Offset(500f, 700f), density = 2f))
        assertTrue(browserPx.contains(Offset(500f, 700f)), "raw compare would wrongly accept this")

        // The mirror: genuinely inside, and an unconverted gate would refuse it.
        val rightSplitPx = Rect(1400f, 100f, 2800f, 1300f)
        assertTrue(pointerInsideBounds(rightSplitPx, Offset(720f, 300f), density = 2f))
        assertFalse(rightSplitPx.contains(Offset(720f, 300f)), "raw compare would wrongly refuse this")
    }

    @Test
    fun `pinch bounds hold at every common display scale`() {
        val boundsPx = Rect(0f, 100f, 2000f, 1300f)
        // The same physical point, expressed in each scale's logical units, must give one answer.
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) {
            val insideLogical = Offset(500f / density, 700f / density)
            assertTrue(
                pointerInsideBounds(boundsPx, insideLogical, density),
                "expected inside at density $density",
            )
            val outsideLogical = Offset(500f / density, 1400f / density)
            assertFalse(
                pointerInsideBounds(boundsPx, outsideLogical, density),
                "expected outside at density $density",
            )
        }
    }

    // --- overlay window sizing ---

    /**
     * The overlay fallback must be an explicit rect, never `WindowPlacement.Maximized`.
     *
     * Chased while investigating an intermittent report of a grey wash over the whole window when
     * opening a new tab. The New Tab dialog is a heavyweight modal under HARDWARE, and it draws a
     * 40%-black scrim across whatever its window covers — so "could not measure the parent" turns
     * a dialog into a full-screen wash. Maximizing an undecorated transparent window also routes
     * through the platform zoom path, where macOS can bring it up opaque, so the fallback used to
     * fail in two ways at once.
     *
     * Asserted on the pure size choice rather than by composing a Window, which needs a display.
     */
    @Test
    fun `overlay falls back to the given screen rect, not to a zero or maximized window`() {
        val screen = intArrayOf(0, 0, 2560, 1440)
        assertEquals(screen.toList(), overlayRectOrScreen(null, screen).toList())
        // A measured parent always wins, and is used verbatim — the scrim has to line up with the
        // window exactly or it appears as a misplaced grey band.
        val parent = intArrayOf(60, 40, 1400, 870)
        assertEquals(parent.toList(), overlayRectOrScreen(parent, screen).toList())
    }

    // --- overlay routing ---

    /**
     * [OverlayConfig] is a process-wide singleton, so a test that injects into it would leak into
     * every later test in this module. Reset after each one rather than relying on ordering.
     */
    @AfterTest
    fun resetOverlayConfig() {
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = false
        resetOverlayFieldForTest("popupRenderer")
        OverlayConfig.heavyweightPopup = null
        resetOverlayFieldForTest("modalRenderer")
        OverlayConfig.heavyweightModal = null
        OverlayConfig.heavyweightTooltip = null
        OverlayConfig.hideHeavyweightTooltip = null
        OverlayConfig.heavyweightHud = null
        OverlayConfig.heavyweightGhost = null
        OverlayConfig.heavyweightCorner = null
        OverlayConfig.openHeavyweightPopups = 0
    }

    @Test
    fun `overlay renderers default to null so callers fall back to Compose popups`() {
        // useHeavyweightPopups can be true while the renderers are null: any entry point that does
        // not run main.kt's wiring (a test host, a tool) reaches exactly that state. The null
        // checks in ContextMenu / BossDialog are what stand between that and no menus at all,
        // so the defaults they rely on are pinned here.
        assertFalse(OverlayConfig.useHeavyweightPopups)
        assertNull(OverlayConfig.heavyweightPopup)
        assertNull(OverlayConfig.heavyweightModal)
        assertNull(OverlayConfig.heavyweightTooltip)
        assertNull(OverlayConfig.heavyweightCorner)
        assertNull(OverlayConfig.hideHeavyweightTooltip)
        assertNull(OverlayConfig.heavyweightHud)
        assertNull(OverlayConfig.heavyweightGhost)
        assertEquals(0, OverlayConfig.openHeavyweightPopups)
    }

    @Test
    fun `the modal switch and popup counter are the same ones plugins see`() {
        // OverlayConfig's modal properties are forwarding accessors onto BossOverlayHost, which is
        // what plugin-drawn dialogs read. If that forwarding is ever replaced by a second backing
        // field, the host would route heavyweight while every plugin dialog silently stayed behind
        // the page - the exact bug this path exists to fix, and invisible from either side.
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = true
        assertTrue(BossOverlayHost.useHeavyweightOverlays)

        OverlayConfig.openHeavyweightPopups = 3
        assertEquals(3, BossOverlayHost.openHeavyweightPopups)

        BossOverlayHost.openHeavyweightPopups = 0
        assertEquals(0, OverlayConfig.openHeavyweightPopups)
    }

    // --- modal dismissal ---

    @Test
    fun `a modal does not dismiss while one of its own popups holds focus`() {
        // The reported dead end: NewTabDialog's folder dropdown is a ContextMenu, which under
        // HARDWARE is its own always-on-top window. Opening it fires the modal's windowLostFocus,
        // and dismissing there closed the entire dialog the dropdown belongs to.
        assertFalse(
            shouldDismissOnFocusLoss(dismissOnFocusLoss = true, openHeavyweightPopups = 1, oppositeWindow = null),
        )
        assertFalse(
            shouldDismissOnFocusLoss(dismissOnFocusLoss = true, openHeavyweightPopups = 2, oppositeWindow = null),
        )
    }

    @Test
    fun `a modal dismisses when focus leaves the application entirely`() {
        // A null opposite window means focus went somewhere AWT does not own - another app. That
        // is the case the focus-loss dismissal actually exists for.
        assertTrue(
            shouldDismissOnFocusLoss(dismissOnFocusLoss = true, openHeavyweightPopups = 0, oppositeWindow = null),
        )
    }

    @Test
    fun `an opted-out modal never dismisses on focus loss, even leaving the app entirely`() {
        // The #152 regression: MemoryPressureNoticeDialog and ScreenCapturePickerDialog dismiss to a
        // destructive action (acknowledge the once-per-session notice; cancel the share). They pass
        // dismissOnFocusLoss = false so an alt-tab away cannot trigger it. This is the case a null
        // oppositeWindow - focus leaving the app - would otherwise dismiss.
        assertFalse(
            shouldDismissOnFocusLoss(dismissOnFocusLoss = false, openHeavyweightPopups = 0, oppositeWindow = null),
        )
    }

    @Test
    fun `the opt-out suppresses dismissal even with an open popup`() {
        // dismissOnFocusLoss = false must win regardless of the other inputs.
        assertFalse(
            shouldDismissOnFocusLoss(dismissOnFocusLoss = false, openHeavyweightPopups = 1, oppositeWindow = null),
        )
    }

    @Test
    fun `destructive dialogs retain their dismissal policy around BossDialog`() {
        val root = repoRoot()
        listOf(
            "performance/MemoryPressureNoticeDialog.kt",
            "plugin/browser/ScreenCapturePickerDialog.kt",
        ).forEach { path ->
            val source = root.resolve("composeApp/src/desktopMain/kotlin/ai/rever/boss/$path").readText()
            assertTrue(
                Regex(
                    "CompositionLocalProvider\\(LocalDismissModalOnFocusLoss provides false\\)" +
                        "\\s*\\{\\s*BossDialog\\(",
                ).containsMatchIn(source),
                "$path must opt the whole dialog out of focus-loss dismissal",
            )
            assertTrue(source.contains("dismissOnClickOutside = false"), "$path must not dismiss on a return click")
        }
    }

    // --- overlay transparency diagnosis ---

    // A false verdict from overlayWillPaintOpaque does NOT mean the overlay looked right. It was
    // measured false on a window that was visibly grey, and that disagreement is what proved the
    // opacity lives below Java. These tests pin what the predicate claims about skiko's clear rule,
    // and nothing about what reached the screen.

    @Test
    fun `an overlay paints transparent only when the flag is on and the clear colour has no alpha`() {
        // Mirrors skiko's own rule, which is the whole reason this predicate exists rather than a
        // bare `!transparency`: SkiaLayer clears with `bg` when transparency is on, and with
        // `bg or 0xFF000000` otherwise. So the flag being on is necessary and NOT sufficient - a
        // layer whose background is opaque clears opaque either way, and that pair
        // (transparency on, background inherited opaque) is what a null layer background
        // degenerates into.
        assertFalse(overlayWillPaintOpaque(transparency = true, backgroundAlpha = 0))
        assertTrue(overlayWillPaintOpaque(transparency = false, backgroundAlpha = 0))
        assertTrue(overlayWillPaintOpaque(transparency = true, backgroundAlpha = 255))
        assertTrue(overlayWillPaintOpaque(transparency = false, backgroundAlpha = 255))
    }

    @Test
    fun `a layer this could not inspect is reported as opaque, never as healthy`() {
        // Both inputs are null when reflection could not reach the Skia layer at all. Reporting
        // "healthy" there is the one answer indistinguishable from a genuine pass, which would make
        // the entire diagnostic unfalsifiable - the same trap the AWT-background check it replaces
        // fell into, where a condition that could never be true read as evidence that nothing was
        // wrong.
        assertTrue(overlayWillPaintOpaque(transparency = null, backgroundAlpha = null))
        assertTrue(overlayWillPaintOpaque(transparency = true, backgroundAlpha = null))
        assertTrue(overlayWillPaintOpaque(transparency = null, backgroundAlpha = 0))
    }

    @Test
    fun `the native re-assert runs only on macOS, on a transparent window, where it can complete`() {
        // The happy path, and the only combination that should act.
        assertTrue(shouldReassertTransparency(backgroundAlpha = 0, isMacOs = true, translucencyCapable = true))

        // Not macOS. The bug and its mechanism are macOS-only, and this gate is the whole basis of
        // the claim that Windows and Linux cannot regress - which was previously argued in prose
        // from skiko internals, and was wrong: resolveRenderingMode returns HARDWARE_ACCELERATED on
        // every platform, so every platform reaches this code.
        assertFalse(shouldReassertTransparency(backgroundAlpha = 0, isMacOs = false, translucencyCapable = true))

        // Not a transparent overlay. null is what skiko leaves on Windows without Direct3D; forcing
        // translucency onto either would be inventing a change rather than repairing one.
        assertFalse(shouldReassertTransparency(backgroundAlpha = null, isMacOs = true, translucencyCapable = true))
        assertFalse(shouldReassertTransparency(backgroundAlpha = 255, isMacOs = true, translucencyCapable = true))
    }

    @Test
    fun `an incapable or unknown graphics configuration refuses, because a half-done cycle is the bug`() {
        // Window.setBackground applies super.setBackground first and only then throws for a
        // configuration that cannot do per-pixel translucency - after the opaque leg has already set
        // the layered, root and content panes opaque, and before the leg that would reverse them. An
        // opaque content pane filling before Skia draws IS the grey backdrop, so attempting the cycle
        // here would manufacture the very fault being repaired.
        assertFalse(shouldReassertTransparency(backgroundAlpha = 0, isMacOs = true, translucencyCapable = false))
        // Unknown is refused, not attempted. Everywhere else in this file an unreadable value is
        // treated as the pessimistic answer; here the pessimistic answer is "do not touch it".
        assertFalse(shouldReassertTransparency(backgroundAlpha = 0, isMacOs = true, translucencyCapable = null))
    }

    @Test
    fun `the skia layer is matched by its fully qualified skiko class name`() {
        // A headless test cannot construct a real SkiaLayer - it needs a display and a render
        // device - so this predicate is the only part of the tree walk a unit test can reach, and
        // it is the part that silently stops matching if skiko ever moves the class. A near miss
        // must not match: the walk would then read getTransparency() off the wrong component and
        // report a confident, wrong answer.
        assertTrue(isSkiaLayerClassName("org.jetbrains.skiko.SkiaLayer"))
        assertFalse(isSkiaLayerClassName("SkiaLayer"))
        assertFalse(isSkiaLayerClassName("org.jetbrains.skiko.SkiaLayerKt"))
        assertFalse(isSkiaLayerClassName("androidx.compose.ui.awt.ComposeWindowPanel"))
    }
}
