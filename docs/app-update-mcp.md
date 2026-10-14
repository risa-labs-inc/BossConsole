# Application update MCP tools

The application exposes its existing updater through four tools:

| Tool | Action |
| --- | --- |
| `app_update_status` | Read current/latest version, updater state, progress and errors. |
| `app_update_check` | Start a check against the configured release sources. |
| `app_update_download` | Download the update currently offered by the updater. |
| `app_update_install` | Install the staged artifact; requires its exact `version`. |

Check, download and install return an `accepted` response immediately. Poll
`app_update_status` until `operation` is absent, then inspect `state` and `error`.
An accepted response means the operation was queued, not that an update installed.
Only one MCP update operation runs at a time. Status remains available throughout.

Typical sequence: check → poll until `available` → download → poll until
`ready_to_install` → install with `{"version":"<install_version>"}`.
An `up_to_date` result needs no download. An error is returned in status if a
background operation fails. Tools never accept an arbitrary URL or installer path.

Installation uses the same signature/checksum, platform and permission behavior as
the UI updater. It may request OS permission, quit/relaunch the application, and
disconnect MCP. Reconnect afterward and read `current_version` to verify completion.
`restart_required` means the existing installer has requested restart, not proof
that the new application has launched. No updater is run by the regression tests.

BossConsole registers the tools in its host MCP registry. Status is read-only;
check/download/install use the existing mutation approval policy, and installation
has HIGH risk. The terminal-tab bridge exposes them as normal host tools; no
boss-plugin-api or terminal-tab change is required.
