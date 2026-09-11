import { assert, assertEquals, assertRejects } from "jsr:@std/assert";
import { createClient } from "@supabase/supabase-js";
import { generateSupabaseAccessToken } from "../utils/jwt.ts";
import { storeChallenge, cleanupExpiredChallenges } from "../utils/challenge.ts";
import { checkAuthStatus } from "../services/auth.ts";
import { withErrorHandler } from "../utils/error-handler.ts";
import { authFailureDetails } from "../utils/logging.ts";
import { ChallengeType } from "../types/challenge.ts";

const sessionId = "session-CANARY-0123456789";
const refreshToken = "refresh-CANARY-abcdefghijklmnopqrstuvwxyz";
const challenge = "challenge-CANARY-abcdefghijklmnopqrstuvwxyz";
const accessToken = "access-CANARY-abcdefghijklmnopqrstuvwxyz";
const user = { id: "test-user", aud: "authenticated", role: "authenticated", email: "user@example.com",
  app_metadata: {}, user_metadata: {}, created_at: "2026-01-01T00:00:00Z" };

function client(responses: Array<{ body: unknown; status?: number }>) {
  return createClient("http://localhost:54321", "synthetic-anon-key", {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    global: {
      fetch: () => {
        const response = responses.shift();
        if (!response) throw new Error("Unexpected test request");
        return Promise.resolve(Response.json(response.body, { status: response.status ?? 200 }));
      },
    },
  });
}

async function capture(action: () => Promise<void>): Promise<string> {
  const output: unknown[][] = [];
  const originals = { log: console.log, error: console.error, warn: console.warn };
  const record = (...args: unknown[]) => output.push(args);
  console.log = record;
  console.error = record;
  console.warn = record;
  try { await action(); } finally { Object.assign(console, originals); }
  const text = JSON.stringify(output);
  for (const value of [sessionId, refreshToken, challenge, accessToken]) {
    assert(!text.includes(value), "logs contain credential material");
    assert(!text.includes(value.slice(0, 20)), "logs contain a credential prefix");
  }
  return text;
}

Deno.test("session mint success and failures never log returned tokens or error bodies", async () => {
  await capture(async () => {
    const supabase = client([
      { body: { ...user, hashed_token: "synthetic-hash", action_link: "http://localhost/verify" } },
      { body: { access_token: accessToken, refresh_token: refreshToken, expires_in: 3600,
        token_type: "bearer", user } },
    ]);
    const tokens = await generateSupabaseAccessToken(supabase, user.email);
    assertEquals(tokens.refreshToken, refreshToken);
    const failure = client([{ body: { msg: refreshToken, code: "unexpected_failure" }, status: 500 }]);
    await assertRejects(() => generateSupabaseAccessToken(failure, user.email));
  });
});

Deno.test("challenge admission, cleanup and polling do not log bearer IDs or database payloads", async () => {
  await capture(async () => {
    assert((await storeChallenge(client([{ body: [] }]), challenge, ChallengeType.Authentication,
      { sessionId, userId: user.id })).success);
    const error = { code: "23505", message: `${challenge} ${sessionId} ${refreshToken}`, details: accessToken };
    assertEquals((await storeChallenge(client([{ body: error, status: 409 }]), challenge,
      ChallengeType.Authentication, { sessionId })).success, false);
    assertEquals((await cleanupExpiredChallenges(client([{ body: error, status: 409 }]))).success, false);
    const missing = await checkAuthStatus(client([{ body: [] }, { body: [] }]), sessionId);
    assertEquals(missing.status, "expired");
    const replay = await checkAuthStatus(client([
      { body: [] },
      { body: [{ id: "completion", user_id: user.id, email: user.email, created_at: "2026-01-01",
        access_token: accessToken, refresh_token: refreshToken, expires_at: Date.now() + 600_000 }] },
      { body: [{ id: "completion" }] },
    ]), sessionId);
    assertEquals(replay.status, "completed");
    assert("refreshToken" in replay);
    assertEquals(replay.refreshToken, refreshToken);
  });
});

Deno.test("service error wrappers omit exception messages and preserve safe diagnostics", async () => {
  const output = await capture(async () => {
    const wrapped = withErrorHandler(async () => { throw new Error(refreshToken); }, "Login unavailable");
    assertEquals(await wrapped(), { success: false, error: "Login unavailable" });
    console.error("Database request failed", authFailureDetails({ code: "23505", status: 409,
      message: sessionId, details: accessToken }));
  });
  assert(output.includes("23505"));
  assert(output.includes("409"));
});
