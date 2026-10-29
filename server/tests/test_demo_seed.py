from datetime import date
import importlib.util
from pathlib import Path
import pytest
from euros_server.domain import Store

spec=importlib.util.spec_from_file_location('seed_demo',Path(__file__).resolve().parents[2]/'scripts/seed_demo.py')
seed_demo=importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed_demo)

def test_demo_is_explicit_persistent_and_never_overwrites(tmp_path):
    path=tmp_path/'demo.sqlite3'
    assert seed_demo.seed(path,date(2026,6,8))==19
    store=Store(path)
    before=store.records()
    assert len(before['records'])==19
    assert len(store.records('meal_plans')['records'])==7
    assert len(store.records('food_entries')['records'])==3
    with pytest.raises(FileExistsError):seed_demo.seed(path,date(2026,7,6))
    assert Store(path).records()==before

def test_demo_dates_are_explicit_and_ids_deterministic():
    first=seed_demo.commands(date(2026,6,8))
    assert first==seed_demo.commands(date(2026,6,8))
    assert len({c['entity_id'] for c in first})==len(first)
    assert all(c['command_id'].startswith('demo-create-') for c in first)
