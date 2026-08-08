"""Google Cloud Run service-to-service identity token support."""

from __future__ import annotations

import base64
import json
import time
from typing import Any, Protocol
from urllib.parse import quote

METADATA_IDENTITY_URL = (
    "http://metadata.google.internal/computeMetadata/v1/instance/"
    "service-accounts/default/identity"
)


class IdentityTokenProvider(Protocol):
    async def token(self) -> str | None: ...


class IdentityTokenError(Exception):
    pass


class CloudRunIdentityTokenProvider:
    def __init__(self, http: Any, audience: str) -> None:
        self.http = http
        self.audience = audience
        self._token: str | None = None
        self._expires_at = 0

    async def token(self) -> str | None:
        if not self.audience:
            return None
        if self._token and self._expires_at - 60 > int(time.time()):
            return self._token

        try:
            response = await self.http.get(
                f"{METADATA_IDENTITY_URL}?audience={quote(self.audience, safe='')}&format=full",
                headers={"Metadata-Flavor": "Google"},
            )
            response.raise_for_status()
        except Exception as exc:
            raise IdentityTokenError("Could not obtain a Google service identity token") from exc
        token = response.text.strip()
        if not token:
            raise IdentityTokenError("Google metadata server returned an empty identity token")
        self._token = token
        self._expires_at = _jwt_expiry(token)
        return token


def _jwt_expiry(token: str) -> int:
    try:
        payload = token.split(".", 2)[1]
        payload += "=" * (-len(payload) % 4)
        body = json.loads(base64.urlsafe_b64decode(payload))
        return int(body["exp"])
    except (IndexError, KeyError, TypeError, ValueError, json.JSONDecodeError):
        return int(time.time()) + 300
