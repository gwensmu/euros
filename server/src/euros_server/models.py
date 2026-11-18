"""SQLAlchemy mappings for the shared database and account-owned application data."""
from sqlalchemy import BigInteger, Integer, CheckConstraint, ForeignKey, Index, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    type_annotation_map = {int: BigInteger().with_variant(Integer, "sqlite")}


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

# Authentication uses SQLAlchemy Core transactions in the same database. These tables
# are not exposed through account domain sessions.
from sqlalchemy import Table, Column, String, Integer, Float

login_failures = Table('login_failures', Base.metadata,
    Column('username', String, nullable=False), Column('at', Float, nullable=False))
Index('login_failures_at', login_failures.c.at)
sessions = Table('sessions', Base.metadata,
    Column('session_hash', String, primary_key=True), Column('user_id', ForeignKey('users.user_id'), nullable=False),
    Column('csrf', String, nullable=False), Column('created_at', Float, nullable=False), Column('expires_at', Float, nullable=False))
oauth_clients = Table('oauth_clients', Base.metadata,
    Column('client_id', String, primary_key=True), Column('secret_hash', String), Column('name', String, nullable=False),
    Column('uris', Text, nullable=False), Column('auth_method', String, nullable=False), Column('created_at', Float, nullable=False),
    Column('registered', Integer, nullable=False))
oauth_grants = Table('oauth_grants', Base.metadata,
    Column('grant_id', String, primary_key=True), Column('user_id', ForeignKey('users.user_id'), nullable=False),
    Column('client_id', ForeignKey('oauth_clients.client_id'), nullable=False), Column('scope', String, nullable=False),
    Column('created_at', Float, nullable=False), Column('revoked', Integer, nullable=False, server_default='0'))
oauth_codes = Table('oauth_codes', Base.metadata,
    Column('code_hash', String, primary_key=True), Column('client_id', ForeignKey('oauth_clients.client_id'), nullable=False),
    Column('user_id', ForeignKey('users.user_id'), nullable=False), Column('redirect_uri', String, nullable=False),
    Column('challenge', String, nullable=False), Column('scope', String, nullable=False), Column('resource', String),
    Column('grant_id', ForeignKey('oauth_grants.grant_id'), nullable=False), Column('expires_at', Float, nullable=False),
    Column('used', Integer, nullable=False, server_default='0'))
oauth_tokens = Table('oauth_tokens', Base.metadata,
    Column('token_hash', String, primary_key=True), Column('kind', String, nullable=False),
    Column('grant_id', ForeignKey('oauth_grants.grant_id'), nullable=False),
    Column('user_id', ForeignKey('users.user_id'), nullable=False), Column('client_id', ForeignKey('oauth_clients.client_id'), nullable=False),
    Column('scope', String, nullable=False), Column('created_at', Float, nullable=False), Column('expires_at', Float, nullable=False),
    Column('revoked', Integer, nullable=False, server_default='0'))
Index('oauth_tokens_grant', oauth_tokens.c.grant_id)
imported_databases = Table('imported_databases', Base.metadata,
    Column('user_id', ForeignKey('users.user_id'), primary_key=True), Column('source', Text, nullable=False),
    Column('imported_at', String, nullable=False))
retired_account_data = Table('retired_account_data', Base.metadata,
    Column('user_id', ForeignKey('users.user_id'), nullable=False), Column('table_name', String, nullable=False),
    Column('row_json', Text, nullable=False))
# Deployment-wide model spend. Deliberately not an account-owned table and not a foreign key:
# deleting an account removes its data but must not refund the deployment's monthly budget.
# Rows hold token counts and a kind, never user text or model output.
model_usage = Table('model_usage', Base.metadata,
    Column('id', Integer, primary_key=True, autoincrement=True), Column('user_id', String, nullable=False),
    Column('at', Float, nullable=False), Column('month', String, nullable=False), Column('kind', String, nullable=False),
    Column('tokens', Integer, nullable=False), Column('measured', Integer, nullable=False, server_default='0'))
Index('model_usage_month', model_usage.c.month, model_usage.c.user_id)
