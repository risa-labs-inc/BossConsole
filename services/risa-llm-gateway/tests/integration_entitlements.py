"""Manual PostgreSQL integration smoke test for the entitlement store."""

from __future__ import annotations

import asyncio
import os

from app.broker import VerifiedUser
from app.entitlements import CloudSqlEntitlementStore


async def main() -> None:
    database_url = os.environ["GATEWAY_DATABASE_URL"]
    store = await CloudSqlEntitlementStore.connect(database_url)
    user = VerifiedUser(
        user_id="00000000-0000-0000-0000-000000000001",
        email="gateway-smoke-test@risalabs.ai",
        email_confirmed=True,
    )
    try:
        first = await store.claim(user)
        assert first.enabled
        await store.pool.execute(
            """
            UPDATE llm_entitlements
            SET enabled = false, source = 'integration_test', revoked_at = now()
            WHERE user_id = $1
            """,
            user.user_id,
        )
        second = await store.claim(user)
        assert not second.enabled
        assert second.source == "integration_test"
        print("Cloud SQL entitlement claim and durable revocation passed")
    finally:
        await store.pool.execute(
            "DELETE FROM llm_entitlements WHERE user_id = $1",
            user.user_id,
        )
        await store.close()


if __name__ == "__main__":
    asyncio.run(main())
