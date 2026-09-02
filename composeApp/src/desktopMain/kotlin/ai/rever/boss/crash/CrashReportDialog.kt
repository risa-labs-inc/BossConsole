package ai.rever.boss.crash

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Crash report dialog content shown when BOSS encounters an unhandled exception.
 *
 * This component renders the crash report UI directly without a Dialog wrapper,
 * as it's designed to be displayed in a standalone window (JFrame with ComposePanel).
 * This ensures the crash dialog appears even when the main Compose UI is broken.
 *
 * Features:
 * - Error summary with exception type and message
 * - Expandable technical details (stack trace)
 * - Copy to clipboard button
 * - User notes text field
 * - Optional inclusion of recent activity logs
 * - Submit to GitHub and dismiss buttons
 *
 * Layout: a fixed header, a scrollable body, and a pinned footer. The footer keeps the
 * action buttons and the submit result reachable no matter how tall the body grows —
 * without it, expanding the technical details section pushes the buttons out of the
 * (deliberately small) crash window.
 *
 * @param crashReport The crash report to display
 * @param recoverablePluginId Set when this crash is attributable to a dynamic plugin that can be
 *   disabled instead of taking the app down with it. It changes what the dialog *says* and what its
 *   exits *mean*: dismissing continues without that plugin rather than ending the session, so
 *   "Don't Send" (accurate only when the next thing that happens is termination) becomes
 *   [CONTINUE_WITHOUT_PLUGIN_LABEL]. Null for a fatal host crash, which behaves as it always has.
 * @param onDismiss Called when user dismisses without submitting
 * @param onSubmit Called when user wants to submit the report
 * @param initialSubmitResult Seeds the submit-result card. Production leaves this null and lets the
 *   submit path fill it in; it exists because that path runs through the [CrashReportService]
 *   object, so the result state is otherwise unreachable — and the footer's behaviour under a long
 *   failure message is exactly what the `maxLines` cap below exists to bound. Also makes the
 *   populated footer previewable. The dialog is `internal`, so this widens nothing externally.
 */
@Composable
internal fun CrashReportDialog(
    crashReport: CrashReport,
    onDismiss: () -> Unit,
    onSubmit: (userNotes: String?, includeLogs: Boolean) -> Unit,
    recoverablePluginId: String? = null,
    onCleanAndRestart: (() -> Unit)? = null,
    onSubmittingChanged: (Boolean) -> Unit = {},
    initialSubmitResult: CrashReportService.SubmitResult? = null,
) {
    var userNotes by remember { mutableStateOf("") }
    var includeLogs by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf(initialSubmitResult) }

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val bodyScrollState = rememberScrollState()

    // Hoisted out of the collapsible content below, so the trace keeps its scroll position
    // across a collapse/expand cycle instead of snapping back to the top. Consequence: while
    // collapsed its maxValue holds a stale value from when the pane was last measured, so
    // `traceOverflows` is only meaningful — and is only read — inside that content.
    val stackTraceScrollState = rememberScrollState()

    // Whether each region is clipping content, which gates its scrollbar — and, for the body, the
    // rule above the footer.
    //
    // `maxValue` starts at Int.MAX_VALUE and is only assigned during measure, so a bare `> 0`
    // reports "overflowing" on the first composition, before anything has been measured. Excluding
    // the sentinel keeps that first frame honest; derivedStateOf keeps a settling `maxValue` from
    // recomposing the whole dialog when the answer hasn't actually flipped.
    //
    // Nothing gated on this may change the body's size, in either axis. Both the scrollbar and the
    // boundary rule are overlays inside the body for that reason: a gated element that consumed
    // layout space would feed back into the measurement deciding whether to show it — monotone, so
    // never oscillating, but able to latch overflow on for content that sits near the boundary.
    val bodyOverflows by remember { derivedStateOf { bodyScrollState.isClipping() } }
    val traceOverflows by remember { derivedStateOf { stackTraceScrollState.isClipping() } }

    // The visible-against-dark-panels scrollbar style now comes from LocalScrollbarStyle
    // at the BossTheme root (plugin-ui-core's BossTheme.kt) rather than a local copy here —
    // see #106.
    val scrollbarStyle = LocalScrollbarStyle.current

    // Derived from the thumb it makes room for, so restyling the scrollbar can't leave it
    // overlapping text.
    val scrollbarGutter = scrollbarStyle.thickness + 4.dp

    // Escape is one of three ways out of this window and has to be as reliable as the other two.
    // `onKeyEvent` only fires for a focused subtree, so without an owner of its own it worked or
    // not depending on whether some child (the notes field) happened to hold focus — a dismissal
    // route that silently does nothing is worse than one that isn't offered.
    val dialogFocus = remember { FocusRequester() }
    // Logged rather than silently swallowed: the failure mode is "Escape stops
    // working", which is the exact thing this line exists to fix, and a silent
    // catch would leave no trace of a dismissal route quietly going missing.
    LaunchedEffect(Unit) {
        runCatching { dialogFocus.requestFocus() }
            .onFailure { failure ->
                BossLogger
                    .forComponent("CrashReportDialog")
                    .warn(
                        LogCategory.UI,
                        "Crash dialog could not take focus - Escape may not dismiss it",
                        mapOf("errorType" to failure.javaClass.simpleName),
                    )
            }
    }

    val dismissLabel = if (recoverablePluginId != null) CONTINUE_WITHOUT_PLUGIN_LABEL else DONT_SEND_LABEL

    // Published so the exits that live outside this composition - the window's
    // close box - can refuse while a submission is in flight. Escape and the
    // button gate on the local state below; this is the same fact, told to the
    // one caller that cannot see it.
    //
    // The submit handler also calls this directly on both edges, so the close box's
    // gate flips in the same instant theirs does. Through a LaunchedEffect alone it
    // trailed them by a recomposition, which is a window - small, but exactly the
    // kind a fast double-input walks into.
    LaunchedEffect(isSubmitting) { onSubmittingChanged(isSubmitting) }

    // Render directly in the window (no Dialog wrapper needed since this is shown in its own JFrame)
    Card(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(dialogFocus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && !isSubmitting) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
        shape = RoundedCornerShape(0.dp),
        backgroundColor = BossTheme.colors.panel,
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
        ) {
            // Header with error icon — fixed, never scrolls away
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Error",
                    tint = BossTheme.colors.alert,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (recoverablePluginId != null) "Plugin Crashed" else "BOSS Has Crashed",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                )
            }

            // Says what is about to happen, because the buttons alone cannot: the user needs to
            // know their windows and other plugins survive this, and which plugin is going away.
            if (recoverablePluginId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text =
                        "BOSS keeps running. '${displayPluginId(recoverablePluginId)}' will be disabled - " +
                            "your other tabs and plugins are unaffected. Re-enable it from Toolbox.",
                    fontSize = 13.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // The body scrollbar overlays this content rather than reserving a gutter beside it.
            // A gutter would keep the body's right edge from lining up with the footer's, and if
            // it were gated on overflow it would also feed back into the measurement deciding it:
            // narrower content is taller content, so overflow would latch on once entered. Nothing
            // in the body renders hard against its right edge (every card and field has its own
            // padding), so an overlaid thumb costs nothing.
            //
            // Scrollable body — capped at the space left over by the header and footer, so
            // anything that grows (expanded stack trace, long exception message) scrolls here
            // instead of pushing the action buttons past the bottom of the window.
            // `fill = false` keeps the cap from becoming a floor: when the content is short the
            // body stays short and the footer sits right below it, as it did before the cap.
            Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(bodyScrollState),
                ) {
                    // Error summary
                    Card(
                        backgroundColor = BossTheme.colors.raised,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = crashReport.exceptionType,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BossTheme.colors.alert,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = crashReport.exceptionMessage,
                                fontSize = 13.sp,
                                color = BossTheme.colors.textPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Expandable technical details
                    Card(
                        backgroundColor = BossTheme.colors.raised,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            // Header row (clickable)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { showDetails = !showDetails }
                                        .padding(12.dp),
                            ) {
                                Text(
                                    text = "Technical Details",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BossTheme.colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (showDetails) "Collapse" else "Expand",
                                    tint = BossTheme.colors.textSecondary,
                                )
                            }

                            // Expandable content
                            AnimatedVisibility(
                                visible = showDetails,
                                enter = expandVertically(),
                                exit = shrinkVertically(),
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp)
                                            .padding(bottom = 12.dp),
                                ) {
                                    // Copy button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(crashReport.stackTrace))
                                            },
                                            colors =
                                                ButtonDefaults.textButtonColors(
                                                    contentColor = BossTheme.colors.signalText,
                                                ),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ContentCopy,
                                                contentDescription = "Copy",
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copy to Clipboard", fontSize = 12.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Stack trace — bounded and independently scrollable so a deep
                                    // trace doesn't turn the body into an endless scroll
                                    SelectionContainer {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = TRACE_PANE_MAX_HEIGHT)
                                                    .testTag(TRACE_PANE_TAG)
                                                    .background(
                                                        BossTheme.colors.panel,
                                                        RoundedCornerShape(4.dp),
                                                    ).padding(8.dp),
                                        ) {
                                            Text(
                                                text = crashReport.stackTrace,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = BossTheme.colors.textPrimary,
                                                lineHeight = 14.sp,
                                                modifier =
                                                    Modifier
                                                        .verticalScroll(stackTraceScrollState)
                                                        // Unconditional here, unlike the body:
                                                        // trace lines *do* run to the edge, so the
                                                        // thumb may not overlay them — and inside
                                                        // this panel a permanent inset has no
                                                        // alignment reference to break.
                                                        .padding(end = scrollbarGutter),
                                            )
                                            if (traceOverflows) {
                                                VerticalScrollbar(
                                                    modifier =
                                                        Modifier
                                                            .align(Alignment.CenterEnd)
                                                            .fillMaxHeight()
                                                            .testTag(TRACE_SCROLLBAR_TAG),
                                                    adapter = rememberScrollbarAdapter(stackTraceScrollState),
                                                    style = scrollbarStyle,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // User notes input
                    Text(
                        text = "What were you doing when this happened? (optional)",
                        fontSize = 13.sp,
                        color = BossTheme.colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = userNotes,
                        onValueChange = { userNotes = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 80.dp, max = 120.dp),
                        placeholder = {
                            Text(
                                "Describe what you were doing...",
                                color = BossTheme.colors.textMuted,
                            )
                        },
                        colors =
                            TextFieldDefaults.outlinedTextFieldColors(
                                textColor = BossTheme.colors.textPrimary,
                                backgroundColor = BossTheme.colors.raised,
                                focusedBorderColor = BossTheme.colors.signal,
                                unfocusedBorderColor = BossTheme.colors.line,
                                cursorColor = BossTheme.colors.signal,
                            ),
                        enabled = !isSubmitting,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Include logs checkbox
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !isSubmitting) { includeLogs = !includeLogs }
                                .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = includeLogs,
                            onCheckedChange = null,
                            colors =
                                CheckboxDefaults.colors(
                                    checkedColor = BossTheme.colors.signal,
                                    uncheckedColor = BossTheme.colors.textMuted,
                                    checkmarkColor = BossTheme.colors.onSignal,
                                ),
                            enabled = !isSubmitting,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Include recent activity logs",
                                fontSize = 14.sp,
                                color = BossTheme.colors.textPrimary,
                            )
                            Text(
                                text = "Helps with debugging (logs are sanitized)",
                                fontSize = 11.sp,
                                color = BossTheme.colors.textMuted,
                            )
                        }
                    }
                }

                if (bodyOverflows) {
                    // Marks the clipped edge. Overlaid rather than stacked below the body so it
                    // costs no layout height — see the footer comment. Same *position* as the old
                    // sibling rule (the body is at its cap, so the ~17dp freed from the footer goes
                    // straight back to it), but body content now runs right up to the rule instead
                    // of leaving a 16dp gap above it.
                    Divider(
                        color = BossTheme.colors.line,
                        modifier = Modifier.align(Alignment.BottomStart).testTag(BOUNDARY_RULE_TAG),
                    )
                    VerticalScrollbar(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .testTag(BODY_SCROLLBAR_TAG),
                        adapter = rememberScrollbarAdapter(bodyScrollState),
                        style = scrollbarStyle,
                    )
                }
            }

            // Pinned footer — the submit result and the action buttons stay visible regardless of
            // how much the body above has grown or scrolled. The rule marking the scroll boundary
            // is drawn as an overlay at the bottom of the body above, not as a sibling here: as a
            // sibling it took ~17dp from the body, and gating that on bodyOverflows fed back into
            // the measurement deciding it. Same visual, no layout height, no feedback path.
            Spacer(modifier = Modifier.height(16.dp))

            // Submit result message
            submitResult?.let { result ->
                // Keyed on the result, not recomputed per composition: userNotes is read in this
                // same restartable scope, so every keystroke in the notes field recomposes the
                // whole dialog — and this runs several regex passes over a string a TLS or proxy
                // error can make arbitrarily long.
                val resultMessage =
                    remember(result) {
                        when (result) {
                            is CrashReportService.SubmitResult.Success -> {
                                if (result.isNewIssue) {
                                    "Issue created successfully!"
                                } else {
                                    "Added to existing issue."
                                }
                            }

                            is CrashReportService.SubmitResult.Error -> {
                                // The text most likely to end up pasted into a public issue, and it
                                // interpolates a raw exception message.
                                //
                                // sanitizeExceptionMessage, not maskUriParams: the latter redacts
                                // named params inside a `?`/`#` segment, and the case that
                                // motivates sanitizing here has neither — "Request timeout has
                                // expired [url=https://…, …]" passed through verbatim. This is also
                                // what the rest of the window already gets: CrashHandler runs the
                                // exception message and stack trace through the same function
                                // before they reach CrashReport.
                                //
                                // Scope, measured rather than assumed: a host is removed when it
                                // appears *inside a URL* — filePathPattern swallows everything after
                                // the scheme colon, which covers ktor's `[url=…]` messages. A bare
                                // host does not match any location pattern and renders verbatim:
                                // UnknownHostException.getMessage() is just the hostname, so
                                // "Failed to submit crash report: proxy.corp.internal" survives
                                // intact. Harmless for our own public endpoint, not necessarily so
                                // for a corporate proxy — see #109.
                                //
                                // Cost of what it does remove: the endpoint is no longer named
                                // here, only in the log. The diagnostic half survives ("Request
                                // timeout has expired", and request_timeout=… since neither
                                // `request` nor `timeout` marks a secret), which keeps this
                                // narrower than a blunt redaction. A blank message renders
                                // "[no message]" where maskUriParams gave "[empty]".
                                LogSanitizer.sanitizeExceptionMessage(result.message)
                            }
                        }
                    }
                Card(
                    backgroundColor =
                        when (result) {
                            is CrashReportService.SubmitResult.Success -> BossTheme.colors.ok
                            is CrashReportService.SubmitResult.Error -> BossTheme.colors.alert
                        },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Selectable so the visible reason can be copied — pasting it somewhere is
                    // usually the user's next move. Selection only reaches painted text, so the
                    // tail past the ellipsis is not recoverable here; both error paths log the
                    // full exception (CrashReportService), which is where it survives.
                    SelectionContainer {
                        Text(
                            text = resultMessage,
                            fontSize = 13.sp,
                            color = BossTheme.colors.onSignal,
                            // This text is the one part of the footer whose length isn't ours: the
                            // network failures interpolate `e.message` (CrashReportService), which
                            // a TLS or proxy error can make arbitrarily long. Unbounded, it would
                            // grow the footer and squeeze the body — re-creating, one level up, the
                            // exact overflow this layout exists to prevent.
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Clean & Restart button. Gated on the disposition as well as the
                // callback: the caller already passes null for a recoverable crash,
                // but that left the invariant - "wiping the install is never offered
                // as the answer to one plugin misbehaving" - resting entirely on a
                // call site with no test, and a test here could only assert it
                // vacuously. Now it holds however this is called.
                if (onCleanAndRestart != null && recoverablePluginId == null) {
                    Button(
                        onClick = onCleanAndRestart,
                        enabled = !isSubmitting,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.alert,
                                contentColor = BossTheme.colors.onSignal,
                                disabledBackgroundColor = BossTheme.colors.raised,
                                disabledContentColor = BossTheme.colors.textMuted,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Clean Data & Restart")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Dismiss button. For a recoverable crash this is the *recovery* action rather
                // than a decline, so it reads as one and is given the foreground colour - the
                // user who wants their session back must not have to guess that the greyed-out
                // "Don't Send" is the button that keeps it.
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSubmitting,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor =
                                if (recoverablePluginId != null) {
                                    BossTheme.colors.textPrimary
                                } else {
                                    BossTheme.colors.textSecondary
                                },
                        ),
                ) {
                    Text(dismissLabel)
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Report Issue button
                Button(
                    onClick = {
                        isSubmitting = true
                        onSubmittingChanged(true)
                        coroutineScope.launch {
                            // try/finally, and the finally is load-bearing now.
                            // isSubmitting gates all three exits, and the window is
                            // DO_NOTHING_ON_CLOSE, so a submit that threw anywhere -
                            // updateReportWithUserInput, or the plumbing around
                            // submitCrashReport that sits outside its own inner
                            // catch - used to leave the flag set forever and the
                            // crash dialog with no way out but killing the process,
                            // on a machine already in a bad state. Before this
                            // change the close box always worked, so the same throw
                            // was survivable.
                            // Exception, not a narrower type: the point is that
                            // NOTHING escapes and leaves isSubmitting stuck, and the
                            // paths involved reach the network, the filesystem and a
                            // config loader.
                            @Suppress("TooGenericExceptionCaught")
                            val submitted =
                                try {
                                    submitReport(
                                        userNotes = userNotes.takeIf { it.isNotBlank() },
                                        includeLogs = includeLogs,
                                    ).also { submitResult = it }
                                } catch (e: Exception) {
                                    submitResult =
                                        CrashReportService.SubmitResult.Error(
                                            "Failed to submit crash report: ${e.message ?: e.javaClass.simpleName}",
                                        )
                                    null
                                } finally {
                                    isSubmitting = false
                                    onSubmittingChanged(false)
                                }

                            // If successful, call onSubmit after a brief delay
                            if (submitted is CrashReportService.SubmitResult.Success) {
                                kotlinx.coroutines.delay(SUBMIT_CONFIRMATION_MILLIS)
                                onSubmit(userNotes.takeIf { it.isNotBlank() }, includeLogs)
                            }
                        }
                    },
                    enabled = !isSubmitting && submitResult !is CrashReportService.SubmitResult.Success,
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = BossTheme.colors.signal,
                            contentColor = BossTheme.colors.onSignal,
                            disabledBackgroundColor = BossTheme.colors.raised,
                            disabledContentColor = BossTheme.colors.textMuted,
                        ),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BossTheme.colors.textPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Submitting...")
                    } else {
                        Text("Report Issue")
                    }
                }
            }

            // Close button after successful submission. Labelled like the dismiss button above,
            // because it does the same thing: a submitted report is not a reason to lose the
            // session, so post-submit is the same recovery, not a quit.
            if (submitResult is CrashReportService.SubmitResult.Success) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.raised,
                                contentColor = BossTheme.colors.textPrimary,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(if (recoverablePluginId != null) CONTINUE_WITHOUT_PLUGIN_LABEL else "Close")
                    }
                }
            }
        }
    }
}

/**
 * Prepare and send the report, or report why it could not be prepared.
 *
 * Extracted so the submit handler's `try`/`finally` reads as one statement, and so
 * "prepare failed" and "send failed" produce the same shape of result rather than
 * two duplicated reset blocks.
 */
private suspend fun submitReport(
    userNotes: String?,
    includeLogs: Boolean,
): CrashReportService.SubmitResult {
    val updated =
        CrashHandler.updateReportWithUserInput(userNotes = userNotes, includeLogs = includeLogs)
            ?: return CrashReportService.SubmitResult.Error("Failed to prepare report")
    return CrashReportService.submitCrashReport(updated)
}

/** How long the success message stays up before the dialog takes its exit. */
private const val SUBMIT_CONFIRMATION_MILLIS = 2000L

/**
 * The dismiss action's label when the crash is recoverable.
 *
 * Named rather than inlined so the dialog and its test cannot drift: the whole point of the
 * rename is that the button says what it does, and an assertion holding its own copy of the
 * string would keep passing while the button said something else.
 */
internal const val CONTINUE_WITHOUT_PLUGIN_LABEL = "Continue Without Plugin"

/** The dismiss action's label for a fatal host crash, where dismissing really does end the app. */
internal const val DONT_SEND_LABEL = "Don't Send"

/** Present only while the body is clipping content; see `isClipping`. */
internal const val BODY_SCROLLBAR_TAG = "crash-dialog-body-scrollbar"

/** The rule marking a clipped body edge. Overlaid, so it must never consume layout height. */
internal const val BOUNDARY_RULE_TAG = "crash-dialog-boundary-rule"

/** The bounded stack-trace viewport. Tagged so a test can measure the cap itself, not its inner content. */
internal const val TRACE_PANE_TAG = "crash-dialog-trace-pane"

/**
 * Height cap on the stack-trace viewport. Bounded so a deep trace takes its own overflow instead of
 * making the body an endless scroll. Shared with the test rather than duplicated, so moving the cap
 * can't leave an assertion validating the old number.
 *
 * Do not remove it as cosmetic: this pane's `verticalScroll` sits inside the body's, which hands
 * down an unbounded max height. The `heightIn` is what bounds it, and without it the dialog throws
 * "Vertically scrollable component was measured with an infinity maximum height constraints"
 * (confirmed by deleting it — eight tests fail on that, not on a size assertion).
 */
internal val TRACE_PANE_MAX_HEIGHT = 200.dp

/** Present only while the stack trace pane is clipping content; see `isClipping`. */
internal const val TRACE_SCROLLBAR_TAG = "crash-dialog-trace-scrollbar"

/**
 * True when this region has measured content taller than its viewport.
 *
 * Deliberately not `maxValue > 0`: [ScrollState.maxValue] is initialised to [Int.MAX_VALUE] and
 * only assigned during the measure pass, so the bare comparison reports overflow on the first
 * composition of any content, however short.
 */
private fun ScrollState.isClipping(): Boolean = maxValue in 1 until Int.MAX_VALUE
