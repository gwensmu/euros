import copy
import json

import pytest
from starlette.testclient import TestClient
from euros_server.app import create_app
from euros_server.domain import DomainError, Store
from euros_server.llm import weekly_facts
from datetime import date


def plan():
    return {"command_id":"seed-meal-plan", "entity_type":"meal_plans", "entity_id":"meal-plan-2026-10-19",
            "expected_revision":0,"operation":"upsert","payload":{
                "date":"2026-10-19","reference_day":"A",
                "nutrition_targets":{"kcal":1900,"proteinG":130,"carbohydrateG":225,"fatG":{"min":50,"max":53}},
                "meals":[{"id":"breakfast","title":"Breakfast","proteinG":30,"carbohydrateG":45,"fatG":None,
                          "localTime":None,"foods":["2 eggs", "Toast"],"appliesWhen":"every_day","sourceRef":"authored"}]}}


@pytest.fixture
def store(tmp_path):return Store(tmp_path/"db")


def test_saved_plan_revision_edits_restart_and_no_intake(store):
    original=plan();saved=store.command(original)
    assert store.command(original)==saved
    edit=copy.deepcopy(original)
    edit.update(command_id="edit-plan",expected_revision=1)
    edit["payload"]["meals"][0]["foods"]=["Oats"]
    accepted=store.command(edit)
    assert accepted["record"]["revision"]==2
    assert Store(store.path).meal_plan("2026-10-19")["record"]==accepted["record"]
    assert store.nutrition("2026-10-19")["entry_count"]==0
    assert store.records("food_entries")["records"]==[]
    with pytest.raises(DomainError,match="revision_conflict"):
        store.command({**edit,"command_id":"stale", "expected_revision":0})
    assert store.meal_plan("2026-10-20")["record"] is None
    assert store.changes()["changes"][0]["payload"]["meals"][0]["foods"]==["2 eggs","Toast"]


def test_deleted_plan_tombstone_does_not_fall_back_or_resurrect(store):
    c=plan();store.command(c)
    store.command({**c,"command_id":"delete-plan","expected_revision":1,"operation":"delete","payload":{}})
    assert store.meal_plan("2026-10-19")["record"] is None
    with pytest.raises(DomainError,match="record_deleted"):
        store.command({**c,"command_id":"recreate","expected_revision":2})


@pytest.mark.parametrize("mutation",[
    lambda p:p.update(date="20261019"),
    lambda p:p.update(reference_day=""),
    lambda p:p.update(meals=[]),
    lambda p:p["meals"].append(copy.deepcopy(p["meals"][0])),
    lambda p:p["meals"][0].update(foods=[""]),
    lambda p:p["meals"][0].update(foods="eggs"),
    lambda p:p["meals"][0].update(localTime="8:30"),
    lambda p:p["meals"][0].update(appliesWhen="random"),
    lambda p:p["nutrition_targets"].update(fatG={"min":53,"max":50}),
    lambda p:p["nutrition_targets"].update(kcal=True),
    lambda p:p["nutrition_targets"].update(kcal=200001),
    lambda p:p["nutrition_targets"].update(kcal="NaN"),
])
def test_invalid_plan_atomic_rejection(store,mutation):
    c=plan();mutation(c["payload"])
    with pytest.raises(DomainError):store.command(c)
    assert store.changes()["changes"]==[]


def test_date_identity_and_read_date_validation(store):
    c=plan();c["entity_id"]="another-id"
    with pytest.raises(DomainError,match="meal_plan_identity_mismatch"):store.command(c)
    for value in [None,"20261019","../plan.json"]:
        with pytest.raises(DomainError,match="invalid_local_date"):store.meal_plan(value)


def test_plan_is_not_logged_food_in_weekly_facts(store):
    record=store.command(plan())["record"]
    facts,coverage=weekly_facts({"week_start":"2026-10-19","records":[record]},date(2026,10,25))
    assert facts["food_days"]==[] and coverage["food_days"]==0


def test_mcp_read_update_same_revisioned_record(store):
    with TestClient(create_app(store,"a"*40,"b"*40),base_url="http://localhost") as client:
        headers={"Authorization":"Bearer "+"b"*40,"Accept":"application/json, text/event-stream"}
        def rpc(method,params):
            result=client.post("/mcp",headers=headers,json={"jsonrpc":"2.0","id":1,"method":method,"params":params})
            assert result.status_code==200,result.text
            return result.json()["result"]
        rpc("initialize",{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}})
        saved=rpc("tools/call",{"name":"apply_command","arguments":{"command":plan()}})
        assert not saved.get("isError"),saved
        read_result=rpc("tools/call",{"name":"read_meal_plan","arguments":{"local_date":"2026-10-19"}})
        assert not read_result.get("isError"),read_result
        read=read_result.get("structuredContent") or json.loads(read_result["content"][0]["text"])
        assert read["record"]==saved["structuredContent"]["record"]
        edit=plan();edit.update(command_id="mcp-edit",expected_revision=1)
        edit["payload"]["meals"][0]["foods"]=["Yogurt"]
        updated=rpc("tools/call",{"name":"apply_command","arguments":{"command":edit}})
        assert not updated.get("isError")
        assert store.meal_plan("2026-10-19")["record"]["payload"]["meals"][0]["foods"]==["Yogurt"]
        listed=client.get("/v1/records?entity_type=meal_plans",headers={"Authorization":"Bearer "+"a"*40}).json()
        assert listed["records"]==[updated["structuredContent"]["record"]]
        assert store.nutrition("2026-10-19")["entry_count"]==0


def test_bounded_dated_range_only_saved_records(store):
    store.command(plan())
    assert len(store.meal_plans("2026-10-19","2026-10-25")["records"])==1
    assert store.meal_plans("2026-10-20","2026-10-25")["records"]==[]
    for start,end in [("20261019","2026-10-25"),("2026-10-25","2026-10-19"),("2026-10-01","2026-11-01")]:
        with pytest.raises(DomainError,match="invalid_plan_date_range"):store.meal_plans(start,end)
