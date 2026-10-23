/** The org invite email: plain, branded, one link to the console's /join/<token>. */

export interface InviteFacts {
  email: string;
  org_name: string;
  role_name: string | null;
  expires_at: string | null;
  inviter_email: string | null;
  inviter_name: string | null;
}

const esc = (s: string) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");

/** `acme_agent_editor` -> `agent editor` (the org slug prefix is noise to the reader). */
export function roleLabel(role: string | null, orgSlug?: string | null): string | null {
  if (!role) return null;
  let r = role;
  if (orgSlug && r.startsWith(`${orgSlug}_`)) r = r.slice(orgSlug.length + 1);
  return r.replace(/_/g, " ").trim() || null;
}

function expiry(at: string | null): string | null {
  if (!at) return null;
  const d = new Date(at);
  if (Number.isNaN(d.getTime())) return null;
  return d.toUTCString().replace(/ GMT$/, " UTC");
}

export function inviteMail(
  facts: InviteFacts & { org_slug?: string | null },
  link: string,
): { subject: string; text: string; html: string } {
  const org = facts.org_name.replace(/[\r\n]+/g, " ").trim() || "an organisation";
  const who = (facts.inviter_name || facts.inviter_email || "An administrator").replace(
    /[\r\n]+/g,
    " ",
  ).trim();
  const role = roleLabel(facts.role_name, facts.org_slug);
  const until = expiry(facts.expires_at);
  const subject = `You're invited to join ${org} on BOSS`;
  const text = [
    `${who} invited you to join ${org} on BOSS${role ? ` as ${role}` : ""}.`,
    "",
    "Accept the invite (sign in with this email address):",
    link,
    "",
    until ? `The link works once and expires ${until}.` : "The link works once.",
    `It was sent to ${facts.email}. If you were not expecting it, ignore this email.`,
  ].join("\n");
  const html = `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${
    esc(subject)
  }</title></head>
<body style="margin:0;padding:24px;background:#f5f6f8;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1a1d21">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#ffffff;border:1px solid #e3e6ea;border-radius:12px">
<tr><td style="padding:28px 28px 8px 28px">
<div style="font-size:13px;font-weight:600;letter-spacing:.04em;color:#5b6470;text-transform:uppercase">BOSS</div>
<h1 style="margin:12px 0 0 0;font-size:20px;line-height:28px;font-weight:600">Join ${esc(org)}</h1>
<p style="margin:12px 0 0 0;font-size:15px;line-height:22px">${
    esc(who)
  } invited you to join <strong>${esc(org)}</strong>${
    role ? ` as <strong>${esc(role)}</strong>` : ""
  }.</p>
</td></tr>
<tr><td style="padding:20px 28px">
<a href="${
    esc(link)
  }" style="display:inline-block;background:#1d5fd6;color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;padding:11px 20px;border-radius:8px">Accept invite</a>
</td></tr>
<tr><td style="padding:0 28px 24px 28px;font-size:13px;line-height:20px;color:#5b6470">
<p style="margin:0">Sign in with <strong>${esc(facts.email)}</strong> to accept. ${
    until ? `The link works once and expires ${esc(until)}.` : "The link works once."
  }</p>
<p style="margin:12px 0 0 0;word-break:break-all">Or open: <a href="${
    esc(link)
  }" style="color:#1d5fd6">${esc(link)}</a></p>
<p style="margin:12px 0 0 0">If you were not expecting this, ignore this email.</p>
</td></tr>
</table>
</td></tr></table>
</body></html>`;
  return { subject, text, html };
}

/** What agent_invite_mail_prepare returns: the invite, the agent and its site, who invited. */
export interface AgentInviteFacts {
  email: string;
  agent_name: string;
  agent_slug: string;
  custom_host: string | null;
  org_name: string;
  inviter_email: string | null;
  inviter_name: string | null;
}

/** The agent invite email: one link to the agent's own site, where the person signs in with this address. */
export function agentInviteMail(
  facts: AgentInviteFacts,
  link: string,
): { subject: string; text: string; html: string } {
  const flat = (s: string) => s.replace(/[\r\n]+/g, " ").trim();
  const agent = flat(facts.agent_name) || "an agent";
  const org = flat(facts.org_name);
  const who = flat(facts.inviter_name || facts.inviter_email || "An administrator");
  const subject = `You're invited to ${agent}`;
  const text = [
    `${who} invited you to ${agent}${org ? ` from ${org}` : ""}.`,
    "",
    "Open it and sign in with this email address:",
    link,
    "",
    `It was sent to ${facts.email}. If you were not expecting it, ignore this email.`,
  ].join("\n");
  const html = `<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${
    esc(subject)
  }</title></head>
<body style="margin:0;padding:24px;background:#f5f6f8;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1a1d21">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0"><tr><td align="center">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#ffffff;border:1px solid #e3e6ea;border-radius:12px">
<tr><td style="padding:28px 28px 8px 28px">
<h1 style="margin:0;font-size:20px;line-height:28px;font-weight:600">You're invited to ${
    esc(agent)
  }</h1>
<p style="margin:12px 0 0 0;font-size:15px;line-height:22px">${esc(who)} invited you to <strong>${
    esc(agent)
  }</strong>${org ? ` from ${esc(org)}` : ""}.</p>
</td></tr>
<tr><td style="padding:20px 28px">
<a href="${
    esc(link)
  }" style="display:inline-block;background:#1d5fd6;color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;padding:11px 20px;border-radius:8px">Open ${
    esc(agent)
  }</a>
</td></tr>
<tr><td style="padding:0 28px 24px 28px;font-size:13px;line-height:20px;color:#5b6470">
<p style="margin:0">Sign in with <strong>${esc(facts.email)}</strong>.</p>
<p style="margin:12px 0 0 0;word-break:break-all">Or open: <a href="${
    esc(link)
  }" style="color:#1d5fd6">${esc(link)}</a></p>
<p style="margin:12px 0 0 0">If you were not expecting this, ignore this email.</p>
</td></tr>
</table>
</td></tr></table>
</body></html>`;
  return { subject, text, html };
}
