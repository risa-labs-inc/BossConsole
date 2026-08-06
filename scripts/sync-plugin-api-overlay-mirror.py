"""Sync the plugin-ui-core overlay files into the boss-plugin-api copy.

WHOLE FILE, deliberately. A previous sync spliced only from the "Anchored popup" marker
onwards, so fixes inside ScrimmedModalContent - the retrying arm timer, the Exit
press-clearing, the scrim's canFocus - never reached the api copy, which is the copy that
actually EXECUTES on an older host via ApiClassLoader. Substituting a known list is the
only shape that fails loudly when the host file changes shape.
"""
import pathlib, sys

H = pathlib.Path("plugin-platform/plugin-ui-core/src/commonMain/kotlin/ai/rever/boss/plugin/ui")
A = pathlib.Path("/Users/kshivang/Development/Boss/.worktrees/boss-plugin-api-overlay-dialogs/src/main/kotlin/ai/rever/boss/plugin/ui")

# BossOverlayHost is identical in both copies.
(A / "BossOverlayHost.kt").write_text((H / "BossOverlayHost.kt").read_text())

s = (H / "BossDialog.kt").read_text()

# The api package predates the design-system tokens, so BossAlertDialog's body uses the
# values they resolve to. Every substitution must fire; a miss means the host file moved
# and this script needs updating rather than silently producing a divergent copy.
subs = [
    ("    val colors = BossTheme.colors\n    val space = BossTheme.space\n", ""),
    ("shape = shape ?: BossTheme.radius.dialogShape,", "shape = shape ?: RoundedCornerShape(DIALOG_RADIUS),"),
    ("color = backgroundColor.takeOrElse { colors.panel },", "color = backgroundColor.takeOrElse { BossThemeColors.SurfaceColor },"),
    ("contentColor = contentColor.takeOrElse { colors.textPrimary },", "contentColor = contentColor.takeOrElse { BossThemeColors.TextPrimary },"),
    ("Column(modifier = Modifier.padding(space.xl)) {", "Column(modifier = Modifier.padding(CARD_PADDING)) {"),
    ("CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {", "CompositionLocalProvider(LocalContentColor provides BossThemeColors.TextPrimary) {"),
    ("ProvideTextStyle(BossTheme.type.title, title)", "ProvideTextStyle(TITLE_STYLE, title)"),
    ("CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {", "CompositionLocalProvider(LocalContentColor provides BossThemeColors.TextSecondary) {"),
    ("ProvideTextStyle(BossTheme.type.body, text)", "ProvideTextStyle(BODY_STYLE, text)"),
    ("Spacer(Modifier.height(space.md))", "Spacer(Modifier.height(TITLE_TEXT_GAP))"),
    ("Spacer(Modifier.height(space.xl))", "Spacer(Modifier.height(CARD_PADDING))"),
    ("Spacer(Modifier.width(BossTheme.space.sm))", "Spacer(Modifier.width(BUTTON_GAP))"),
]
missing = [old for old, _ in subs if old not in s]
if missing:
    sys.exit("host file changed shape; these no longer match:\n  " + "\n  ".join(repr(m) for m in missing))
for old, new in subs:
    s = s.replace(old, new)

tokens = '''// ---------------------------------------------------------------------------
// Design-system stand-ins.
//
// The host's copy reads BossTheme.space / .radius / .type. This copy of the package predates those
// tokens, so the values they resolve to are inlined. This body is NOT dead: ApiClassLoader serves
// these types from the jar on a host that lacks them compiled in, so it is what runs on the fallback
// path. Colors go through BossThemeColors, the indirection layer the rest of this package uses.
// ---------------------------------------------------------------------------

/** BossRadii.dialog */
private val DIALOG_RADIUS: Dp = 8.dp

/** BossSpacing.xl */
private val CARD_PADDING: Dp = 24.dp

/** BossSpacing.md */
private val TITLE_TEXT_GAP: Dp = 12.dp

/** BossSpacing.sm */
private val BUTTON_GAP: Dp = 8.dp

/** bossTypography().title */
private val TITLE_STYLE = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

/** bossTypography().body */
private val BODY_STYLE = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp)

'''
anchor = "/** Width of a BOSS alert card, matching the house confirmation dialog. */"
assert anchor in s, "AlertWidth anchor missing"
s = s.replace(anchor, tokens + anchor, 1)

extra = ["import androidx.compose.foundation.shape.RoundedCornerShape\n",
         "import androidx.compose.ui.text.TextStyle\n",
         "import androidx.compose.ui.text.font.FontWeight\n",
         "import androidx.compose.ui.unit.sp\n"]
for imp in extra:
    if imp not in s:
        i = s.index("import "); s = s[:i] + imp + s[i:]

# Sort the import block, which review #2 flagged as unsorted relative to its siblings.
lines = s.split("\n")
first = next(i for i, l in enumerate(lines) if l.startswith("import "))
last = max(i for i, l in enumerate(lines) if l.startswith("import "))
imports = sorted({l for l in lines[first:last + 1] if l.startswith("import ")})
lines[first:last + 1] = imports
s = "\n".join(lines)

(A / "BossDialog.kt").write_text(s)
print("synced whole file; %d substitutions applied, imports sorted" % len(subs))
