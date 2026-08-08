import unittest

from app.broker import (
    BrokerError,
    Entitlement,
    IssuedKey,
    TokenBroker,
    VerifiedUser,
    bearer_token,
    email_domain,
    unique_key_alias,
)


class FakeIdentity:
    def __init__(self, user: VerifiedUser) -> None:
        self.user = user
        self.seen_token = None

    async def verify_access_token(self, token: str) -> VerifiedUser:
        self.seen_token = token
        return self.user



class FakeEntitlements:
    def __init__(self, entitlement: Entitlement) -> None:
        self.entitlement = entitlement
        self.seen_user = None

    async def claim(self, current_user: VerifiedUser) -> Entitlement:
        self.seen_user = current_user
        return self.entitlement


class FakeKeys:
    def __init__(self) -> None:
        self.seen_user = None

    async def issue_key(self, user: VerifiedUser) -> IssuedKey:
        self.seen_user = user
        return IssuedKey(key="sk-user", expires_at="2026-08-06T12:00:00Z")


def user(email: str = "person@risalabs.ai", confirmed: bool = True) -> VerifiedUser:
    return VerifiedUser(user_id="user-123", email=email, email_confirmed=confirmed)


class BrokerHelpersTests(unittest.TestCase):
    def test_bearer_token(self) -> None:
        self.assertEqual(bearer_token("Bearer abc"), "abc")
        with self.assertRaises(BrokerError):
            bearer_token(None)

    def test_domain_is_exact(self) -> None:
        self.assertEqual(email_domain("Person@RisaLabs.ai"), "risalabs.ai")
        self.assertNotEqual(email_domain("person@risalabs.ai.example.com"), "risalabs.ai")

    def test_key_alias_is_unique_for_safe_retries(self) -> None:
        first = unique_key_alias()
        second = unique_key_alias()

        self.assertTrue(first.startswith("boss-codex-"))
        self.assertNotEqual(first, second)


class TokenBrokerTests(unittest.IsolatedAsyncioTestCase):
    async def test_issues_key_for_entitled_risa_user(self) -> None:
        identity = FakeIdentity(user())
        entitlements = FakeEntitlements(Entitlement(enabled=True, source="verified_domain"))
        keys = FakeKeys()
        broker = TokenBroker(identity, entitlements, keys)

        issued = await broker.exchange("Bearer boss-session")

        self.assertEqual(issued.key, "sk-user")
        self.assertEqual(identity.seen_token, "boss-session")
        self.assertEqual(keys.seen_user.user_id, "user-123")

    async def test_rejects_lookalike_domain(self) -> None:
        broker = TokenBroker(
            FakeIdentity(user("person@risalabs.ai.example.com")),
            FakeEntitlements(Entitlement(True, "test")),
            FakeKeys(),
        )

        with self.assertRaises(BrokerError) as caught:
            await broker.exchange("Bearer boss-session")
        self.assertEqual(caught.exception.status_code, 403)

    async def test_rejects_unconfirmed_email(self) -> None:
        broker = TokenBroker(
            FakeIdentity(user(confirmed=False)),
            FakeEntitlements(Entitlement(True, "test")),
            FakeKeys(),
        )

        with self.assertRaises(BrokerError) as caught:
            await broker.exchange("Bearer boss-session")
        self.assertIn("verified", caught.exception.message)

    async def test_rejects_revoked_entitlement(self) -> None:
        broker = TokenBroker(
            FakeIdentity(user()),
            FakeEntitlements(Entitlement(False, "admin_revoked")),
            FakeKeys(),
        )

        with self.assertRaises(BrokerError) as caught:
            await broker.exchange("Bearer boss-session")
        self.assertIn("disabled", caught.exception.message)

    async def test_pilot_allowlist_is_case_insensitive(self) -> None:
        broker = TokenBroker(
            FakeIdentity(user("Nilesh@RisaLabs.ai")),
            FakeEntitlements(Entitlement(True, "pilot")),
            FakeKeys(),
            allowed_emails=frozenset({"nilesh@risalabs.ai"}),
        )

        issued = await broker.exchange("Bearer boss-session")

        self.assertEqual(issued.key, "sk-user")

    async def test_rejects_risa_user_outside_pilot(self) -> None:
        entitlements = FakeEntitlements(Entitlement(True, "pilot"))
        broker = TokenBroker(
            FakeIdentity(user("not-in-pilot@risalabs.ai")),
            entitlements,
            FakeKeys(),
            allowed_emails=frozenset({"nilesh@risalabs.ai"}),
        )

        with self.assertRaises(BrokerError) as caught:
            await broker.exchange("Bearer boss-session")
        self.assertEqual(caught.exception.status_code, 403)
        self.assertIsNone(entitlements.seen_user)


if __name__ == "__main__":
    unittest.main()
