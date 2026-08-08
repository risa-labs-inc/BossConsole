"""HTTP clients for Supabase identity and LiteLLM key management."""

from __future__ import annotations

from typing import Any

import httpx

from .broker import BrokerError, IssuedKey, VerifiedUser, unique_key_alias
from .cloudrun_auth import IdentityTokenError, IdentityTokenProvider
from .settings import Settings


class SupabaseIdentityClient:
    def __init__(self, http: httpx.AsyncClient, settings: Settings) -> None:
        self.http = http
        self.settings = settings

    async def verify_access_token(self, token: str) -> VerifiedUser:
        response = await self.http.get(
            f"{self.settings.supabase_url}/auth/v1/user",
            headers={
                "apikey": self.settings.supabase_anon_key,
                "Authorization": f"Bearer {token}",
            },
        )
        if response.status_code != 200:
            raise BrokerError(401, "BOSS session is invalid or expired")

        body = _json_object(response, "Supabase returned an invalid user response")
        user_id = body.get("id")
        email = body.get("email")
        if not isinstance(user_id, str) or not isinstance(email, str):
            raise BrokerError(502, "Supabase returned an incomplete user response")
        confirmed = bool(body.get("email_confirmed_at") or body.get("confirmed_at"))
        return VerifiedUser(user_id=user_id, email=email, email_confirmed=confirmed)


class LiteLlmKeyIssuer:
    def __init__(
        self,
        http: httpx.AsyncClient,
        settings: Settings,
        identity_tokens: IdentityTokenProvider,
    ) -> None:
        self.http = http
        self.settings = settings
        self.identity_tokens = identity_tokens

    async def issue_key(self, user: VerifiedUser) -> IssuedKey:
        headers = {
            "Authorization": f"Bearer {self.settings.litellm_master_key}",
            "Content-Type": "application/json",
        }
        try:
            identity_token = await self.identity_tokens.token()
        except IdentityTokenError as exc:
            raise BrokerError(502, "Could not authenticate to the model gateway") from exc
        if identity_token:
            headers["X-Serverless-Authorization"] = f"Bearer {identity_token}"

        response = await self.http.post(
            f"{self.settings.litellm_url}/key/generate",
            headers=headers,
            json={
                "models": [self.settings.model],
                "user_id": user.user_id,
                "duration": self.settings.key_duration,
                "rpm_limit": self.settings.rpm_limit,
                "tpm_limit": self.settings.tpm_limit,
                # LiteLLM requires aliases to be globally unique. A client can
                # lose the first response during a cold start and retry, so a
                # deterministic per-user alias makes otherwise safe retries
                # fail. Attribution remains stable through user_id/metadata.
                "key_alias": unique_key_alias(),
                "metadata": {
                    "client": "boss-codex",
                    "supabase_user_id": user.user_id,
                },
            },
        )
        if response.status_code != 200:
            raise BrokerError(502, "Could not issue a RISA LLM access token")

        body = _json_object(response, "LiteLLM returned an invalid key response")
        key = body.get("key")
        expires = body.get("expires")
        if not isinstance(key, str) or not key or not isinstance(expires, str) or not expires:
            raise BrokerError(502, "LiteLLM returned an incomplete key response")
        return IssuedKey(key=key, expires_at=expires)


def _json_object(response: httpx.Response, error_message: str) -> dict[str, Any]:
    try:
        body = response.json()
    except ValueError as exc:
        raise BrokerError(502, error_message) from exc
    if not isinstance(body, dict):
        raise BrokerError(502, error_message)
    return body
