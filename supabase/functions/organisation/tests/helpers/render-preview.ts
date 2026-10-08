/**
 * Render every page, in every state that changes its markup, so a human (or a
 * screenshot) can look at them.
 *
 * Not a test - a development harness. The views are pure functions of their
 * data, so they can be rendered without a database, a session or a running
 * function, which is the only reason a visual check is cheap enough to do on
 * every styling change.
 *
 * The stylesheet is the thing this exists for. `deno check` and the unit tests
 * both pass on CSS that renders as an unreadable mess: neither one can see that
 * a colour fails contrast, that a table overflows its card, or that a heading
 * disappeared into its own eyebrow treatment. Those only show up on a screen.
 *
 *   deno run --allow-write --allow-read tests/helpers/render-preview.ts <outDir>
 */

import { adminPage, inviteOnlyPage } from "../../views/admin.ts"
import { orgPage } from "../../views/org.ts"
import { pluginPage } from "../../views/plugin.ts"
import { invalidInvitePage, joinPage } from "../../views/join.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE } from "../../views/error.ts"
import type { OrgDetail, OrgDomain, OrgInvite, OrgMember, OrgRole } from "../../services/org.ts"
import type { OrgPluginSummary, PluginDetail } from "../../services/plugin.ts"

const NONCE = "previewnonce"
/** A plausible one-time invite URL. Only its shape matters to the rendering. */
const INVITE =
  "https://api.risaboss.com/functions/v1/organisation/join/hK3nQ8mZ0pW7xR4tL9vB2cN6dF1sA5gJ"
const BASE = "/functions/v1/organisation"

const org: OrgDetail = {
  id: "11111111-1111-1111-1111-111111111111",
  slug: "risa_labs",
  name: "Risa Labs",
  description: "The people building BOSS. Ask an administrator if you need access to something.",
  visibility: "public",
  join_policy: "request_to_join",
  is_system: false,
  owner_email: "shivang@risalabs.ai",
  member_count: 89,
  pending_count: 2,
  primary_domain: "risalabs.ai",
  website: "https://risalabs.ai",
  is_owner: true,
  is_admin: true,
  can_publish: true,
  publish_policy: "admins",
}

const members: OrgMember[] = [
  {
    user_id: "u1",
    email: "shivang@risalabs.ai",
    status: "active",
    joined_at: "2026-01-14T10:00:00Z",
    requested_at: null,
    request_message: null,
    join_source: "seed",
    is_owner: true,
    is_admin: true,
    roles: ["risa_labs_admin"],
  },
  {
    user_id: "u2",
    email: "aamer@risalabs.ai",
    status: "active",
    joined_at: "2026-03-02T10:00:00Z",
    requested_at: null,
    request_message: null,
    join_source: "domain",
    is_owner: false,
    is_admin: true,
    roles: ["risa_labs_admin", "risa_labs_user"],
  },
  {
    user_id: "u3",
    email: "someone-with-a-rather-long-address@risalabs.ai",
    status: "active",
    joined_at: "2026-06-21T10:00:00Z",
    requested_at: null,
    request_message: null,
    join_source: "invite",
    is_owner: false,
    is_admin: false,
    roles: ["risa_labs_user"],
  },
  {
    user_id: "u4",
    email: "newcomer@example.com",
    status: "pending",
    joined_at: null,
    requested_at: "2026-08-05T10:00:00Z",
    request_message: "Joining the platform team next week.",
    join_source: "request",
    is_owner: false,
    is_admin: false,
    roles: [],
  },
]

const roles: OrgRole[] = [
  {
    role_id: "r1",
    role_name: "risa_labs_admin",
    description: "Full control of the organisation.",
    kind: "admin",
    member_count: 2,
    permissions: ["organisation.read", "organisation.admin", "plugins.create", "plugins.publish"],
  },
  {
    role_id: "r2",
    role_name: "risa_labs_user",
    description: null,
    kind: "user",
    member_count: 87,
    permissions: ["organisation.read"],
  },
]

const domains: OrgDomain[] = [
  {
    domain_id: "d1",
    domain: "risalabs.ai",
    is_primary: true,
    verified: true,
    verified_at: "2026-02-01T10:00:00Z",
    dns_record_type: "TXT",
    dns_record_name: "_boss-verify.risalabs.ai",
    // The real prefix. It was "boss-verify=" here, which the RPC never emits and
    // extractToken would reject - a preview that could not survive a real Verify.
    dns_record_value: "boss-org-verification=6f2a9c31d7b84e05",
    // Verified and with people left to adopt, so the preview shows the button.
    addable_user_count: 12,
  },
  {
    domain_id: "d2",
    domain: "risaboss.com",
    is_primary: false,
    verified: false,
    verified_at: null,
    dns_record_type: "TXT",
    dns_record_name: "_boss-verify.risaboss.com",
    dns_record_value: "boss-org-verification=1c7e40ab99f3ad2",
    // Unverified rows always carry 0: the RPC does not compute the count for them.
    addable_user_count: 0,
  },
]

const invites: OrgInvite[] = [
  {
    invite_id: "i1",
    token_prefix: "hK3nQ8",
    label: "Platform team",
    role_id: "r2",
    role_name: "risa_labs_user",
    max_uses: 25,
    uses: 4,
    expires_at: "2026-09-01T10:00:00Z",
    revoked_at: null,
    created_by_email: "shivang@risalabs.ai",
    is_live: true,
    inviter_admin: true,
  },
  {
    invite_id: "i2",
    token_prefix: "zP0mR2",
    label: null,
    role_id: null,
    role_name: null,
    max_uses: null,
    uses: 12,
    expires_at: "2026-07-01T10:00:00Z",
    revoked_at: "2026-06-20T10:00:00Z",
    created_by_email: "aamer@risalabs.ai",
    is_live: false,
    inviter_admin: true,
  },
  {
    invite_id: "i3",
    token_prefix: "qW9sT4",
    label: "Hiring freeze",
    role_id: null,
    role_name: null,
    max_uses: 20,
    uses: 5,
    expires_at: "2026-12-01T10:00:00Z",
    revoked_at: null,
    created_by_email: "aamer@risalabs.ai",
    // Not expired, but the inviter lost admin: the link stops admitting
    // until they are re-promoted -- its own pill state, Revoke intact.
    is_live: true,
    inviter_admin: false,
  },
]

/**
 * Enough rows to see the section as a reader meets it: a restricted one beside public ones, so
 * the visibility column has something to distinguish, and one long id to check the table scrolls
 * inside its card rather than widening the page.
 */
const plugins: OrgPluginSummary[] = [
  {
    plugin_id: "ai.rever.boss.plugin.dynamic.codexglm",
    display_name: "Codex GLM",
    description: "RISA Codex GLM provider",
    icon_url: null,
    visibility: "public",
    published: true,
    verified: true,
  },
  {
    plugin_id: "ai.rever.boss.plugin.dynamic.medicalnecessity",
    display_name: "Medical Necessity",
    description: "Clinical review workflow",
    icon_url: null,
    visibility: "org",
    published: true,
    verified: false,
  },
  {
    plugin_id: "ai.rever.boss.plugin.dynamic.finance",
    display_name: "Finance",
    description: null,
    icon_url: null,
    visibility: "unlisted",
    published: true,
    verified: false,
  },
]

/** One plugin, as its page meets it: a real README's first lines, and a restricted visibility. */
const pluginDetail: PluginDetail = {
  id: "33333333-3333-4333-8333-333333333333",
  plugin_id: "ai.rever.boss.plugin.dynamic.codexglm",
  display_name: "Codex GLM",
  description: "RISA Codex GLM provider for BOSS, brokered through the organisation gateway.",
  author_name: "Shivang",
  homepage_url: "https://github.com/risa-labs-inc/boss-plugin-codexglm",
  icon_url: null,
  type: "panel",
  api_version: "1.0.75",
  verified: true,
  published: true,
  visibility: "org",
  org_id: org.id,
  org_slug: org.slug,
  download_count: 128,
  latest_version: "1.0.4",
  updated_at: "2026-08-01T00:00:00Z",
}

const README = `[![build](https://img.shields.io/badge/build-passing-green.svg)](https://example.test/ci)

# Codex GLM

A BOSS plugin that runs Codex against the RISA LLM gateway. See [the docs](https://example.test/docs)
or the *relative* [contributing guide](./CONTRIBUTING.md), which keeps its text and loses its link.

## Install

Open the Toolbox, search for \`Codex GLM\`, press **Install**. No API key: it exchanges your BOSS
session for a short-lived scoped credential.

\`\`\`bash
boss llm-token --broker risa-llm-gateway
export CODEX_MODEL=glm-4.6
\`\`\`

> A blockquote, for the note every README has.

## Configuration

| Setting | Default | Notes |
|---|---:|:---:|
| Model   | glm-4.6 | overridable |
| Sandbox | workspace-write | see ~~danger~~ safety |

- First bullet with \`inline code\`
- Second bullet
- Third

1. Numbered one
2. Numbered two

---

Raw HTML is shown as text: <script>alert(1)</script>

Long unbroken line to check the wrap: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
`

const pages: Record<string, string> = {
  "overview.html": orgPage({ nonce: NONCE, basePath: BASE, org, members, roles, plugins }),
  // A second copy with enough rows to page, so the pager is actually looked at rather than
  // asserted about. Members are cloned from the real fixture to keep the row shape honest.
  "overview-paged.html": orgPage({
    nonce: NONCE,
    basePath: BASE,
    org,
    members: Array.from({ length: 60 }, (_, i) => ({
      ...members[i % members.length],
      email: `member${i + 1}@risalabs.ai`,
    })),
    roles,
    plugins: Array.from({ length: 40 }, (_, i) => ({
      ...plugins[i % plugins.length],
      plugin_id: `ai.rever.boss.plugin.dynamic.sample${i + 1}`,
      display_name: `Sample Plugin ${i + 1}`,
    })),
    membersPage: 2,
    pluginsPage: 1,
  }),
  "plugin.html": pluginPage({
    nonce: NONCE,
    basePath: BASE,
    orgSlug: org.slug,
    csrf: "preview-csrf",
    plugin: pluginDetail,
    readme: README,
    canEdit: true,
  }),
  "plugin-member.html": pluginPage({
    nonce: NONCE,
    basePath: BASE,
    orgSlug: org.slug,
    csrf: "preview-csrf",
    plugin: { ...pluginDetail, visibility: "public" },
    readme: null,
    canEdit: false,
  }),
  "admin.html": adminPage({
    nonce: NONCE,
    basePath: BASE,
    csrf: "preview-csrf",
    org,
    members,
    roles,
    domains,
    invites,
    banner: { kind: "ok", message: "Settings saved." },
  }),
  "admin-error.html": adminPage({
    nonce: NONCE,
    basePath: BASE,
    csrf: "preview-csrf",
    org,
    members,
    roles,
    domains,
    invites,
    banner: { kind: "error", message: "That value was not accepted. Check the field and try again." },
  }),
  // The one-time invite card. It carries `.highlight`, which had never rendered
  // (a bare .highlight loses to section.card on specificity) and which no preview
  // covered - so the visual check could not have caught it. That is the gap this
  // page and the two below close.
  "admin-new-invite.html": adminPage({
    nonce: NONCE,
    basePath: BASE,
    csrf: "preview-csrf",
    org,
    members,
    roles,
    domains,
    invites,
    newInviteUrl: INVITE,
  }),
  "invite-only.html": inviteOnlyPage(NONCE, BASE, "risa_labs", INVITE),
  "join.html": joinPage({
    nonce: NONCE,
    orgName: "Risa Labs",
    orgSlug: "risa_labs",
    description: "The people building BOSS.",
    deepLink: "boss://organisation/join?token=preview",
  }),
  "invite-invalid.html": invalidInvitePage(NONCE),
  "error.html": errorPage({
    nonce: NONCE,
    title: "Not available - BOSS",
    heading: "Not available",
    message: NOT_AVAILABLE_MESSAGE,
  }),
  "error-with-action.html": errorPage({
    nonce: NONCE,
    title: "Not available - BOSS",
    heading: "Not available",
    message: NOT_AVAILABLE_MESSAGE,
    action: { href: `${BASE}/o/risa_labs`, label: "Back to the organisation" },
  }),
}

const outDir = Deno.args[0] ?? "./preview"
await Deno.mkdir(outDir, { recursive: true })
for (const [name, html] of Object.entries(pages)) {
  await Deno.writeTextFile(`${outDir}/${name}`, html)
  console.log(`${outDir}/${name}`)
}
