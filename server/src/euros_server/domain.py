"""Transactional canonical records. No fixtures or model-generated values enter this store."""
from sqlalchemy import select, func
from .models import RecordRow, Change, Command
import hashlib
import json
from contextlib import nullcontext
from datetime import date, datetime, time, timezone, timedelta
from decimal import Decimal, InvalidOperation
from .database import LEGACY_OWNER, connect, initialize, account_session
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


MACROS = ("kcal", "proteinG", "carbohydrateG", "fatG")
# A planned portion; larger values are authoring mistakes, not meals.
MAX_ITEM_GRAMS = 5000


def parse_local_date(value, error):
    """A strict YYYY-MM-DD local date, or DomainError(error)."""
    try:
        day = date.fromisoformat(value)
        if day.isoformat() != value:
            raise ValueError()
    except (TypeError, ValueError):
        raise DomainError(error) from None
    return day


def _validate_item(item):
    """A structured ingredient: what grocery lists and prep aggregate, so it must be usable.

    Extra fields are preserved (agents may annotate); the fields others compute from are checked.
    """
    if not isinstance(item, dict):
        raise DomainError("invalid_plan_item")
    food_id = item.get("food_id")
    if not isinstance(food_id, str) or not 1 <= len(food_id) <= 60:
        raise DomainError("invalid_plan_item", field="food_id")
    if "grams" not in item or number(item["grams"], "grams", positive=True) > MAX_ITEM_GRAMS:
        raise DomainError("invalid_plan_item", field="grams", food_id=food_id)
    if "food_revision" in item and (type(item["food_revision"]) is not int or item["food_revision"] < 1):
        raise DomainError("invalid_plan_item", field="food_revision", food_id=food_id)
    if "per100g" in item:
        per = item["per100g"]
        if not isinstance(per, dict) or set(per) != set(MACROS):
            raise DomainError("invalid_plan_item", field="per100g", food_id=food_id)
        for key in MACROS:
            number(per[key], key)


def _validate_meal(meal):
    if not isinstance(meal, dict):
        raise DomainError("invalid_plan_meal")
    for key, maximum in (("id", 100), ("title", 200)):
        if not isinstance(meal.get(key), str) or not 1 <= len(meal[key].strip()) <= maximum:
            raise DomainError("invalid_plan_meal", field=key)
    foods = meal.get("foods")
    if not isinstance(foods, list) or len(foods) > 30 or any(not isinstance(food, str) or not 1 <= len(food.strip()) <= 1000 for food in foods):
        raise DomainError("invalid_plan_foods")
    for key in MACROS:
        plan_quantity(meal.get(key), key)
    local_time = meal.get("localTime")
    if local_time is not None:
        try:
            if not isinstance(local_time, str) or time.fromisoformat(local_time).strftime("%H:%M") != local_time:
                raise ValueError()
        except ValueError:
            raise DomainError("invalid_meal_time") from None
    if meal.get("appliesWhen") not in ("every_day", "bjj_day"):
        raise DomainError("invalid_meal_applicability")
    items = meal.get("items")
    if items is not None:
        if not isinstance(items, list) or len(items) > 30:
            raise DomainError("invalid_plan_items", meal_id=meal["id"])
        for item in items:
            _validate_item(item)
    for flag in ("batch", "use_up"):
        if flag in meal and not isinstance(meal[flag], bool):
            raise DomainError("invalid_plan_meal", field=flag)


def _validate_meals(meals, error):
    if not isinstance(meals, list) or not 1 <= len(meals) <= 30:
        raise DomainError(error)
    identities = set()
    for meal in meals:
        _validate_meal(meal)
        if meal["id"] in identities:
            raise DomainError("duplicate_plan_meal")
        identities.add(meal["id"])


def validate_meal_plan(payload):
    if not isinstance(payload, dict):
        raise DomainError("invalid_plan_date")
    parse_local_date(payload.get("date"), "invalid_plan_date")
    reference = payload.get("reference_day")
    if not isinstance(reference, str) or not 1 <= len(reference.strip()) <= 80:
        raise DomainError("invalid_reference_day")
    targets = payload.get("nutrition_targets")
    if not isinstance(targets, dict) or set(targets) != set(MACROS):
        raise DomainError("invalid_nutrition_targets")
    for key, value in targets.items():
        plan_quantity(value, key)
    _validate_meals(payload.get("meals"), "invalid_plan_meals")


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
    def __init__(self, path, user_id=LEGACY_OWNER):
        if not isinstance(user_id, str) or not user_id.startswith('usr-') or len(user_id) != 36 or any(c not in '0123456789abcdef' for c in user_id[4:]):
            raise ValueError('Invalid account identifier')
        self.path, self.user_id = str(path), user_id
        initialize(self.path, legacy_owner=user_id)

    def connect(self):
        return connect(self.path)

    def session(self, write=False):
        return account_session(self.path, self.user_id, write=write)

    @staticmethod
    def record(row):
        if row is None:
            return None
        return {"entity_type": row.entity_type, "entity_id": row.entity_id, "revision": row.revision,
                "deleted": bool(row.deleted), "payload": json.loads(row.payload), "updated_at": row.updated_at}

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
        if _db is not None and _db.user_id != self.user_id:
            raise DomainError('account_context_mismatch', 403)
        encoded = canonical(c).encode()
        if len(encoded) > 262144:
            raise DomainError("request_too_large", 413)
        digest = hashlib.sha256(encoded).hexdigest()
        with (self.session(write=True) if _db is None else nullcontext(_db)) as db:
            prior = db.get(Command, (self.user_id, c['command_id']))
            if prior:
                if prior.digest != digest:
                    raise DomainError("idempotency_key_reused", 409)
                return json.loads(prior.result)
            current = self.record(db.get(RecordRow, (self.user_id, c['entity_type'], c['entity_id'])))
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
                if (c["entity_type"] == "activities" and current and payload.get("kind") in {"bjj", "weights"}
                        and current["payload"].get("scheduled_at") != payload.get("scheduled_at")):
                    payload = dict(payload)
                    moves = list(payload.get("move_history", current["payload"].get("move_history", [])))
                    old, new = current["payload"]["scheduled_at"], payload["scheduled_at"]
                    if not moves or moves[-1].get("from") != old or moves[-1].get("to") != new:
                        moves.append({"from": old, "to": new, "changed_at": datetime.now(timezone.utc).isoformat()})
                    moves[-1] = {**moves[-1], "adjust_nutrition": True}
                    payload["move_history"] = moves
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
            db.merge(RecordRow(user_id=self.user_id, **{**record, "deleted": int(record["deleted"]), "payload": canonical(payload)}))
            cursor = (db.scalar(select(func.max(Change.cursor))) or 0) + 1
            db.add(Change(user_id=self.user_id, cursor=cursor, record=canonical(record)))
            result = {"record": record, "cursor": cursor}
            db.add(Command(user_id=self.user_id, command_id=c["command_id"], digest=digest, result=canonical(result)))
            return result

    @staticmethod
    def _validate_weight_identity(db, entity_id, payload):
        """Anchors are unique among live records; imported identities survive deletion."""
        anchor, source = payload.get("weekly_anchor"), payload.get("source_key")
        if not anchor and not source:
            return
        rows = db.scalars(select(RecordRow).where(RecordRow.entity_type == "weight_entries", RecordRow.entity_id != entity_id)).all()
        others = [(bool(row.deleted), json.loads(row.payload)) for row in rows]
        if anchor and any(not deleted and p.get("weekly_anchor") == anchor for deleted, p in others):
            raise DomainError("weekly_anchor_already_selected")
        if source and any(p.get("source_key") == source for _, p in others):
            raise DomainError("imported_measurement_already_saved")

    def changes(self, after=0, limit=100):
        if type(after) is not int or not 0 <= after <= MAX_SQLITE_INTEGER or type(limit) is not int or not 1 <= limit <= 500:
            raise DomainError("invalid_pagination")
        with self.session() as db:
            rows = db.scalars(select(Change).where(Change.cursor > after).order_by(Change.cursor).limit(limit + 1)).all()
        changes = [{**json.loads(r.record), "cursor": r.cursor} for r in rows[:limit]]
        return {"changes": changes, "next_cursor": changes[-1]["cursor"] if changes else after,
                "has_more": len(rows) > limit}

    def records(self, entity_type=None):
        if entity_type is not None and (not isinstance(entity_type, str) or entity_type not in TYPES):
            raise DomainError("invalid_entity_type")
        with self.session() as db:
            rows = db.scalars(select(RecordRow).where(RecordRow.deleted == 0, True if entity_type is None else RecordRow.entity_type == entity_type).order_by(RecordRow.entity_type, RecordRow.entity_id)).all()
        return {"records": [self.record(r) for r in rows]}

    def meal_plan(self, local_date):
        parse_local_date(local_date, "invalid_local_date")
        with self.session() as db:
            row = db.scalar(select(RecordRow).where(RecordRow.entity_type == "meal_plans", RecordRow.entity_id == "meal-plan-" + local_date, RecordRow.deleted == 0))
        return {"local_date": local_date, "record": self.record(row)}

    def meal_plans(self, date_from, date_to):
        start, end = parse_local_date(date_from, "invalid_plan_date_range"), parse_local_date(date_to, "invalid_plan_date_range")
        if not 0 <= (end - start).days <= 30:
            raise DomainError("invalid_plan_date_range")
        records = self.records("meal_plans")["records"]
        return {"date_from": date_from, "date_to": date_to,
                "records": [record for record in records if date_from <= record["payload"]["date"] <= date_to]}

    def nutrition(self, local_date, plan=None):
        """Known intake and the day's targets. plan is the account's training plan, else defaults."""
        parse_local_date(local_date, "invalid_local_date")
        entries = [r for r in self.records("food_entries")["records"] if r["payload"]["local_date"] == local_date]
        totals = {}
        for k in NUTRIENTS:
            values = [r["payload"]["nutrients"][k] for r in entries]
            totals[k] = {"known_total": str(sum((Decimal(str(v)) for v in values if v is not None), Decimal(0))),
                         "missing_count": sum(v is None for v in values)}
        saved = self.meal_plan(local_date)["record"]
        policy = resolve_nutrition(self.records("weight_entries")["records"] + self.records("activities")["records"],
                                   local_date, saved["payload"]["nutrition_targets"] if saved else None, plan=plan)
        return {"local_date": local_date, "entry_count": len(entries), "nutrients": totals, **policy}

    def import_plan(self, commands):
        """Explicit local authoring import; all scheduled changes accepted atomically."""
        if not isinstance(commands, list) or not commands or len(commands) > 500:
            raise DomainError("plan_requires_1_to_500_commands")
        if any(not isinstance(c, dict) or c.get("entity_type") != "activities" for c in commands):
            raise DomainError("plan_contains_non_activity")
        with self.session(write=True) as db:
            return {"results": [self.command(c, _db=db) for c in commands]}
