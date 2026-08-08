"""Durable RISA LLM entitlement storage in PostgreSQL/Cloud SQL."""

from __future__ import annotations

import asyncpg

from .broker import BrokerError, Entitlement, VerifiedUser


SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS llm_entitlements (
    user_id text PRIMARY KEY,
    email text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    source text NOT NULL DEFAULT 'verified_domain',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz,
    CONSTRAINT llm_entitlements_source_not_blank CHECK (length(trim(source)) > 0)
)
"""


class CloudSqlEntitlementStore:
    def __init__(self, pool: asyncpg.Pool) -> None:
        self.pool = pool

    @classmethod
    async def connect(cls, database_url: str) -> "CloudSqlEntitlementStore":
        pool = await asyncpg.create_pool(database_url, min_size=1, max_size=5, command_timeout=10)
        store = cls(pool)
        async with pool.acquire() as connection:
            await connection.execute(SCHEMA_SQL)
        return store

    async def close(self) -> None:
        await self.pool.close()

    async def ready(self) -> bool:
        try:
            return bool(await self.pool.fetchval("SELECT true"))
        except (asyncpg.PostgresError, OSError):
            return False

    async def claim(self, user: VerifiedUser) -> Entitlement:
        try:
            async with self.pool.acquire() as connection:
                async with connection.transaction():
                    await connection.execute(
                        """
                        INSERT INTO llm_entitlements (user_id, email, enabled, source)
                        VALUES ($1, $2, true, 'verified_domain')
                        ON CONFLICT (user_id) DO NOTHING
                        """,
                        user.user_id,
                        user.email.strip().lower(),
                    )
                    row = await connection.fetchrow(
                        """
                        SELECT enabled, source
                        FROM llm_entitlements
                        WHERE user_id = $1
                        """,
                        user.user_id,
                    )
        except (asyncpg.PostgresError, OSError) as exc:
            raise BrokerError(503, "Could not verify RISA LLM entitlement") from exc

        if row is None:
            raise BrokerError(503, "Could not verify RISA LLM entitlement")
        return Entitlement(enabled=bool(row["enabled"]), source=str(row["source"]))
