# Secret Auto-Fill Integration with Fluck Browser

**Issue**: #56
**Status**: shipped, and since moved out of this repository into a browser plugin
**Version**: 8.12.20+

## Overview

This feature integrates BOSS's secret management system with the Fluck browser, enabling automatic credential filling similar to password manager browser extensions. Users can right-click on login form fields to access and auto-fill stored credentials.

**Read the Architecture section before this one if you came here to change the code.** What the
user sees is still as described below, but the implementation no longer lives here: the host
detects the focused field and publishes it through the plugin API, and a plugin owns the menu,
the matching and the fill. Everything except Architecture, the Domain Matching checklist under
Testing, and the Code Files list under References was written against the original host-side
implementation, so treat file names, line numbers and internal details in those other sections
as historical: the fill-modes, clipboard and framework-compatibility bullets under Features
describe that original implementation, and what the installed plugin offers today is the
plugin's to define.

## Features

### 1. Context Menu Integration
- **Right-click detection** on form fields (username, password, email)
- **Intelligent field detection** using multiple heuristics
- **Domain-based secret matching** with exact and dot-boundary scoring
- **Visual indicators** for matched secrets

### 2. Auto-Fill Capabilities
- **Smart field detection** for username/email and password fields
- **Framework compatibility** (React, Vue, Angular) through native input setters
- **Multiple fill modes**: Both fields, username only, password only
- **Clipboard operations** for copying credentials

### 3. Secret Management
- **Browse all secrets** via searchable dialog
- **Quick secret creation** pre-filled with current website
- **Auto-reload** after secret creation
- **Search and filter** by website, username, or tags

## Architecture

The feature is no longer implemented in this repository. The host detects which form field
the user right-clicked and publishes it across the plugin API; a browser plugin owns the menu,
the secret matching, the selection dialog and the fill. Five of the eight Kotlin files this
document used to describe are gone, and two of the three survivors have no production caller.

### What this repository still owns

| Piece | Where | State |
|---|---|---|
| focused-field detection | `BrowserHandleImpl.getFormFieldInfoFromJS` (`composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserHandleImpl.kt`) | live, the only path that runs |
| the field-type heuristics on that result | `FormFieldInfoJson.kt` (`composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/FormFieldInfoJson.kt`) | live, `FormFieldInfoJsonTest` pins the precedence |
| the published field type | `FormFieldInfo` / `FormFieldType` in `plugin-platform/plugin-api-browser/src/commonMain/kotlin/ai/rever/boss/plugin/browser/BrowserHandle.kt` | live |
| the menu carrier | `BrowserContextMenuInfo.formFieldInfo`, same file | live |
| injected page helper | `FormFieldDetector.injectFormDetectionScript` (`.../plugin/browser/FormFieldDetector.kt`) | injected on every navigation; its readers are dead, see below |
| domain scoring | `WebsiteMatchingUtil` (`composeApp/src/commonMain/kotlin/ai/rever/boss/utils/WebsiteMatchingUtil.kt`) | no production caller; kept honest by four regression tests |
| the old view model | `BrowserSecretIntegrationViewModel` (`.../components/plugin/tab_types/fluck/`) | declared, never constructed |

### How a right-click reaches a plugin today

1. `BrowserHandleImpl` handles the native context-menu event. Chromium reports the click target,
   so nothing is injected for this.
2. The lookup runs only when `BrowserContextMenuInfo.isEditable` is true, and that flag is
   `isMainFrame && contentTypes.contains(EDITABLE)`: Chromium resolves it against the click
   target, and it is main-frame-only, so a right-click on an input inside an iframe never
   attempts the lookup at all - the menu arrives with `formFieldInfo = null` not because the
   lookup failed but because it was never tried (the `iFrame support` item under Technical
   Improvements is the open work). When it does run, `getFormFieldInfoFromJS` executes a
   self-contained script over `document.activeElement` and returns a `FormFieldInfo`, or null
   when the click was not on an `INPUT` or `TEXTAREA`. It reads
   `document.activeElement` directly and does **not** use the globals
   `FormFieldDetector` installs. There are more null cases than that: the lookup races a
   500 ms timeout, and on timeout the menu is delivered with `formFieldInfo = null` - the
   menu opens without the auto-fill entries rather than never opening; the same happens
   if the script throws or the frame is already gone.
3. The result is attached as `BrowserContextMenuInfo.formFieldInfo` and delivered to whichever
   plugin registered the context-menu callback.
4. Everything after that - matching a stored secret to the site, drawing the menu, the selection
   dialog, and writing the value into the page - belongs to the browser plugin, which lives in
   the `boss-plugin-fluck-browser` repository rather than here.

The KDoc on `getFormFieldInfoFromJS` records the one behaviour worth knowing at this boundary: it
describes whatever has focus, so a page that calls `preventDefault()` on mousedown can leave focus
elsewhere and the menu then describes the previously focused field.

### Two things that are present but not fully wired

`FormFieldDetector` installs `window.__BOSS_FOCUSED_FIELD` and `window.__BOSS_GET_FOCUSED_FIELD`
on every main-frame navigation, and defines `getCurrentFocusedField` and `findAllFormFields` to
read them. **Nothing in this repository calls either function**, and the live path above does not
use the globals. The nested `FormFieldDetector.FormFieldInfo` and `FormFieldDetector.FieldType` are
likewise a separate pair of types from the published `FormFieldInfo` and `FormFieldType` that
plugins actually receive; do not confuse the two when reading this file.

`WebsiteMatchingUtil` is reached only from `BrowserSecretIntegrationViewModel`, which nothing
constructs. Its scoring rules are still pinned by `WebsiteMatchingAuthorRegressionTest`,
`WebsiteMatchingBoundaryRegressionTest`, `WebsiteMatchingHostnameRegressionTest` and
`WebsiteMatchingUtilTest`, so the behaviour is specified even though no caller depends on it.

### Gone

`FormFieldInjector.kt`, `SecretContextMenuBuilder.kt`, `SecretSelectionDialog.kt`,
`SecretDialogs.kt` and `JxBrowserCompose.kt` no longer exist. Earlier revisions of this document
described their contents and quoted line numbers inside `JxBrowserCompose.kt`; that wiring, the
host-side dialogs and the host-side fill went with them when the feature moved into a plugin.

## User Workflow

### Basic Usage

1. **Navigate** to a website with login form
2. **Right-click** on username/email field
3. **See matched secrets** in context menu
4. **Click a secret** to auto-fill both fields
5. **Submit** the form

### Adding New Secret

**Method 1: From Context Menu**
1. Right-click on form field
2. Click "Add New Secret"
3. Enter username and password (website pre-filled)
4. Save

**Method 2: From "Show All Secrets"**
1. Right-click on form field
2. Click "Show All Secrets..."
3. Click "Add New Secret" button
4. Enter credentials
5. Save

### Searching Secrets

1. Right-click on any form field
2. Click "Show All Secrets..."
3. Use search bar to filter by:
   - Website name
   - Username
   - Tags
   - Notes

## Security Considerations

### Data Protection
- **Encrypted storage** via SecretService (server-side pgcrypto, decrypted on the server)
- **No plaintext** credential storage in memory longer than necessary
- **Secure transmission** to browser via HTTPS Supabase connection

### JavaScript Isolation
- **Minimal JS injection** for field detection only
- **No credential data** stored in JavaScript context
- **Event-based** filling to prevent interception

### Access Control
- **User-scoped secrets** only
- **RBAC integration** via SecretService
- **Session validation** required for all operations

## Testing

### Manual Testing Checklist

**Basic Auto-Fill**:
- [ ] Right-click on username field shows secret menu
- [ ] Right-click on password field shows secret menu
- [ ] Matched secrets appear at top of menu
- [ ] Clicking a secret fills both username and password
- [ ] Filled values trigger form validation

**Domain Matching** (these are `WebsiteMatchingUtil`'s scoring rules; the matching the
app actually ships is the browser plugin's):
- [ ] Exact domain match (google.com)
- [ ] Subdomain match (login.google.com)
- [ ] A secret saved for accounts.google.com is NOT suggested on login.google.com
- [ ] A secret saved for google.com IS suggested on login.google.com (save it against the
      parent domain to share it with those subdomains)
- [ ] Sibling hosts under a two-part TLD do not match: a secret saved for example.co.uk is
      NOT suggested on google.co.uk (pinned by `WebsiteMatchingBoundaryRegressionTest`);
      there is no registrable-domain or public-suffix guessing
- [ ] A secret saved against a broad suffix (co.uk) IS suggested on every host under it:
      the scorer does not validate public suffixes, so save it against the specific host
- [ ] Localhost handling

**Secret Management**:
- [ ] "Show All Secrets" opens dialog
- [ ] Search filters secrets correctly
- [ ] Click to fill from dialog works
- [ ] "Add New Secret" pre-fills website
- [ ] Quick create saves and reloads secrets

**Framework Compatibility**:
- [ ] Works on React forms (Facebook, GitHub)
- [ ] Works on Vue forms
- [ ] Works on Angular forms
- [ ] Works on vanilla HTML forms
- [ ] Triggers onChange events properly

### Test Websites

**Recommended Sites**:
1. **GitHub** (github.com/login) - React, autocomplete attributes
2. **Google** (accounts.google.com) - Multiple subdomains
3. **Facebook** (facebook.com) - React, dynamic forms
4. **Twitter/X** (twitter.com) - Modern web standards
5. **Localhost** (localhost:3000) - Development testing

## Troubleshooting

### Common Issues

**Issue**: Secret menu doesn't appear
- **Check**: Secrets loaded (`secretViewModel.state.allSecrets.size`)
- **Check**: Form field detected (`focusedFieldInfo != null`)
- **Check**: JavaScript console for errors

**Issue**: No secrets match current website
- **Solution**: Check domain extraction logic
- **Solution**: Verify secret's website field format
- **Solution**: Use "Show All Secrets" and search manually

**Issue**: Auto-fill doesn't work
- **Check**: Field detection strategies
- **Check**: JavaScript injection successful
- **Check**: Browser console for errors
- **Solution**: Try different fill modes (username only, password only)

**Issue**: Filled values don't trigger validation
- **Solution**: Ensure event dispatching is working
- **Solution**: Check framework-specific input setters
- **Solution**: Manual interaction may be required for some frameworks

## Performance Considerations

### Optimization Strategies

1. **Lazy Loading**: Secrets loaded once on initialization
2. **Caching**: Matched secrets cached per domain
3. **Debouncing**: URL change detection debounced
4. **Pagination**: Secrets loaded in batches (limit: 1000)

### Memory Usage

- **ViewModel lifecycle**: Tied to browser tab
- **Secret storage**: In-memory list, encrypted at rest
- **JavaScript context**: Minimal footprint (detection script only)

## Future Enhancements

### Planned Features
- [ ] Submenu for advanced fill options (username only, password only, copy)
- [ ] Auto-submit after fill (optional)
- [ ] Credential strength indicator
- [ ] Password generator integration
- [ ] Multiple account support (switch between accounts)
- [ ] Browser extension-style overlay UI
- [ ] Keyboard shortcuts (Ctrl+Shift+L to fill)
- [ ] Credit card auto-fill
- [ ] Address auto-fill
- [ ] TOTP/2FA code display

### Technical Improvements
- [ ] Field detection accuracy improvements
- [ ] Better React/Vue detection
- [ ] Shadow DOM support
- [ ] iFrame support
- [ ] Custom autocomplete attribute support

## Related Issues

- **#56**: Secret Access Integration with main panel (this implementation)
- **#80**: User-level secret list plugin (dependency)
- **#81**: Secret sharing with users and roles (dependency)

## References

### Code Files

Still here:

- `composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserHandleImpl.kt` - the live path, `getFormFieldInfoFromJS`
- `composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/FormFieldInfoJson.kt` - parses the field info that path returns, `FormFieldInfoJsonTest` pins the heuristics
- `plugin-platform/plugin-api-browser/src/commonMain/kotlin/ai/rever/boss/plugin/browser/BrowserHandle.kt` - `FormFieldInfo`, `FormFieldType`, `BrowserContextMenuInfo.formFieldInfo`
- `composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/FormFieldDetector.kt` - injected page helper, readers unused
- `composeApp/src/commonMain/kotlin/ai/rever/boss/utils/WebsiteMatchingUtil.kt` - no production caller
- `composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/tab_types/fluck/BrowserSecretIntegrationViewModel.kt` - never constructed

Gone, and named here only so a search for them stops at this line rather than in the history:
`FormFieldInjector.kt`, `SecretContextMenuBuilder.kt`, `SecretSelectionDialog.kt`,
`SecretDialogs.kt`, `JxBrowserCompose.kt`.

The rest of the implementation is in the browser plugin, in the `boss-plugin-fluck-browser` repository.

### External Dependencies
- **JxBrowser**: Browser rendering and JavaScript execution; the pinned version is in `gradle/libs.versions.toml`
- **Supabase**: Secret storage and retrieval
- **Compose Desktop**: UI framework

---

**Last Updated**: 2026-09-22
**Author**: Claude Code
**Reviewer**: swept 2026-09-22 (codeq)
