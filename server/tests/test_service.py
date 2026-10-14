import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path

import pytest
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import DomainError, Store

API = "app-" + "x" * 40
MCP = "mcp-" + "y" * 40

@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / "euros.db")

def command(id="one", revision=0, **payload):
    return dict(command_id=f"{id}-{revision}", entity_type="activities", entity_id=id,
                expected_revision=revision, operation="upsert",
                payload={"title": "Practice", "kind": "bjj", "scheduled_at": "2026-10-14T18:00:00-05:00", "status": "pending", **payload})


def test_retry_conflict_and_restart(store):
    cmd = command()
    first = store.command(cmd)
    assert store.command(cmd) == first
    assert Store(store.path).records()["records"] == [first["record"]]
    changed = command(status="done")
    with pytest.raises(DomainError) as err:
        store.command(changed)
    assert err.value.body["error"] == "idempotency_key_reused"
    changed["command_id"] = "different-command"
    with pytest.raises(DomainError) as err:
        store.command(changed)
    assert err.value.body["current"] == first["record"]
    assert len(store.changes()["changes"]) == 1


def test_concurrent_retry_once(store):
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda _: store.command(command()), range(12)))
    assert all(x == results[0] for x in results)
    assert len(store.changes()["changes"]) == 1


def test_delete_change_pagination_and_no_resurrection(store):
    store.command(command())
    store.command(command(revision=1, status="done"))
    deletion = command(revision=2)
    deletion.update(operation="delete", payload={})
    deleted = store.command(deletion)
    assert deleted["record"]["deleted"]
    assert store.command(deletion) == deleted
    assert store.records()["records"] == []
    page = store.changes(limit=2)
    assert page["has_more"] and page["next_cursor"] == 2
    assert store.changes(after=2)["changes"][0]["deleted"]
    with pytest.raises(DomainError, match="record_deleted"):
        store.command(command(revision=3))


def food(id="meal", rev=0, kcal="100.1", protein=None):
    return dict(command_id=f"{id}-{rev}", entity_type="food_entries", entity_id=id, expected_revision=rev,
                operation="upsert", payload={"consumed_at": "2026-10-14T12:00:00-05:00", "local_date": "2026-10-14",
                "nutrients": {"kcal": kcal, "protein_g": protein, "carbs_g": 0, "fat_g": None}, "raw_text": "User provided totals"})


def test_decimal_unknown_correct_delete(store):
    store.command(food())
    store.command(food("meal2", kcal="0.2", protein="5"))
    n = store.nutrition("2026-10-14")
    assert n["nutrients"]["kcal"] == {"known_total": "100.3", "missing_count": 0}
    assert n["nutrients"]["protein_g"] == {"known_total": "5", "missing_count": 1}
    store.command(food(rev=1, kcal="50"))
    assert store.nutrition("2026-10-14")["nutrients"]["kcal"]["known_total"] == "50.2"
    delete = food(rev=2)
    delete.update(operation="delete", payload={})
    store.command(delete)
    assert store.nutrition("2026-10-14")["entry_count"] == 1


@pytest.mark.parametrize("value", [-1, "NaN", "Infinity", True])
def test_reject_invalid_quantity(store, value):
    with pytest.raises(DomainError):
        store.command(food(kcal=value))
    assert not store.changes()["changes"]


def test_requires_schedule_and_offset(store):
    for time in [None, "2026-10-14T18:00:00"]:
        with pytest.raises(DomainError):
            store.command(command(scheduled_at=time))


def test_http_auth_and_mcp_parity(store):
    app = create_app(store, API, MCP)
    with TestClient(app, base_url="http://localhost") as client:
        assert client.get("/v1/records").status_code == 401
        assert client.get("/v1/records", headers={"Authorization": f"Bearer {MCP}"}).status_code == 401
        headers = {"Authorization": f"Bearer {API}"}
        first = client.post("/v1/commands", headers=headers, json=command()).json()
        assert first["record"]["revision"] == 1
        assert client.post("/v1/commands", headers=headers, json=command()).json() == first
        assert client.get("/v1/records", headers={**headers, "Origin": "https://evil.example"}).status_code == 403
        assert client.get("/v1/records", headers={**headers, "Host": "evil.example"}).status_code == 400
        assert client.post("/v1/commands", headers=headers, content="{").status_code == 400
        mh = {"Authorization": f"Bearer {MCP}", "Accept": "application/json, text/event-stream"}
        def rpc(method, params, id=1):
            return client.post("/mcp", headers=mh, json={"jsonrpc": "2.0", "id": id, "method": method, "params": params})
        init = rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
        assert init.status_code == 200, init.text
        result = rpc("tools/call", {"name": "apply_command", "arguments": {"command": command(revision=1, status="done")}}, 2)
        assert result.status_code == 200, result.text
        assert not result.json()["result"].get("isError"), result.text
        assert client.get("/v1/records", headers=headers).json()["records"][0]["payload"]["status"] == "done"
        reads = rpc("tools/call", {"name": "read_records", "arguments": {}}, 3).json()["result"]
        body = reads.get("structuredContent") or json.loads(reads["content"][0]["text"])
        assert body == client.get("/v1/records", headers=headers).json()
        failed = rpc("tools/call", {"name": "apply_command", "arguments": {"command": {**command(), "command_id": "mcp-stale"}}}, 4).json()["result"]
        assert failed["isError"] is True
        assert "revision_conflict" in failed["content"][0]["text"]
        client.post("/v1/commands", headers=headers, json=food())
        mcp_totals = rpc("tools/call", {"name": "nutrition_totals", "arguments": {"local_date": "2026-10-14"}}, 5).json()["result"]
        totals = mcp_totals.get("structuredContent") or json.loads(mcp_totals["content"][0]["text"])
        assert totals == client.get("/v1/nutrition?local_date=2026-10-14", headers=headers).json()
        stale = client.post("/v1/commands", headers=headers, json={**command(), "command_id": "stale"})
        assert stale.status_code == 409 and stale.json()["current"]["revision"] == 2


def test_contract_fixtures(store):
    root = Path(__file__).resolve().parents[2] / "contracts"
    for filename in ["activity-create.json", "activity-complete.json", "food-partial.json"]:
        store.command(json.loads((root / filename).read_text()))
    assert store.nutrition("2026-10-14")["entry_count"] == 1


def test_atomic_plan_import_and_retry(store):
    bad = command("bad", scheduled_at=None)
    with pytest.raises(DomainError):
        store.import_plan([command(), bad])
    assert store.records()["records"] == []
    plan = [command(), command("two")]
    first = store.import_plan(plan)
    assert store.import_plan(plan) == first
    assert len(store.changes()["changes"]) == 2


def test_schema_guard(tmp_path):
    import sqlite3
    path = tmp_path / "future.db"
    with sqlite3.connect(path) as db:
        db.execute("PRAGMA user_version=99")
    with pytest.raises(RuntimeError, match="Unsupported database schema"):
        Store(path)
    with sqlite3.connect(path) as db:
        assert db.execute("PRAGMA user_version").fetchone()[0] == 99


def test_bad_types_are_validation_errors(store):
    for field in ["entity_type", "operation"]:
        with pytest.raises(DomainError):
            store.command({**command(), field: []})
    with pytest.raises(DomainError):
        store.command(command(kind=[]))


def test_weight_and_date_validation(store):
    c = {"command_id": "weight", "entity_type": "weight_entries", "entity_id": "weight", "expected_revision": 0,
         "operation": "upsert", "payload": {"measured_at": "2026-10-14T07:00:00-05:00", "local_date": "2026-10-14", "weight_lb": "164.2"}}
    assert store.command(c)["record"]["payload"]["weight_lb"] == "164.2"
    with pytest.raises(DomainError):
        store.command({**food(), "payload": {**food()["payload"], "local_date": "2026-10-15"}})


def test_numeric_and_date_boundaries(store):
    for value in ["1e999999999", "0.0000001", "1000000001"]:
        with pytest.raises(DomainError):
            store.command(food(kcal=value))
    with pytest.raises(DomainError):
        store.changes(after=10 ** 30)
    with pytest.raises(DomainError):
        store.command(command(revision=10 ** 30))
    c = food()
    c["payload"]["local_date"] = "20261014"
    with pytest.raises(DomainError):
        store.command(c)
    with pytest.raises(DomainError):
        store.nutrition("20261014")


def test_recovery_intake_and_rest(store):
    c = command(kind="rest")
    store.command(c)
    base = command("water", kind="recovery", recovery_step_type="hydration")
    store.command(base)
    with pytest.raises(DomainError, match="actual_intake_required"):
        store.command(command("water",1,kind="recovery",recovery_step_type="hydration",status="done"))
    for intake in [{"kind":"hydration","amount":0,"unit":"ml"}, {"kind":"hydration","amount":500,"unit":"oz"}, {"kind":"supplement","amount":300,"unit":"mg"}]:
        with pytest.raises(DomainError):
            store.command(command("water",1,kind="recovery",recovery_step_type="hydration",status="done",recovery_intake=intake))
    done=command("water",1,kind="recovery",recovery_step_type="hydration",status="done",recovery_intake={"kind":"hydration","amount":500,"unit":"ml"})
    result=store.command(done)
    assert store.command(done)==result
    assert result["record"]["payload"]["recovery_intake"]["amount"]==500
    with pytest.raises(DomainError, match="invalid_recovery_intake"):
        store.command(command("water",2,kind="recovery",recovery_step_type="hydration",status="skipped",recovery_intake={"kind":"hydration","amount":500,"unit":"ml"}))


def test_supplement_requires_explicit_product_and_unit(store):
    for intake in [{"kind":"supplement","amount":300,"unit":"mg"}, {"kind":"supplement","amount":300,"unit":"g","product":"label"}]:
        with pytest.raises(DomainError):
            store.command(command("supp",kind="recovery",recovery_step_type="supplement",status="done",recovery_intake=intake))
    result=store.command(command("supp",kind="recovery",recovery_step_type="supplement",status="done",recovery_intake={"kind":"supplement","amount":300,"unit":"mg","product":"Elemental magnesium label"}))
    assert result["record"]["revision"]==1


def test_weekly_weight_unique_anchor_clear_then_select(store):
    def weight(id,rev=0,anchor="2026-10-12"):
        payload={"measured_at":"2026-10-18T08:00:00-05:00","local_date":"2026-10-18","weight_lb":"164.2"}
        if anchor: payload["weekly_anchor"]=anchor
        return dict(command_id=f"{id}-{rev}",entity_type="weight_entries",entity_id=id,expected_revision=rev,operation="upsert",payload=payload)
    store.command(weight("one"))
    with pytest.raises(DomainError,match="weekly_anchor_already_selected"):
        store.command(weight("two"))
    store.command(weight("one",1,None))
    store.command(weight("two"))
    for anchor in ["2026-10-13","2026-10-05","20261012"]:
        with pytest.raises(DomainError,match="invalid_weekly_anchor"):
            store.command(weight("bad",anchor=anchor))


def test_imported_weight_source_identity_cannot_duplicate_or_resurrect(store):
    def c(id,rev=0):
        return dict(command_id=f"{id}-{rev}",entity_type="weight_entries",entity_id=id,expected_revision=rev,operation="upsert",payload={"measured_at":"2026-10-18T08:00:00-05:00","local_date":"2026-10-18","weight_lb":"164.2","source_key":"tracker:weight:one"})
    store.command(c("first"))
    with pytest.raises(DomainError,match="imported_measurement_already_saved"):
        store.command(c("second"))
    delete=c("first",1);delete.update(operation="delete",payload={});store.command(delete)
    with pytest.raises(DomainError,match="imported_measurement_already_saved"):
        store.command(c("third"))


@pytest.mark.parametrize("entity_type", [[], {}, 1, True])
def test_record_filter_rejects_non_string_types(store, entity_type):
    with pytest.raises(DomainError, match="invalid_entity_type"):
        store.records(entity_type)


def test_malformed_origin_is_denied_without_decoding_failure(store):
    with TestClient(create_app(store, API, MCP), base_url="http://localhost") as client:
        response = client.get("/v1/records", headers=[
            (b"authorization", f"Bearer {API}".encode()),
            (b"origin", b"https://\xff.example"),
        ])
        assert response.status_code == 403
        assert response.json() == {"error": "origin_denied"}


def weight_command(identifier, source, anchor="2026-10-12"):
    return dict(command_id=identifier, entity_type="weight_entries", entity_id=identifier,
                expected_revision=0, operation="upsert", payload={
                    "measured_at": "2026-10-18T08:00:00-05:00", "local_date": "2026-10-18",
                    "weight_lb": "164.2", "source_key": source, "weekly_anchor": anchor,
                })


def test_deleted_weight_releases_anchor_but_preserves_import_identity(store):
    original = weight_command("original", "tracker:one")
    store.command(original)
    store.command({**original, "command_id": "delete", "expected_revision": 1,
                   "operation": "delete", "payload": {}})
    store.command(weight_command("replacement", "tracker:two"))
    with pytest.raises(DomainError, match="imported_measurement_already_saved"):
        store.command(weight_command("duplicate", "tracker:one", anchor=None))
    assert len(store.changes()["changes"]) == 3


def test_concurrent_weight_selection_has_one_winner(store):
    def select(identifier):
        try:
            return store.command(weight_command(identifier, f"tracker:{identifier}"))
        except DomainError as error:
            return error.body

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(select, ["first", "second"]))
    assert sum("record" in result for result in results) == 1
    assert {result["error"] for result in results if "error" in result} == {"weekly_anchor_already_selected"}
    assert len(store.changes()["changes"]) == 1
