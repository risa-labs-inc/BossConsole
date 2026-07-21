# BOSS Design System — "Operator's Console"

One visual language shared by **BossConsole** (the host) and **BossTerm** (the
terminal surface). This is the canonical spec; the [**visual styleguide**](design-system.html)
(a self-contained HTML reference — open it in a browser) is the companion reference,
and the tokens themselves ship as code (see [Where it lives](#where-it-lives)).

---

## 1. Direction

Three rules drive every token. When a choice is unclear, return here.

1. **The cell is the unit.** The terminal character cell (~8.4 × 17 px at 14sp
   MesloLGS) is the grid the whole UI snaps to. Chrome borrows the terminal's
   discipline — 8.dp base spacing, hairline borders, high density — not the
   other way around.
2. **One amber signal.** Amber `#F2A93B` means *live / active / now*: the
   primary action, the focused field, the selected tab, the cursor. Nothing else
   competes for it. Cyan `#56C7E0` carries links and data.
3. **Quiet everywhere else.** Surfaces separate by tint, not shadow. Borders are
   hairlines. Density is high and calm so your output is the loudest thing on
   screen.

The deliberate, non-default choices: amber (not blue) is the primary action
color, and **monospace is the display/brand voice**, not just code. Both are
grounded in a terminal-first product.

---

## 2. Color

Host chrome and the terminal share one floor (`ink`), so a panel and the shell
it wraps read as one continuous surface. Amber and cyan are the only saturated
colors that appear in chrome.

### Surface & ink

| Token | Hex | Role |
|-------|-----|------|
| `ink` | `#0E1217` | Base floor — host **and** terminal |
| `panel` | `#161D26` | Chrome / card / sidebar |
| `raised` | `#1E2731` | Menus, popovers, hover |
| `line` | `#2A3744` | Hairline border / divider |
| `lineStrong` | `#3A4B5C` | Input edge / strong border |
| `chalk` | `#E9EEF3` | Primary text |
| `mist` | `#8593A3` | Secondary text |
| `muted` | `#5C6977` | Tertiary / disabled |

### Signals

| Token | Hex | Role |
|-------|-----|------|
| `signal` | `#F2A93B` | Amber — live / active / primary action |
| `signalDim` | `#C98A2E` | Pressed / variant |
| `signalWash` | `#2A2113` | Faint amber hover fill on `ink` |
| `data` | `#56C7E0` | Cyan — links / info / data |
| `ok` | `#6FD08C` | Success / clean exit |
| `warn` | `#F0B429` | Warning |
| `alert` | `#F2685F` | Error / destructive |
| `onSignal` | `#1A1206` | Ink that sits on an amber fill |
| `onData` | `#06222A` | Ink that sits on a cyan fill |

### Terminal — "BOSS Operator" theme

| Property | Hex |
|----------|-----|
| foreground | `#D7DEE6` |
| background | `#0E1217` (shared `ink`) |
| cursor | `#F2A93B` (the signature) |
| cursorText | `#0E1217` |
| selection | `#21405A` |
| searchMatch | `#F0B429` |
| hyperlink | `#56C7E0` |

**ANSI 16** (tuned to sit calmly on `ink`):

| # | Color | Hex | | # | Bright | Hex |
|---|-------|-----|---|---|--------|-----|
| 0 | black | `#15202B` | | 8 | brightBlack | `#3A4B5C` |
| 1 | red | `#F2685F` | | 9 | brightRed | `#FF8A80` |
| 2 | green | `#6FD08C` | | 10 | brightGreen | `#8FE0A6` |
| 3 | yellow | `#F2A93B` | | 11 | brightYellow | `#FFC560` |
| 4 | blue | `#5C9FE0` | | 12 | brightBlue | `#82B7F0` |
| 5 | magenta | `#C792EA` | | 13 | brightMagenta | `#DDB0F5` |
| 6 | cyan | `#56C7E0` | | 14 | brightCyan | `#7FD9EE` |
| 7 | white | `#C7D1DB` | | 15 | brightWhite | `#E9EEF3` |

---

## 3. Typography

Monospace is the brand voice — display, eyebrows, labels, every number and path.
A humanist sans carries running UI copy where reading speed matters. The app
bundles **MesloLGS Nerd Font** for the mono role.

| Style | Family | Size | Weight | Tracking | Use |
|-------|--------|------|--------|----------|-----|
| `displayLarge` | mono | 28 | SemiBold | −0.5 | Hero headings |
| `displaySmall` | mono | 22 | SemiBold | — | Section headings |
| `title` | sans | 16 | SemiBold | — | Panel / dialog titles |
| `body` | sans | 13 | Normal | — | Running UI copy |
| `data` | mono | 14 | Normal | — | Terminal, code, paths, metrics |
| `label` | mono | 11 | SemiBold | +1.5, UPPER | Eyebrows / section labels |
| `micro` | mono | 10 | Medium | +1.0, UPPER | Captions / status |

---

## 4. Space, shape, motion

**Spacing** — 8.dp base, 4.dp half-step:
`hairline 2` · `xs 4` · `sm 8` · `md 12` · `lg 16` · `xl 24` · `xxl 32`.
`cellWidth 8.4` / `cellHeight 17` mirror the terminal char cell.

**Radius** (small radii read as a precision instrument):
`grid 0` (terminal) · `input 3` · `button 5` · `card 5` · `dialog 8`.

**Elevation** — tint first; shadow only for true popovers:
`floor` (ink) · `panel` (tint + 1px line) · `popover 8.dp` (menus / dialogs).

**Motion** — `instant 0ms` (cursor, key echo) · `fast 90ms` (hover, press) ·
`base 160ms` (menus, panels) · `cursorBlink 530ms`. Easing
`cubic-bezier(0.2, 0, 0, 1)`. Honor `prefers-reduced-motion` /
the OS reduce-motion setting: the cursor stops blinking, panels cut instead of slide.

---

## 5. Components

The active item always wears amber. A few canonical specs:

- **Tab** — selected tab shows a 4.dp bottom marker: `signal` when focused,
  `line` when not (the system's **signature** element).
- **Button** — primary = `signal` fill + `onSignal` text, used once per view;
  secondary = transparent + `lineStrong` border; ghost = transparent;
  destructive = `alert`, outlined until hover then committed.
- **Text field** — `ink` fill, `lineStrong` border, focus ring `signal`.
- **Context menu** — `raised` surface, `lineStrong` border, hover = `signalWash`.
- **Dialog** — `panel`, `dialog` radius, 24.dp padding, popover elevation.
- **Scrollbar** — thumb `#ffffff30` over a 12%-white track; search hits = amber
  markers; command-block status = `ok` / `alert` gutter markers.

---

## 6. Consuming the tokens (Compose)

Inside a `BossTheme { … }` scope, read tokens via the `BossTheme` accessor
object — the same pattern as `MaterialTheme.colors`:

```kotlin
import ai.rever.boss.plugin.ui.BossTheme

@Composable
fun Example() {
    val colors = BossTheme.colors
    val space = BossTheme.space
    val radii = BossTheme.radius

    Surface(color = colors.panel, shape = RoundedCornerShape(radii.card)) {
        Text("Live", color = colors.signal, modifier = Modifier.padding(space.md))
    }
}
```

### Worked example — `BossTabButton`

The selected-tab marker is the design system's signature, so it's the reference
migration (`composeApp/.../components/buttons/BossTabButton.kt`):

```kotlin
val colors = BossTheme.colors          // captured once at the top of the composable
// …
Box(
    modifier = Modifier
        .height(4.dp)
        .background(
            // amber signal when focused, quiet line when not
            color = if (isFocused) colors.signal else colors.line,
            shape = RoundedCornerShape(2.dp),
        ),
)
```

The same pattern is applied in `BossActionButton` (selected background →
`colors.signal`, tooltip → `colors.raised`), `ConfirmationDialog` (destructive →
`colors.alert`, warning → `colors.warn`, surface/radii via tokens), and
`ContextMenu` (item text → `colors.textPrimary`, arrows/indicators →
`colors.textSecondary`).

### Fonts

The mono brand voice is **wired to the bundled MesloLGS Nerd Font**: the host's
`BossTheme` re-export (`composeApp/.../components/misc/BossTheme.kt`) builds a
`FontFamily` from `Res.font.meslolgs_nf_*` and injects it via
`bossTypography(mono = …)`, so all four theme roots (`BossApp`, `main`,
`AuthScreenContainer`, `SettingsWindow`) render `BossTheme.type.*` in the real
face. Outside the host (e.g. plugins) the type scale falls back to the platform
generic monospace. The sans role is the platform default — bundle Inter and pass
`bossTypography(mono = …, sans = …)` to brand it further.

### Migration note

`BossColors` (the legacy flat object and its top-level aliases like
`BossDarkAccent`) now **delegate to `BossPalette`** with names unchanged — so
existing components re-skin automatically with zero edits. New and touched code
should prefer the semantic `BossTheme.colors.*` accessors. Spacing, radius,
elevation, and motion tokens are consumed by the migrated components above;
migrate the rest opportunistically as you touch them. Watch the accessor names:
the semantic scheme uses `textPrimary` / `textSecondary` / `textMuted`, not the
raw-palette names `chalk` / `mist` / `muted`.

---

## Where it lives

| Concern | Location |
|---------|----------|
| Host tokens (source of truth) | `plugin-platform/plugin-ui-core/src/commonMain/kotlin/ai/rever/boss/plugin/ui/BossDesignSystem.kt` |
| Legacy color object (delegates to `BossPalette`) | `…/ui/BossColors.kt` |
| `BossTheme()` composable (provides token locals) | `…/ui/BossTheme.kt` |
| composeApp re-exports + MesloLGS font injection | `composeApp/.../components/misc/BossTheme.kt` |
| Terminal theme + ANSI palette | BossTerm `…/settings/theme/BuiltinThemes.kt`, `BuiltinColorPalettes.kt` (id `boss-operator`, now the default) |
| Terminal defaults | BossTerm `…/settings/TerminalSettings.kt` (`activeThemeId = "boss-operator"`) |

**Defaults changed:** BossTerm `DEFAULT_THEME_ID` and `TerminalSettings.activeThemeId`
are now `boss-operator`, and the default terminal fg/bg are `#D7DEE6` / `#0E1217`.
Existing saved settings keep their current theme; only fresh installs pick up the
new default.
