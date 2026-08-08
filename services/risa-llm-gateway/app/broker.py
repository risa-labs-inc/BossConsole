"""Authentication and short-lived LiteLLM key exchange."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol
from uuid import uuid4


class BrokerError(Exception):
    def __init__(self, status_code: int, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.message = message


@dataclass(frozen=True)
class VerifiedUser:
    user_id: str
    email: str
    email_confirmed: bool


@dataclass(frozen=True)
class Entitlement:
    enabled: bool
    source: str


@dataclass(frozen=True)
class IssuedKey:
    key: str
    expires_at: str


class IdentityClient(Protocol):
    async def verify_access_token(self, token: str) -> VerifiedUser: ...


class EntitlementStore(Protocol):
    async def claim(self, user: VerifiedUser) -> Entitlement: ...


class KeyIssuer(Protocol):
    async def issue_key(self, user: VerifiedUser) -> IssuedKey: ...


def bearer_token(authorization: str | None) -> str:
    prefix = "Bearer "
    if not authorization or not authorization.startswith(prefix):
        raise BrokerError(401, "BOSS authentication required")
    token = authorization[len(prefix):].strip()
    if not token:
        raise BrokerError(401, "BOSS authentication required")
    return token


def email_domain(email: str) -> str:
    _, separator, domain = email.strip().lower().rpartition("@")
    return domain if separator else ""


def unique_key_alias() -> str:
    return f"boss-codex-{uuid4().hex}"


class TokenBroker:
    def __init__(
        self,
        identity: IdentityClient,
        entitlements: EntitlementStore,
        keys: KeyIssuer,
        allowed_domain: str = "risalabs.ai",
        allowed_emails: frozenset[str] | None = None,
    ) -> None:
        self.identity = identity
        self.entitlements = entitlements
        self.keys = keys
        self.allowed_domain = allowed_domain.lower()
        self.allowed_emails = frozenset(email.lower() for email in allowed_emails) if allowed_emails else None

    async def exchange(self, authorization: str | None) -> IssuedKey:
        access_token = bearer_token(authorization)
        user = await self.identity.verify_access_token(access_token)

        if not user.email_confirmed:
            raise BrokerError(403, "A verified RISA email address is required")
        if email_domain(user.email) != self.allowed_domain:
            raise BrokerError(403, "RISA LLM access is limited to risalabs.ai users")
        if self.allowed_emails is not None and user.email.strip().lower() not in self.allowed_emails:
            raise BrokerError(403, "RISA LLM access is not enabled for this account")

        entitlement = await self.entitlements.claim(user)
        if not entitlement.enabled:
            raise BrokerError(403, "RISA LLM access has been disabled for this account")

        return await self.keys.issue_key(user)
