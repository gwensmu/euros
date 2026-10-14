"""Loopback app API + stateless MCP, both using Store commands."""
import argparse
import hashlib
import hmac
import json
import os
from contextlib import asynccontextmanager
from pathlib import Path

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations, CallToolResult, TextContent
from starlette.applications import Starlette
from starlette.middleware.trustedhost import TrustedHostMiddleware
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route

from .domain import DomainError, Store


def load_token(name):
    file = os.environ.get(name + "_FILE")
    token = Path(file).read_text().strip() if file else os.environ.get(name, "").strip()
    if len(token) < 32 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError(f"Set {name} or {name}_FILE to a private ASCII token (32+ characters)")
    return token


class TokenGate:
    def __init__(self, app, api_token, mcp_token, origins):
        self.app = app
        self.digests = {"api": hashlib.sha256(api_token.encode()).digest(),
                        "mcp": hashlib.sha256(mcp_token.encode()).digest()}
        self.origins = {origin.encode("ascii") for origin in origins}

    async def __call__(self, scope, receive, send):
        if scope["type"] == "http":
            headers = scope.get("headers", [])
            origins = [v for k, v in headers if k == b"origin"]
            if origins and (len(origins) != 1 or origins[0] not in self.origins):
                await JSONResponse({"error": "origin_denied"}, 403)(scope, receive, send)
                return
            auths = [v for k, v in headers if k == b"authorization"]
            parts = auths[0].split(b" ", 1) if len(auths) == 1 else []
            token = parts[1] if len(parts) == 2 and parts[0].lower() == b"bearer" else b""
            surface = "api" if scope["path"].startswith("/v1/") else "mcp"
            if not token or not hmac.compare_digest(hashlib.sha256(token).digest(), self.digests[surface]):
                await JSONResponse({"error": "unauthorized"}, 401,
                                   headers={"WWW-Authenticate": 'Bearer realm="euros"', "Cache-Control": "no-store"})(scope, receive, send)
                return
        await self.app(scope, receive, send)


def create_app(store, api_token, mcp_token, public_hosts=()):
    if len(api_token) < 32 or len(mcp_token) < 32 or api_token == mcp_token:
        raise ValueError("Separate app and MCP tokens of at least 32 characters are required")
    hosts = ["127.0.0.1", "localhost", *public_hosts]
    origins = [f"https://{h}" for h in public_hosts]
    mcp = FastMCP("Euros", stateless_http=True, json_response=True,
                  transport_security=TransportSecuritySettings(
                      allowed_hosts=[h for host in hosts for h in (host, f"{host}:*")],
                      allowed_origins=origins))
    read = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)

    @mcp.tool(annotations=read)
    def read_records(entity_type: str | None = None) -> dict:
        """Read saved activities, food_entries or weight_entries; no inferred completions."""
        return store.records(entity_type)

    @mcp.tool(annotations=read)
    def read_changes(after: int = 0, limit: int = 100) -> dict:
        """Read revision history and tombstones after an exclusive cursor."""
        return store.changes(after, limit)

    @mcp.tool(annotations=read)
    def nutrition_totals(local_date: str) -> dict:
        """Known daily nutrient subtotal and missing-value counts; not an intake target."""
        return store.nutrition(local_date)

    @mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=True, idempotentHint=True, openWorldHint=False))
    def apply_command(command: dict) -> CallToolResult:
        """Explicitly save/correct/delete a record using the same revision-checked app command.

        Requires command_id, entity_type, entity_id, expected_revision, operation (upsert/delete), payload.
        Interpretation is not supported by this foundation; never invent nutrient values.
        """
        try:
            result = store.command(command)
            return CallToolResult(content=[TextContent(type="text", text=json.dumps(result))], structuredContent=result)
        except DomainError as exc:
            result = {**exc.body, "status": exc.status}
            return CallToolResult(isError=True, content=[TextContent(type="text", text=json.dumps(result))], structuredContent=result)

    async def command(request):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 262144:
                return JSONResponse({"error": "request_too_large"}, 413)
        try:
            data = json.loads(body)
        except (ValueError, UnicodeDecodeError):
            return JSONResponse({"error": "invalid_json"}, 400)
        return JSONResponse(store.command(data))

    async def changes(request):
        try:
            after = int(request.query_params.get("after", 0))
            limit = int(request.query_params.get("limit", 100))
        except ValueError:
            raise DomainError("invalid_pagination")
        return JSONResponse(store.changes(after, limit))

    async def records(request):
        return JSONResponse(store.records(request.query_params.get("entity_type")))

    async def nutrition(request):
        return JSONResponse(store.nutrition(request.query_params.get("local_date")))

    async def domain_error(request, exc):
        return JSONResponse(exc.body, exc.status)

    @asynccontextmanager
    async def lifespan(app):
        async with mcp.session_manager.run():
            yield

    app = Starlette(routes=[Route("/v1/commands", command, methods=["POST"]),
                            Route("/v1/changes", changes), Route("/v1/records", records),
                            Route("/v1/nutrition", nutrition), Mount("/", mcp.streamable_http_app())],
                    lifespan=lifespan, exception_handlers={DomainError: domain_error})
    app.state.mcp = mcp
    return TrustedHostMiddleware(TokenGate(app, api_token, mcp_token, origins), allowed_hosts=hosts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--import-plan", help="Explicitly import an authored JSON array of activity commands, then exit")
    parser.add_argument("--port", type=int, default=3003)
    parser.add_argument("--public-host", action="append", default=[])
    args = parser.parse_args()
    db_path = os.environ.get("EUROS_DB_PATH")
    if not db_path:
        raise ValueError("Set EUROS_DB_PATH to a persistent database outside release directories")
    if args.import_plan:
        result = Store(db_path).import_plan(json.loads(Path(args.import_plan).read_text()))
        print(json.dumps({"accepted": len(result["results"])}))
        return
    api_token, mcp_token = load_token("EUROS_API_TOKEN"), load_token("EUROS_MCP_TOKEN")
    import uvicorn
    uvicorn.run(create_app(Store(db_path), api_token, mcp_token, args.public_host),
                host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
