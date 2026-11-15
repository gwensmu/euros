"""SQLAlchemy mappings for the shared database and account-owned application data."""
from sqlalchemy import CheckConstraint, ForeignKey, Index, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = 'users'
    user_id: Mapped[str] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(unique=True)
    display_name: Mapped[str]
    password_hash: Mapped[str]
    created_at: Mapped[str]
    password_changed_at: Mapped[str]
    disabled: Mapped[int] = mapped_column(default=0, server_default='0')


class Owned:
    user_id: Mapped[str] = mapped_column(ForeignKey('users.user_id'), primary_key=True, sort_order=-100)


class RecordRow(Owned, Base):
    __tablename__ = 'records'
    entity_type: Mapped[str] = mapped_column(primary_key=True)
    entity_id: Mapped[str] = mapped_column(primary_key=True)
    revision: Mapped[int]
    deleted: Mapped[int]
    payload: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[str]


class Change(Owned, Base):
    __tablename__ = 'changes'
    cursor: Mapped[int] = mapped_column(primary_key=True)
    record: Mapped[str] = mapped_column(Text)


class Command(Owned, Base):
    __tablename__ = 'commands'
    command_id: Mapped[str] = mapped_column(primary_key=True)
    digest: Mapped[str]
    result: Mapped[str] = mapped_column(Text)


class TrackerWorkout(Owned, Base):
    __tablename__ = 'tracker_workouts'
    source_key: Mapped[str] = mapped_column(primary_key=True)
    revision: Mapped[int]
    deleted: Mapped[int]
    payload: Mapped[str] = mapped_column(Text)
    observed_at: Mapped[str]
    received_at: Mapped[str]


class TrackerCommand(Owned, Base):
    __tablename__ = 'tracker_commands'
    command_id: Mapped[str] = mapped_column(primary_key=True)
    digest: Mapped[str]
    result: Mapped[str] = mapped_column(Text)


class CatalogItem(Owned, Base):
    __tablename__ = 'catalog_items'
    kind: Mapped[str] = mapped_column(primary_key=True)
    id: Mapped[str] = mapped_column(primary_key=True)
    revision: Mapped[int]
    payload: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[str]


class CatalogHistory(Owned, Base):
    __tablename__ = 'catalog_history'
    cursor: Mapped[int] = mapped_column(primary_key=True)
    kind: Mapped[str]
    id: Mapped[str]
    revision: Mapped[int]
    payload: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[str]


class MealDraft(Owned, Base):
    __tablename__ = 'meal_week_drafts'
    draft_id: Mapped[str] = mapped_column(primary_key=True)
    created_at: Mapped[str]
    body: Mapped[str] = mapped_column(Text)


class FoodEstimate(Owned, Base):
    __tablename__ = 'food_item_cache'
    key: Mapped[str] = mapped_column(primary_key=True)
    input: Mapped[str] = mapped_column(Text)
    result: Mapped[str] = mapped_column(Text)


class ReminderEstimate(Owned, Base):
    __tablename__ = 'reminder_copy_cache'
    key: Mapped[str] = mapped_column(primary_key=True)
    result: Mapped[str] = mapped_column(Text)
    created_at: Mapped[str]


class InferenceRequest(Owned, Base):
    __tablename__ = 'inference_requests'
    # Internal sequence; not exposed as an account's sync cursor.
    user_id: Mapped[str] = mapped_column(ForeignKey('users.user_id'), sort_order=-100)
    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    at: Mapped[float]
    day: Mapped[str]
    kind: Mapped[str]
    __table_args__ = (Index('inference_requests_owner_at','user_id','at'),
                      Index('inference_requests_owner_day','user_id','day','kind'))


class TrainingPlan(Owned, Base):
    __tablename__ = 'training_plan'
    id: Mapped[int] = mapped_column(primary_key=True)
    revision: Mapped[int]
    payload: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[str]
    __table_args__ = (CheckConstraint('id=1'),)


OWNED_TABLES = {m.local_table.name: m.local_table for m in Base.registry.mappers if issubclass(m.class_, Owned)}
