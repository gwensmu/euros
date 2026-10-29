"""Transactional canonical records. No fixtures or model-generated values enter this store."""
import hashlib
import json
import sqlite3
from contextlib import contextmanager, nullcontext
from datetime import date, datetime, timezone, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from .nutrition_policy import resolve as resolve_nutrition

TYPES = {"activities", "food_entries", "weight_entries", "meal_plans"}
NUTRIENTS = ("kcal", "protein_g", "carbs_g", "fat_g")
MAX_SQLITE_INTEGER = (1 << 63) - 1


class DomainError(Exception):
    def __init__(self, error, status=422, **details):
        self.status, self.body = status, {"error": error, **details}
        super().__init__(error)


def canonical(value):
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)
    except (ValueError, TypeError, RecursionError):
        raise DomainError("invalid_json_value")


def number(value, name, positive=False):
    try:
        if isinstance(value, bool) or value is None:
            raise ValueError()
        n = Decimal(str(value))
        if not n.is_finite() or n < 0 or n > Decimal("1000000000") or n.as_tuple().exponent < -6 or (positive and n == 0):
            raise ValueError()
        return n
    except (InvalidOperation, ValueError):
        raise DomainError("invalid_quantity", field=name)


def instant(value, name):
    try:
        d = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if d.utcoffset() is None:
            raise ValueError()
        return d
    except (ValueError, TypeError, AttributeError):
        raise DomainError("offset_datetime_required", field=name)


def plan_quantity(value, field):
    """Preserve authored unknowns and ranges; never turn a plan into intake."""
    if value is None:
        return
    if isinstance(value, dict):
        if set(value) != {"min", "max"}:
            raise DomainError("invalid_plan_quantity", field=field)
        low, high = number(value["min"], field), number(value["max"], field)
        if low > high or high > 200000:
            raise DomainError("invalid_plan_quantity", field=field)
    elif number(value, field) > 200000:
        raise DomainError("invalid_plan_quantity", field=field)


def validate_meal_plan(payload):
    try:
        day = date.fromisoformat(payload["date"])
        if day.isoformat() != payload["date"]:
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise DomainError("invalid_plan_date") from None
    reference = payload.get("reference_day")
    if not isinstance(reference, str) or not 1 <= len(reference.strip()) <= 80:
        raise DomainError("invalid_reference_day")
    targets = payload.get("nutrition_targets")
    if not isinstance(targets, dict) or set(targets) != {"kcal", "proteinG", "carbohydrateG", "fatG"}:
        raise DomainError("invalid_nutrition_targets")
    for key, value in targets.items():
        plan_quantity(value, key)
    meals = payload.get("meals")
    if not isinstance(meals, list) or not 1 <= len(meals) <= 30:
        raise DomainError("invalid_plan_meals")
    identities = set()
    for meal in meals:
        if not isinstance(meal, dict):
            raise DomainError("invalid_plan_meal")
        for key, maximum in (("id", 100), ("title", 200)):
            if not isinstance(meal.get(key), str) or not 1 <= len(meal[key].strip()) <= maximum:
                raise DomainError("invalid_plan_meal", field=key)
        if meal["id"] in identities:
            raise DomainError("duplicate_plan_meal")
        identities.add(meal["id"])
        foods = meal.get("foods")
        if not isinstance(foods, list) or len(foods) > 30 or any(not isinstance(food, str) or not 1 <= len(food.strip()) <= 1000 for food in foods):
            raise DomainError("invalid_plan_foods")
        for key in ("kcal", "proteinG", "carbohydrateG", "fatG"):
            plan_quantity(meal.get(key), key)
        local_time = meal.get("localTime")
        if local_time is not None:
            from datetime import time
            try:
                if not isinstance(local_time, str) or time.fromisoformat(local_time).strftime("%H:%M") != local_time:
                    raise ValueError()
            except ValueError:
                raise DomainError("invalid_meal_time") from None
        if meal.get("appliesWhen") not in ("every_day", "bjj_day"):
            raise DomainError("invalid_meal_applicability")


def validate_payload(kind, p):
    if not isinstance(p, dict):
        raise DomainError("object_payload_required")
    canonical(p)
    if kind == "meal_plans":
        validate_meal_plan(p)
    elif kind == "activities":
        if not isinstance(p.get("title"), str) or not p["title"].strip():
            raise DomainError("title_required")
        if not isinstance(p.get("kind"), str) or p.get("kind") not in {"bjj", "weights", "food", "recovery", "morning", "weight", "rest", "prep"}:
            raise DomainError("invalid_activity_kind")
        instant(p.get("scheduled_at"), "scheduled_at")
        if not isinstance(p.get("status"), str) or p.get("status") not in {"pending", "done", "skipped"}:
            raise DomainError("invalid_activity_status")
        intake = p.get("recovery_intake")
        substance = p.get("recovery_step_type") in ("hydration", "supplement")
        if p.get("recovery_step_type") == "supplement" and p["status"] == "done" and intake is None:
            raise DomainError("actual_intake_required")
        if intake is not None:
            if not isinstance(intake, dict) or p["kind"] != "recovery" or p["status"] != "done":
                raise DomainError("invalid_recovery_intake")
            number(intake.get("amount"), "amount", positive=True)
            intake_kind = intake.get("kind")
            if intake_kind == "hydration":
                if intake.get("unit") not in ("ml", "l", "us_fl_oz"):
                    raise DomainError("invalid_hydration_unit")
            elif intake_kind == "supplement":
                if intake.get("unit") != "mg" or not isinstance(intake.get("product"), str) or not intake["product"].strip():
                    raise DomainError("supplement_product_and_mg_required")
            else:
                raise DomainError("invalid_intake_kind")
            if substance and intake_kind != p["recovery_step_type"]:
                raise DomainError("intake_kind_mismatch")
    else:
        actual = instant(p.get("consumed_at" if kind == "food_entries" else "measured_at"), "actual_time")
        try:
            local_day = date.fromisoformat(p["local_date"])
            if local_day.isoformat() != p["local_date"] or actual.date() != local_day:
                raise ValueError()
        except (KeyError, TypeError, ValueError):
            raise DomainError("local_date_required")
        if kind == "food_entries":
            nutrients = p.get("nutrients")
            if not isinstance(nutrients, dict) or set(nutrients) != set(NUTRIENTS):
                raise DomainError("four_nullable_nutrients_required")
            for k, v in nutrients.items():
                if v is not None:
                    number(v, k)
            # This slice accepts explicit totals/snapshots only; never totals + item calculations.
            if p.get("items"):
                raise DomainError("itemized_nutrition_not_supported_yet")
        else:
            number(p.get("weight_lb"), "weight_lb", positive=True)
            if p.get("source_key") is not None and (not isinstance(p["source_key"], str) or not p["source_key"].strip()):
                raise DomainError("invalid_source_key")
            if p.get("weekly_anchor") is not None:
                try:
                    anchor = date.fromisoformat(p["weekly_anchor"])
                    if anchor.isoformat() != p["weekly_anchor"] or anchor.weekday() != 0 or not anchor <= local_day <= anchor + timedelta(days=6):
                        raise ValueError()
                except (ValueError, TypeError):
                    raise DomainError("invalid_weekly_anchor")


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            version = db.execute("PRAGMA user_version").fetchone()[0]
            if version not in (0, 1):
                raise RuntimeError(f"Unsupported database schema {version}")
            db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS records (
                  entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, revision INTEGER NOT NULL,
                  deleted INTEGER NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL,
                  PRIMARY KEY(entity_type, entity_id));
                CREATE TABLE IF NOT EXISTS changes (
                  cursor INTEGER PRIMARY KEY AUTOINCREMENT, record TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS commands (
                  command_id TEXT PRIMARY KEY, digest TEXT NOT NULL, result TEXT NOT NULL);
                PRAGMA user_version=1;
            ''')

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def record(row):
        if row is None:
            return None
        return {**dict(row), "deleted": bool(row["deleted"]), "payload": json.loads(row["payload"])}

    def command(self, c, _db=None):
        if not isinstance(c, dict):
            raise DomainError("object_command_required")
        if set(c) != {"command_id", "entity_type", "entity_id", "expected_revision", "operation", "payload"}:
            raise DomainError("invalid_command_fields")
        for key in ("command_id", "entity_id"):
            if not isinstance(c[key], str) or not 1 <= len(c[key]) <= 200:
                raise DomainError("invalid_identifier", field=key)
        if not isinstance(c["entity_type"], str) or not isinstance(c["operation"], str) or c["entity_type"] not in TYPES or c["operation"] not in {"upsert", "delete"}:
            raise DomainError("unsupported_operation")
        if type(c["expected_revision"]) is not int or not 0 <= c["expected_revision"] < MAX_SQLITE_INTEGER:
            raise DomainError("invalid_revision")
        encoded = canonical(c).encode()
        if len(encoded) > 262144:
            raise DomainError("request_too_large", 413)
        digest = hashlib.sha256(encoded).hexdigest()
        with (self.connect() if _db is None else nullcontext(_db)) as db:
            if _db is None:
                db.execute("BEGIN IMMEDIATE")
            prior = db.execute("SELECT * FROM commands WHERE command_id=?", (c["command_id"],)).fetchone()
            if prior:
                if prior["digest"] != digest:
                    raise DomainError("idempotency_key_reused", 409)
                return json.loads(prior["result"])
            current = self.record(db.execute("SELECT * FROM records WHERE entity_type=? AND entity_id=?",
                                            (c["entity_type"], c["entity_id"])).fetchone())
            if c["expected_revision"] != (current["revision"] if current else 0):
                raise DomainError("revision_conflict", 409, current=current)
            if c["operation"] == "delete":
                if current is None:
                    raise DomainError("record_not_found", 404)
                if c["payload"] != {}:
                    raise DomainError("delete_payload_must_be_empty")
                payload = current["payload"]
            else:
                validate_payload(c["entity_type"], c["payload"])
                payload = c["payload"]
                if c["entity_type"] == "meal_plans" and c["entity_id"] != "meal-plan-" + payload["date"]:
                    raise DomainError("meal_plan_identity_mismatch")
                if c["entity_type"] == "weight_entries":
                    self._validate_weight_identity(db, c["entity_id"], payload)
                # Resurrection requires a separately designed explicit restore operation.
                if current and current["deleted"]:
                    raise DomainError("record_deleted", 409, current=current)
            record = {"entity_type": c["entity_type"], "entity_id": c["entity_id"],
                      "revision": c["expected_revision"] + 1, "deleted": c["operation"] == "delete",
                      "payload": payload, "updated_at": datetime.now(timezone.utc).isoformat()}
            db.execute("INSERT OR REPLACE INTO records VALUES (?,?,?,?,?,?)",
                       (record["entity_type"], record["entity_id"], record["revision"], record["deleted"],
                        canonical(payload), record["updated_at"]))
            cursor = db.execute("INSERT INTO changes(record) VALUES (?)", (canonical(record),)).lastrowid
            result = {"record": record, "cursor": cursor}
            db.execute("INSERT INTO commands VALUES (?,?,?)", (c["command_id"], digest, canonical(result)))
            return result

    @staticmethod
    def _validate_weight_identity(db, entity_id, payload):
        """Anchors are unique among live records; imported identities survive deletion."""
        anchor, source = payload.get("weekly_anchor"), payload.get("source_key")
        if not anchor and not source:
            return
        rows = db.execute(
            "SELECT deleted,payload FROM records WHERE entity_type='weight_entries' AND entity_id<>?",
            (entity_id,),
        ).fetchall()
        others = [(bool(row["deleted"]), json.loads(row["payload"])) for row in rows]
        if anchor and any(not deleted and p.get("weekly_anchor") == anchor for deleted, p in others):
            raise DomainError("weekly_anchor_already_selected")
        if source and any(p.get("source_key") == source for _, p in others):
            raise DomainError("imported_measurement_already_saved")

    def changes(self, after=0, limit=100):
        if type(after) is not int or not 0 <= after <= MAX_SQLITE_INTEGER or type(limit) is not int or not 1 <= limit <= 500:
            raise DomainError("invalid_pagination")
        with self.connect() as db:
            rows = db.execute("SELECT * FROM changes WHERE cursor>? ORDER BY cursor LIMIT ?", (after, limit + 1)).fetchall()
        changes = [{**json.loads(r["record"]), "cursor": r["cursor"]} for r in rows[:limit]]
        return {"changes": changes, "next_cursor": changes[-1]["cursor"] if changes else after,
                "has_more": len(rows) > limit}

    def records(self, entity_type=None):
        if entity_type is not None and (not isinstance(entity_type, str) or entity_type not in TYPES):
            raise DomainError("invalid_entity_type")
        with self.connect() as db:
            rows = db.execute("SELECT * FROM records WHERE deleted=0 AND (? IS NULL OR entity_type=?) ORDER BY entity_type,entity_id",
                              (entity_type, entity_type)).fetchall()
        return {"records": [self.record(r) for r in rows]}

    def meal_plan(self, local_date):
        try:
            if date.fromisoformat(local_date).isoformat() != local_date:
                raise ValueError()
        except (TypeError, ValueError):
            raise DomainError("invalid_local_date") from None
        with self.connect() as db:
            row = db.execute("SELECT * FROM records WHERE entity_type='meal_plans' AND entity_id=? AND deleted=0",
                             ("meal-plan-" + local_date,)).fetchone()
        return {"local_date": local_date, "record": self.record(row)}

    def meal_plans(self, date_from, date_to):
        try:
            start, end = date.fromisoformat(date_from), date.fromisoformat(date_to)
            if start.isoformat() != date_from or end.isoformat() != date_to or not 0 <= (end - start).days <= 30:
                raise ValueError()
        except (TypeError, ValueError):
            raise DomainError("invalid_plan_date_range") from None
        records = self.records("meal_plans")["records"]
        return {"date_from": date_from, "date_to": date_to,
                "records": [record for record in records if date_from <= record["payload"]["date"] <= date_to]}

    def nutrition(self, local_date):
        try:
            if date.fromisoformat(local_date).isoformat() != local_date:
                raise ValueError()
        except (TypeError, ValueError):
            raise DomainError("invalid_local_date")
        entries = [r for r in self.records("food_entries")["records"] if r["payload"]["local_date"] == local_date]
        totals = {}
        for k in NUTRIENTS:
            values = [r["payload"]["nutrients"][k] for r in entries]
            totals[k] = {"known_total": str(sum((Decimal(str(v)) for v in values if v is not None), Decimal(0))),
                         "missing_count": sum(v is None for v in values)}
        saved = self.meal_plan(local_date)["record"]
        policy = resolve_nutrition(self.records("weight_entries")["records"], local_date, saved["payload"]["nutrition_targets"] if saved else None)
        return {"local_date": local_date, "entry_count": len(entries), "nutrients": totals, **policy}

    def import_plan(self, commands):
        """Explicit local authoring import; all scheduled changes accepted atomically."""
        if not isinstance(commands, list) or not commands or len(commands) > 500:
            raise DomainError("plan_requires_1_to_500_commands")
        if any(not isinstance(c, dict) or c.get("entity_type") != "activities" for c in commands):
            raise DomainError("plan_contains_non_activity")
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            return {"results": [self.command(c, _db=db) for c in commands]}
