"""App API, MCP and the account site, all over the signed-in account's own workspace.

Three ways in, one identity model: an OAuth access token for the phone app and MCP clients,
a username and password on the account site, and an optional static token kept for an already
enrolled single-account deployment. Each request resolves to one account before any handler
runs, and every handler receives that account's scoped services over the shared database.
"""
import argparse
import getpass
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
from starlette.responses import JSONResponse, RedirectResponse
from starlette.routing import Mount, Route

from .accounts import AccountStore
from .domain import DomainError, Store
from .llm import ModelService
from .oauth import OAuthServer
from .plan import PlanSettings, generate_sessions
from .security import DEFAULT_ACCOUNT_MONTHLY_TOKENS, DEFAULT_MONTHLY_TOKENS, BodyLimit, TokenBudget
from .browser import BrowserSessions
from .web import AccountSite
from .workspace import FixedWorkspace, Workspaces

OWNER = 'usr-' + '0' * 32
PUBLIC_PREFIXES = ('/oauth/', '/account', '/.well-known/')
READ_SCOPE, WRITE_SCOPE = 'training.read', 'training.write'


def load_token(name):
    file = os.environ.get(name + "_FILE")
    token = Path(file).read_text().strip() if file else os.environ.get(name, "").strip()
    if len(token) < 32 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError(f"Set {name} or {name}_FILE to a private ASCII token (32+ characters)")
    return token


class AuthGate:
    """Resolves every API and MCP request to one account before a handler sees it.

    Static tokens stay bound to their surface and to the account they were issued for. OAuth
    access tokens carry their own account and scopes. Public paths (account site, OAuth
    endpoints, discovery documents) authenticate themselves and are passed through.
    """

    def __init__(self, app, accounts=None, static=None, origins=(), issuer=None):
        self.app, self.accounts, self.static, self.issuer = app, accounts, static or {}, issuer
        self.digests = {surface: hashlib.sha256(token.encode()).digest()
                        for surface, token in self.static.get('tokens', {}).items()}
        self.origins = {origin.encode("ascii") for origin in origins}

    def _static_principal(self, surface, token):
        digest = self.digests.get(surface)
        if digest and hmac.compare_digest(hashlib.sha256(token).digest(), digest):
            return dict(user_id=self.static['user_id'], scopes=[READ_SCOPE, WRITE_SCOPE],
                        client_id='static-' + surface, kind='static')
        return None

    def _oauth_principal(self, token):
        granted = self.accounts.oauth.access(token.decode('ascii', 'ignore')) if self.accounts else None
        if granted is None:
            return None
        return dict(user_id=granted['user_id'], scopes=granted['scope'], client_id=granted['client_id'],
                    kind='oauth')

    def _public(self, path):
        # Those surfaces authenticate themselves, and only exist when accounts do. "/" is the
        # signpost to the account site and carries nothing of its own.
        return self.accounts is not None and (path == "/" or path.startswith(PUBLIC_PREFIXES))

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or self._public(scope["path"]):
            return await self.app(scope, receive, send)
        headers = scope.get("headers", [])
        origins = [v for k, v in headers if k == b"origin"]
        if origins and (len(origins) != 1 or origins[0] not in self.origins):
            return await JSONResponse({"error": "origin_denied"}, 403)(scope, receive, send)
        auths = [v for k, v in headers if k == b"authorization"]
        parts = auths[0].split(b" ", 1) if len(auths) == 1 else []
        token = parts[1] if len(parts) == 2 and parts[0].lower() == b"bearer" else b""
        surface = "api" if scope["path"].startswith("/v1/") else "mcp"
        principal = (self._static_principal(surface, token) or self._oauth_principal(token)) if token else None
        if principal is None:
            return await self._challenge(scope, receive, send, "unauthorized", 401)
        needed = WRITE_SCOPE if surface == "api" and scope["method"] not in ("GET", "HEAD") else READ_SCOPE
        if needed not in principal["scopes"]:
            return await self._challenge(scope, receive, send, "insufficient_scope", 403, needed)
        scope["euros_principal"] = principal
        await self.app(scope, receive, send)

    async def _challenge(self, scope, receive, send, error, status, needed=None):
        header = lambda name: next((v.decode('latin-1') for k, v in scope.get('headers', []) if k == name), '')
        # Behind a TLS terminator only the configured public URL or the forwarded scheme is right.
        scheme = header(b'x-forwarded-proto').split(',')[0].strip() or scope.get('scheme', 'https')
        base = self.issuer.rstrip('/') if self.issuer else f"{scheme}://{header(b'host')}"
        challenge = f'Bearer realm="euros", error="{error}"'
        if needed:
            challenge += f', scope="{needed}"'
        if self.accounts is not None:
            challenge += f', resource_metadata="{base}/.well-known/oauth-protected-resource"'
        await JSONResponse({"error": error}, status,
                           headers={"WWW-Authenticate": challenge, "Cache-Control": "no-store"})(scope, receive, send)


def _build(workspaces, accounts, public_hosts=(), static=None, issuer=None, allow_signup=False):
    hosts = ["127.0.0.1", "localhost", *public_hosts]
    origins = [f"https://{h}" for h in public_hosts]
    mcp = FastMCP("Euros", instructions="Before planning or changing meals, call meal_planning_context. For a week of meals use draft_week, show the user, redraft for their changes and apply_week once accepted. For one-off edits read the dated plan, preview_meal_plan, then apply_command. Proposals never log food. read_training_plan shows the signed-in user's competition date, calorie goals, meals and weekly workouts; they change it on the account site.", stateless_http=True, json_response=True,
                  transport_security=TransportSecuritySettings(
                      allowed_hosts=[h for host in hosts for h in (host, f"{host}:*")],
                      allowed_origins=origins))
    read = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=False)

    def principal_of(request):
        found = request.scope.get("euros_principal") if request is not None else None
        if found is None:
            raise DomainError("unauthorized", 401)
        return found

    def workspace(request, write=False):
        """The signed-in account's workspace, or a refusal; no handler chooses an account itself."""
        found = principal_of(request)
        if (WRITE_SCOPE if write else READ_SCOPE) not in found["scopes"]:
            raise DomainError("insufficient_scope", 403)
        return workspaces.get(found["user_id"])

    def mcp_workspace(write=False):
        try:
            request = mcp.get_context().request_context.request
        except (LookupError, ValueError, AttributeError):
            raise DomainError("unauthorized", 401) from None
        return workspace(request, write)

    # --- MCP tools ----------------------------------------------------------------------

    def tool_result(call):
        try:
            result = call()
            return CallToolResult(content=[TextContent(type="text", text=json.dumps(result))], structuredContent=result)
        except DomainError as exc:
            result = {**exc.body, "status": exc.status}
            return CallToolResult(isError=True, content=[TextContent(type="text", text=json.dumps(result))], structuredContent=result)

    async def model_result(call):
        try:
            result = await call
            return CallToolResult(content=[TextContent(type="text", text=json.dumps(result))], structuredContent=result)
        except DomainError as exc:
            result = {**exc.body, "status": exc.status}
            return CallToolResult(isError=True, content=[TextContent(type="text", text=json.dumps(result))], structuredContent=result)

    @mcp.tool(annotations=read)
    def meal_planning_context() -> CallToolResult:
        """Read nutrition policy, meal templates, provenance and safe planning workflow first."""
        from .meal_planning import context
        return tool_result(lambda: context(mcp_workspace().catalog))

    @mcp.tool(annotations=read)
    def preview_meal_plan(payload: dict) -> CallToolResult:
        """Validate a dated meal proposal and calculate known planned totals; never save."""
        from .meal_planning import preview
        return tool_result(lambda: preview(payload))

    @mcp.tool(annotations=read)
    def read_training_plan() -> CallToolResult:
        """Read this account's training plan: competition, calorie goals, meals and weekly workouts.

        These are the user's own settings, changed by them on the account site, not records.
        Meal drafting and daily targets already follow them; read them to explain or plan around
        the user's schedule rather than assuming the defaults.
        """
        return tool_result(lambda: mcp_workspace().plans.read())

    @mcp.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=False, openWorldHint=False))
    def draft_week(week_start: str, dates: list[str] | None = None, swaps: list[dict] | None = None,
                   avoid_foods: list[str] | None = None) -> CallToolResult:
        """Draft a Mon–Sun meal week balanced to each day's nutrition targets; saves nothing.

        week_start is a Monday (YYYY-MM-DD). Optional dates limits the draft to those days.
        Today and past days are skipped. Translate the user's requests:
        swaps [{date, meal_id, template}] for "salmon Friday dinner"; avoid_foods [food_id]
        for "no eggs this week". Valid meal_ids, templates and food_ids are in meal_planning_context.
        Show the user the returned week, redraft for changes, then apply_week(draft_id) once accepted.
        """
        return tool_result(lambda: mcp_workspace().meal_weeks.draft(week_start, dates, swaps, avoid_foods))

    @mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=True, idempotentHint=True, openWorldHint=False))
    def apply_week(draft_id: str) -> CallToolResult:
        """Save an accepted draft_week: all meal plans and prep activities atomically, or nothing.

        Call only after the user accepts the draft. If anything changed since drafting it
        returns draft_stale and saves nothing; draft again. Never logs food.
        """
        return tool_result(lambda: mcp_workspace(write=True).meal_weeks.apply(draft_id))

    @mcp.tool(annotations=read)
    def grocery_list(week_start: str) -> CallToolResult:
        """Aggregate the saved Mon–Sun meal plans into a purchase-form grocery list; buys and saves nothing.

        week_start is a Monday (YYYY-MM-DD). Only applied plans count — draft first, apply_week,
        then list. Days without a saved plan appear in missing_dates, never filled in. Cooked/dry
        planning grams come from each saved plan's meals and convert to the catalog's
        purchase form via cooked_yield, then to whole store units via the catalog's purchase.unit
        (buy_count/buy_unit, e.g. 12 eggs or 2 cans of black beans), rounded up to whole packs where a food is sold in multiples; read buy_text to
        the user rather than bare grams. Foods with no known pack size are sold loose by weight and
        say so. Pantry staples (oil, rice, pasta, oats...) are not quantified: present check_stock
        as one "check stock — may need replenishment" line. Text-only meals and foods
        that cannot be converted are listed under unresolved, never guessed. Meals from
        use_up templates consume existing inventory and are excluded. No pantry subtraction yet:
        tell the user to strike items they already have.
        """
        from .grocery import grocery_list as build
        return tool_result(lambda: build(mcp_workspace().store, mcp_workspace().catalog, week_start))

    catalog_write = ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=False)

    @mcp.tool(annotations=catalog_write)
    def upsert_food(food_id: str, food: dict, expected_revision: int) -> CallToolResult:
        """Add or edit a planning food; expected_revision is 0 for new, else the revision from meal_planning_context.

        food: name, per100g {kcal, proteinG, carbohydrateG, fatG}, role (protein/carb/fat/fixed),
        optional basis (cooked/raw/dry/as_sold), step_g, batch_cook,
        purchase {form, cooked_yield, unit {name, plural?, grams, pack? {name, plural?, count}}, staple}, source, active.
        purchase.unit is how it is bought — {'name': 'egg', 'grams': 50} or
        {'name': '1 kg bag of dry rice', 'grams': 1000} — and makes grocery lists count units
        instead of grams; omit it for foods weighed loose at the counter. Add unit.pack when the
        unit is only sold in multiples, e.g. {'name': 'dozen', 'plural': 'dozen', 'count': 12} for eggs. staple true marks a
        shelf-stable pantry item that grocery lists only flag to check stock. Set active false to stop drafting it; true to switch it back on.
        Prefer label values when the user has them and name the label in source. Saved plans keep their snapshots.
        """
        return tool_result(lambda: mcp_workspace(write=True).catalog.upsert('food', food_id, food, expected_revision))

    @mcp.tool(annotations=catalog_write)
    def upsert_template(template_id: str, template: dict, expected_revision: int) -> CallToolResult:
        """Add or edit a meal template; expected_revision is 0 for new, else the revision from meal_planning_context.

        template: title, slots (breakfast/lunch/dinner/recovery-snack/pre-training), items
        [{food_id, grams, min_g?, max_g?}], optional batch (covered by prep), use_up (swap only,
        never rotated or replenished), active. Grams are starting amounts that daily balancing scales.
        """
        return tool_result(lambda: mcp_workspace(write=True).catalog.upsert('template', template_id, template, expected_revision))

    @mcp.tool(annotations=read)
    def read_records(entity_type: str | None = None) -> CallToolResult:
        """Read saved activities, food_entries, weight_entries or meal_plans; no inferred completions."""
        return tool_result(lambda: mcp_workspace().store.records(entity_type))

    @mcp.tool(annotations=read)
    def read_changes(after: int = 0, limit: int = 100) -> CallToolResult:
        """Read revision history and tombstones after an exclusive cursor."""
        return tool_result(lambda: mcp_workspace().store.changes(after, limit))

    @mcp.tool(annotations=read)
    def nutrition_totals(local_date: str) -> CallToolResult:
        """Known daily nutrient subtotal and missing-value counts; not an intake target."""
        return tool_result(lambda: _nutrition(mcp_workspace(), local_date))

    @mcp.tool(annotations=read)
    def read_meal_plan(local_date: str) -> CallToolResult:
        """Read the saved dated meal plan and targets, or null when none is saved.

        To edit, pass its full payload and current revision to apply_command with
        entity_type meal_plans and entity_id meal-plan-YYYY-MM-DD. Never log consumption.
        """
        return tool_result(lambda: mcp_workspace().store.meal_plan(local_date))

    @mcp.tool(annotations=read)
    def read_meal_plans(date_from: str, date_to: str) -> CallToolResult:
        """Read saved meal plans in an inclusive date range (at most 31 days).

        Missing/deleted dates are omitted, not filled from a repeating template.
        Update individual records with apply_command and their accepted revisions.
        """
        return tool_result(lambda: mcp_workspace().store.meal_plans(date_from, date_to))

    @mcp.tool(annotations=read)
    def list_tracker_workouts(date_from: str, date_to: str) -> CallToolResult:
        """Read source-owned Polar workout summaries and tombstones by Chicago start date.

        Compare recorded durations, HR statistics and coverage across sessions. Imported
        workouts never imply planned completion. Missing HR is unknown, not rest or zero.
        No cortisol, CNS readiness or recovery diagnosis can be inferred from this data.
        """
        return tool_result(lambda: mcp_workspace().trackers.list(date_from, date_to))

    @mcp.tool(annotations=read)
    def read_tracker_workout(source_key: str, zone_boundaries_bpm: list[int] | None = None) -> CallToolResult:
        """Read a Polar workout's HR curve, provenance, coverage and fixed time windows.

        The curve preserves bucket extrema with at most 600 points; summary metrics use
        original samples. Covered HR uses adjacent sample gaps <=60s without extrapolation.
        Windows are elapsed time, never assumed rounds. Named HR zones use the user-confirmed
        Polar screenshot cutoffs, with provenance; computed totals may differ from Polar.
        Optional custom time bands remain separate. Do not infer max HR from observed peak.
        Training benefit is unavailable. No cortisol/CNS/readiness claims.
        """
        return tool_result(lambda: mcp_workspace().trackers.read(source_key, zone_boundaries_bpm))

    @mcp.tool(annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=True, idempotentHint=True, openWorldHint=False))
    def apply_command(command: dict) -> CallToolResult:
        """Explicitly save/correct/delete a record using the same revision-checked app command.

        Requires command_id, entity_type, entity_id, expected_revision, operation (upsert/delete), payload.
        meal_plans are dated planned meals, not consumed food; edits never add food_entries.
        Use interpret_food for an editable model proposal, then explicitly save food_entries
        only when the user wants to log consumption. Preserve prior payload fields on correction.
        """
        return tool_result(lambda: mcp_workspace(write=True).store.command(command))

    proposal = ToolAnnotations(readOnlyHint=True, destructiveHint=False,
                               idempotentHint=False, openWorldHint=True)

    @mcp.tool(name="interpret_food", annotations=proposal)
    async def interpret_food_tool(text: str) -> CallToolResult:
        """Reuse exact comma-separated food estimates; send only uncached items to the model. Returns an editable draft, never logs consumption."""
        return await model_result(mcp_workspace().food_cache.food({"text": text}))


    async def _oversized_model_request():
        raise DomainError("request_too_large", 413)

    # --- app API ------------------------------------------------------------------------

    def _nutrition(space, local_date):
        return space.store.nutrition(local_date, plan=space.plan())

    async def read_json(request, limit=262144):
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > limit:
                raise DomainError("request_too_large", 413)
        try:
            return json.loads(body)
        except (ValueError, UnicodeDecodeError, RecursionError):
            raise DomainError("invalid_json", 400) from None

    async def command(request):
        return JSONResponse(workspace(request, write=True).store.command(await read_json(request)))

    async def tracker_import(request):
        return JSONResponse(workspace(request, write=True).trackers.ingest(await read_json(request, 4194304)))

    async def tracker_workouts(request):
        return JSONResponse(workspace(request).trackers.list(request.query_params.get("date_from"),
                                                             request.query_params.get("date_to")))

    async def reminder_copy(request):
        return JSONResponse(await workspace(request).reminder_copy.copy(await read_json(request, 16384)))

    async def interpret_food(request):
        return JSONResponse(await workspace(request).food_cache.food(await read_json(request, 40000)))





    async def changes(request):
        try:
            after = int(request.query_params.get("after", 0))
            limit = int(request.query_params.get("limit", 100))
        except ValueError:
            raise DomainError("invalid_pagination")
        return JSONResponse(workspace(request).store.changes(after, limit))

    async def records(request):
        return JSONResponse(workspace(request).store.records(request.query_params.get("entity_type")))

    async def nutrition(request):
        return JSONResponse(_nutrition(workspace(request), request.query_params.get("local_date")))

    async def read_account(request):
        principal = principal_of(request)
        if accounts is not None and accounts.users.active(principal['user_id']) is None:
            raise DomainError('unauthorized', 401)
        return JSONResponse({'user_id': principal['user_id']})

    async def read_plan(request):
        space = workspace(request)
        found = principal_of(request)
        account = accounts.users.read(found["user_id"]) if accounts else None
        return JSONResponse({**space.plans.read(),
                             "account": {"user_id": found["user_id"],
                                         "username": account["username"] if account else None,
                                         "display_name": account["display_name"] if account else None}})

    async def write_plan(request):
        body = await read_json(request, 262144)
        if not isinstance(body, dict) or set(body) != {"plan", "expected_revision"}:
            raise DomainError("invalid_training_plan_request", 422, fields=["plan", "expected_revision"])
        return JSONResponse(workspace(request, write=True).plans.write(body["plan"], body["expected_revision"]))

    async def plan_sessions(request):
        body = await read_json(request, 16384)
        if not isinstance(body, dict) or set(body) != {"date_from", "date_to"}:
            raise DomainError("invalid_plan_date_range", 422, fields=["date_from", "date_to"])
        space = workspace(request, write=True)
        return JSONResponse(generate_sessions(space.store, space.plan(), body["date_from"], body["date_to"]))

    async def domain_error(request, exc):
        return JSONResponse(exc.body, exc.status)

    @asynccontextmanager
    async def lifespan(app):
        async with mcp.session_manager.run():
            yield

    routes = [Route("/v1/account", read_account), Route("/v1/commands", command, methods=["POST"]),
              Route("/v1/changes", changes), Route("/v1/records", records),
              Route("/v1/plan", read_plan), Route("/v1/plan", write_plan, methods=["PUT"]),
              Route("/v1/plan/sessions", plan_sessions, methods=["POST"]),
              Route("/v1/tracker/import", tracker_import, methods=["POST"]),
              Route("/v1/tracker/workouts", tracker_workouts),
              Route("/v1/interpret/food", interpret_food, methods=["POST"]),
              Route("/v1/reminders/copy", reminder_copy, methods=["POST"]),
              Route("/v1/nutrition", nutrition)]
    if accounts is not None:
        sessions = BrowserSessions(accounts, issuer)
        site = AccountSite(accounts, workspaces, sessions, allow_signup=allow_signup, issuer=issuer)
        routes = [Route("/", lambda request: RedirectResponse("/account/", status_code=303)),
                  *OAuthServer(accounts, sessions, issuer).routes(), *site.routes(), *routes]
    app = Starlette(routes=[*routes, Mount("/", mcp.streamable_http_app())],
                    lifespan=lifespan, exception_handlers={DomainError: domain_error})
    app.state.mcp = mcp
    return TrustedHostMiddleware(AuthGate(BodyLimit(app), accounts, static, origins, issuer), allowed_hosts=hosts)


def create_app(store, api_token, mcp_token, public_hosts=(), model_service=None,
               monthly_tokens=DEFAULT_MONTHLY_TOKENS, account_monthly_tokens=DEFAULT_ACCOUNT_MONTHLY_TOKENS):
    """The single-account service: one database, static bearer tokens, no account site.

    Kept for the already enrolled deployment and its tests. New deployments use create_service,
    which adds accounts, OAuth sign-in and the account site over the same handlers.
    """
    if len(api_token) < 32 or len(mcp_token) < 32 or api_token == mcp_token:
        raise ValueError("Separate app and MCP tokens of at least 32 characters are required")
    models = model_service or ModelService.from_environment()
    workspaces = FixedWorkspace(OWNER, store, models, monthly_tokens, account_monthly_tokens)
    return _build(workspaces, None, public_hosts,
                  static=dict(user_id=OWNER, tokens={"api": api_token, "mcp": mcp_token}))


def create_service(data_dir, public_hosts=(), model_service=None, static_tokens=None,
                   static_username=None, issuer=None, allow_signup=False, accounts=None,
                   monthly_tokens=DEFAULT_MONTHLY_TOKENS, account_monthly_tokens=DEFAULT_ACCOUNT_MONTHLY_TOKENS):
    """The multi-account service: accounts, OAuth, the account site and per-account workspaces.

    static_tokens keeps an already enrolled client working during migration: it authenticates
    as the named account and nothing else. Signing up is closed unless allow_signup is set,
    because every account spends the deployment's own model budget; --create-account always works.
    monthly_tokens is that budget: one allowance shared by every account; account_monthly_tokens
    caps any one account's share of it.
    """
    models = model_service or ModelService.from_environment()
    accounts = accounts or _accounts(data_dir)
    workspaces = Workspaces(accounts, models, monthly_tokens, account_monthly_tokens)
    static = None
    if static_tokens:
        user = accounts.users.by_username(static_username or "")
        if user is None:
            raise ValueError(f"EUROS_STATIC_ACCOUNT must name an existing account; {static_username!r} not found")
        static = dict(user_id=user["user_id"], tokens=static_tokens)
    return _build(workspaces, accounts, public_hosts, static=static, issuer=issuer, allow_signup=allow_signup)


# --- administration -------------------------------------------------------------------

def _read_password(prompt, environment="EUROS_NEW_PASSWORD"):
    value = os.environ.get(environment)
    if value:
        return value
    first = getpass.getpass(prompt)
    if first != getpass.getpass("Repeat password: "):
        raise ValueError("Passwords did not match")
    return first


def _data_dir():
    from .database import configured_database_url
    directory = configured_database_url() or os.environ.get("EUROS_DATA_DIR")
    if not directory:
        if os.environ.get("EUROS_DB_PATH"):
            raise ValueError("Set EUROS_DATA_DIR to the account data directory. An existing single-account "
                             "database is adopted once with --adopt-database; it is never moved automatically.")
        raise ValueError("Set EUROS_DATABASE_URL_FILE, EUROS_DATABASE_URL or EUROS_DATA_DIR")
    return directory if directory.startswith("postgresql") else Path(directory)


def _account_database(data_dir, user_id):
    from .database import is_postgres
    return Store(data_dir if is_postgres(data_dir) else Path(data_dir) / "euros.db", user_id)


def _adopt(accounts, data_dir, source, username):
    from .storage_migration import import_training
    source = Path(source)
    if not source.is_file():
        raise ValueError(f"No database at {source}")
    user = accounts.users.by_username(username)
    if user is None:
        raise ValueError('Create the destination account before adopting its database')
    from .database import is_postgres
    if is_postgres(accounts.path):
        from .postgres_migration import import_snapshot
        counts = import_snapshot(accounts.path, source, user['user_id'])
        destination = 'PostgreSQL'
    else:
        counts = import_training(accounts.path, source, user['user_id'])
        destination = str(accounts.path)
    return {"username":user['username'], "user_id":user['user_id'], "database":destination,
            "source":str(source), "rows":counts, "note":"The source database was copied, not deleted."}


def _monthly_tokens(flag, environment, default):
    value = flag if flag is not None else os.environ.get(environment, default)
    try:
        tokens = int(value)
    except (TypeError, ValueError):
        raise ValueError(f"{environment} must be a whole number of tokens") from None
    if tokens < 0:
        raise ValueError("The model token budget cannot be negative")
    return tokens


def _named_account(accounts, username):
    user = accounts.users.by_username(username)
    if user is None:
        raise ValueError(f"No account named {username}")
    return user


def _accounts(data_dir):
    from .database import is_postgres
    if is_postgres(data_dir):
        return AccountStore(data_dir)
    root = Path(data_dir)
    if not (root / 'euros.db').exists() and ((root / 'accounts.db').exists() or (root / 'users').exists()):
        raise ValueError('Run --consolidate-databases before starting the shared database service')
    return AccountStore(root / 'euros.db')


def main():
    parser = argparse.ArgumentParser(description="Euros backend: serve, or administer accounts.")
    parser.add_argument("--consolidate-databases", action="store_true", help="With the service stopped, copy accounts.db and per-user databases into one euros.db")
    parser.add_argument("--import-plan", help="Explicitly import an authored JSON array of activity commands, then exit")
    parser.add_argument("--create-account", metavar="USERNAME", help="Create an account and exit")
    parser.add_argument("--set-password", metavar="USERNAME", help="Set an account's password and exit")
    parser.add_argument("--list-accounts", action="store_true", help="List accounts and exit")
    parser.add_argument("--account-report", action="store_true",
                        help="Show every account's activity and this month's model spend, then exit")
    parser.add_argument("--account-details", metavar="USERNAME", help="Show one account's apps, data and spend, then exit")
    parser.add_argument("--disable-account", metavar="USERNAME", help="Stop an account signing in or using its apps, then exit")
    parser.add_argument("--enable-account", metavar="USERNAME", help="Let a disabled account sign in again, then exit")
    parser.add_argument("--delete-account", metavar="USERNAME",
                        help="Permanently delete an account and its data; needs --confirm USERNAME")
    parser.add_argument("--confirm", metavar="USERNAME", help="Repeat the username for --delete-account")
    parser.add_argument("--model-token-budget", type=int, metavar="TOKENS",
                        help="Monthly model tokens shared by all accounts "
                             f"(default EUROS_MODEL_TOKEN_BUDGET or {DEFAULT_MONTHLY_TOKENS:,})")
    parser.add_argument("--account-token-budget", type=int, metavar="TOKENS",
                        help="Monthly model tokens any one account may use "
                             f"(default EUROS_ACCOUNT_TOKEN_BUDGET or {DEFAULT_ACCOUNT_MONTHLY_TOKENS:,})")
    parser.add_argument("--adopt-database", metavar="PATH", help="Copy an existing single-account database to --username and exit")
    parser.add_argument("--username", help="Account for --import-plan or --adopt-database")
    parser.add_argument("--display-name", help="Display name for --create-account")
    parser.add_argument("--port", type=int, default=3003)
    parser.add_argument("--public-host", action="append", default=[])
    parser.add_argument("--public-url", help="Public base URL announced in OAuth metadata, e.g. https://euros.example")
    parser.add_argument("--allow-signup", action="store_true",
                        help="Let visitors create their own accounts on the account site")
    args = parser.parse_args()
    data_dir = _data_dir()
    if args.consolidate_databases:
        from .database import is_postgres
        if is_postgres(data_dir):
            raise ValueError('--consolidate-databases is only for the legacy SQLite directory layout')
        from .storage_migration import consolidate
        print(json.dumps(consolidate(data_dir), indent=2))
        return
    accounts = _accounts(data_dir)
    monthly_tokens = _monthly_tokens(args.model_token_budget, "EUROS_MODEL_TOKEN_BUDGET", DEFAULT_MONTHLY_TOKENS)
    account_tokens = _monthly_tokens(args.account_token_budget, "EUROS_ACCOUNT_TOKEN_BUDGET",
                                     DEFAULT_ACCOUNT_MONTHLY_TOKENS)

    if args.list_accounts:
        print(json.dumps([accounts.users.read(user_id) for user_id in accounts.users.ids()], indent=2))
        return
    if args.create_account:
        user = accounts.users.create(args.create_account, _read_password(f"Password for {args.create_account}: "),
                                     args.display_name)
        _account_database(data_dir, user["user_id"])
        print(json.dumps(user))
        return
    if args.set_password:
        user = accounts.users.by_username(args.set_password)
        if user is None:
            raise ValueError(f"No account named {args.set_password}")
        accounts.users.reset_password(user["user_id"], _read_password("New password: "))
        accounts.sessions.end_all(user["user_id"])
        print(json.dumps({"username": user["username"], "password_changed": True}))
        return
    if args.account_report or args.account_details:
        from .operator_view import account_details, account_report
        budget = TokenBudget(accounts.path, monthly_tokens, account_tokens)
        report = (account_report(accounts, budget) if args.account_report
                  else account_details(accounts, budget, args.account_details))
        print(json.dumps(report, indent=2))
        return
    if args.disable_account or args.enable_account:
        user = _named_account(accounts, args.disable_account or args.enable_account)
        changed = accounts.users.set_disabled(user["user_id"], bool(args.disable_account))
        if args.disable_account:
            accounts.sessions.end_all(user["user_id"])
        print(json.dumps({"username": changed["username"], "disabled": changed["disabled"]}))
        return
    if args.delete_account:
        user = _named_account(accounts, args.delete_account)
        if args.confirm != user["username"]:
            raise ValueError("--delete-account permanently removes the account; repeat its username with --confirm")
        from .account_data import delete_account
        print(json.dumps(delete_account(accounts.path, user["user_id"]), indent=2))
        return
    if args.adopt_database:
        if not args.username:
            raise ValueError("--adopt-database requires --username")
        print(json.dumps(_adopt(accounts, data_dir, args.adopt_database, args.username), indent=2))
        return
    if args.import_plan:
        if not args.username:
            raise ValueError("--import-plan requires --username")
        user = accounts.users.by_username(args.username)
        if user is None:
            raise ValueError(f"No account named {args.username}")
        store = _account_database(data_dir, user["user_id"])
        result = store.import_plan(json.loads(Path(args.import_plan).read_text()))
        print(json.dumps({"accepted": len(result["results"]), "username": user["username"]}))
        return

    static_tokens = None
    static_username = os.environ.get("EUROS_STATIC_ACCOUNT")
    if static_username:
        static_tokens = {"api": load_token("EUROS_API_TOKEN"), "mcp": load_token("EUROS_MCP_TOKEN")}
        if static_tokens["api"] == static_tokens["mcp"]:
            raise ValueError("Separate app and MCP tokens are required")
    import uvicorn
    app = create_service(data_dir, args.public_host, static_tokens=static_tokens,
                         static_username=static_username, issuer=args.public_url,
                         allow_signup=args.allow_signup, monthly_tokens=monthly_tokens,
                         account_monthly_tokens=account_tokens)
    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False)


if __name__ == "__main__":
    main()
