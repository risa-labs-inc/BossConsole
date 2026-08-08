"""Public API for llm.risa.inc."""

from __future__ import annotations

import json
import uuid
from contextlib import asynccontextmanager
from typing import AsyncIterator

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

from .bridge import (
    BridgeRequestError,
    ToolRoute,
    extract_error_message,
    flatten_namespace_tools,
    rewrite_json_body,
    rewrite_sse_line,
)
from .broker import BrokerError, TokenBroker
from .clients import LiteLlmKeyIssuer, SupabaseIdentityClient
from .cloudrun_auth import CloudRunIdentityTokenProvider, IdentityTokenError
from .entitlements import CloudSqlEntitlementStore
from .settings import Settings


MAX_REQUEST_BYTES = 4 * 1024 * 1024


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = Settings.from_env()
    http = httpx.AsyncClient(timeout=httpx.Timeout(180.0, connect=10.0))
    entitlements = await CloudSqlEntitlementStore.connect(settings.gateway_database_url)
    identity_tokens = CloudRunIdentityTokenProvider(http, settings.litellm_id_token_audience)
    app.state.settings = settings
    app.state.http = http
    app.state.entitlements = entitlements
    app.state.identity_tokens = identity_tokens
    app.state.broker = TokenBroker(
        identity=SupabaseIdentityClient(http, settings),
        entitlements=entitlements,
        keys=LiteLlmKeyIssuer(http, settings, identity_tokens),
        allowed_domain=settings.allowed_email_domain,
        allowed_emails=settings.allowed_emails,
    )
    try:
        yield
    finally:
        await entitlements.close()
        await http.aclose()


app = FastAPI(
    title="RISA LLM Gateway",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)


def error_response(status_code: int, message: str, request_id: str | None = None) -> JSONResponse:
    headers = {"X-Request-Id": request_id} if request_id else None
    return JSONResponse(
        status_code=status_code,
        content={"error": {"message": message, "type": "invalid_request_error"}},
        headers=headers,
    )


@app.get("/healthz")
async def health(request: Request) -> Response:
    settings: Settings = request.app.state.settings
    try:
        headers = await _cloud_run_headers(request)
        response = await request.app.state.http.get(
            f"{settings.litellm_url}/health/readiness",
            headers=headers,
        )
    except (httpx.HTTPError, IdentityTokenError):
        return JSONResponse(status_code=503, content={"status": "not_ready"})
    database_ready = await request.app.state.entitlements.ready()
    status_code = 200 if response.status_code == 200 and database_ready else 503
    return JSONResponse(status_code=status_code, content={"status": "ready" if status_code == 200 else "not_ready"})


@app.post("/auth/token")
async def exchange_token(request: Request) -> Response:
    try:
        issued = await request.app.state.broker.exchange(request.headers.get("Authorization"))
    except BrokerError as exc:
        return error_response(exc.status_code, exc.message)

    settings: Settings = request.app.state.settings
    return JSONResponse(
        content={
            "access_token": issued.key,
            "expires_at": issued.expires_at,
            "refresh_after_seconds": settings.key_refresh_seconds,
            "token_type": "Bearer",
        },
        headers={"Cache-Control": "no-store"},
    )


@app.post("/v1/responses")
async def responses_proxy(request: Request) -> Response:
    request_id = request.headers.get("X-Request-Id") or str(uuid.uuid4())
    content_length = request.headers.get("Content-Length")
    if content_length:
        try:
            if int(content_length) > MAX_REQUEST_BYTES:
                return error_response(413, "Request body is too large", request_id)
        except ValueError:
            return error_response(400, "Invalid Content-Length header", request_id)

    authorization = request.headers.get("Authorization")
    if not authorization or not authorization.startswith("Bearer "):
        return error_response(401, "RISA LLM authentication required", request_id)

    raw_body = await request.body()
    if len(raw_body) > MAX_REQUEST_BYTES:
        return error_response(413, "Request body is too large", request_id)
    try:
        payload = json.loads(raw_body)
        if not isinstance(payload, dict):
            raise BridgeRequestError("Request body must be a JSON object")
        rewritten, aliases = flatten_namespace_tools(payload)
    except (json.JSONDecodeError, UnicodeDecodeError, BridgeRequestError) as exc:
        return error_response(400, str(exc), request_id)

    settings: Settings = request.app.state.settings
    try:
        cloud_run_headers = await _cloud_run_headers(request)
    except IdentityTokenError:
        return error_response(502, "Could not authenticate to the model gateway", request_id)
    upstream_request = request.app.state.http.build_request(
        "POST",
        f"{settings.litellm_url}/v1/responses",
        headers={
            "Authorization": authorization,
            **cloud_run_headers,
            "Content-Type": "application/json",
            "Accept": request.headers.get("Accept", "application/json, text/event-stream"),
            "X-Request-Id": request_id,
        },
        content=json.dumps(rewritten, separators=(",", ":")).encode("utf-8"),
    )

    try:
        upstream = await request.app.state.http.send(upstream_request, stream=True)
    except httpx.HTTPError:
        return error_response(502, "Could not reach the model gateway", request_id)

    content_type = upstream.headers.get("Content-Type", "application/json")
    if upstream.status_code >= 400:
        error_body = await upstream.aread()
        await upstream.aclose()
        return error_response(upstream.status_code, extract_error_message(error_body), request_id)

    headers = {
        "Content-Type": content_type,
        "Cache-Control": "no-store",
        "X-Request-Id": request_id,
    }
    if "text/event-stream" in content_type:
        return StreamingResponse(
            _rewrite_sse_stream(upstream, aliases),
            status_code=upstream.status_code,
            headers=headers,
            media_type="text/event-stream",
        )

    body = await upstream.aread()
    await upstream.aclose()
    return Response(
        content=rewrite_json_body(body, aliases),
        status_code=upstream.status_code,
        headers=headers,
        media_type=content_type.split(";", 1)[0],
    )


async def _rewrite_sse_stream(
    upstream: httpx.Response,
    aliases: dict[str, ToolRoute],
) -> AsyncIterator[bytes]:
    buffer = b""
    try:
        async for chunk in upstream.aiter_bytes():
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                yield rewrite_sse_line(line + b"\n", aliases)
        if buffer:
            yield rewrite_sse_line(buffer, aliases)
    finally:
        await upstream.aclose()


async def _cloud_run_headers(request: Request) -> dict[str, str]:
    token = await request.app.state.identity_tokens.token()
    return {"X-Serverless-Authorization": f"Bearer {token}"} if token else {}
