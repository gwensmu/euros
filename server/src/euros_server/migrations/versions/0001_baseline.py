"""Baseline: the schema as created before versioned migrations.

Generated from the models on November 19, 2026, against an empty PostgreSQL database. A
database created earlier by SQLAlchemy create_all is adopted at this revision by
`euros-server --migrate` only when its schema matches this one exactly.

Revision ID: 0001
Revises:
Create Date: 2026-11-19
"""
from alembic import op
import sqlalchemy as sa


revision = '0001'
down_revision = None
branch_labels = None
depends_on = None


def upgrade():
    op.create_table('login_failures',
    sa.Column('username', sa.String(), nullable=False),
    sa.Column('at', sa.Float(), nullable=False)
    )
    op.create_index('login_failures_at', 'login_failures', ['at'], unique=False)
    op.create_table('model_usage',
    sa.Column('id', sa.Integer(), autoincrement=True, nullable=False),
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('at', sa.Float(), nullable=False),
    sa.Column('month', sa.String(), nullable=False),
    sa.Column('kind', sa.String(), nullable=False),
    sa.Column('tokens', sa.Integer(), nullable=False),
    sa.Column('measured', sa.Integer(), server_default='0', nullable=False),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('model_usage_month', 'model_usage', ['month', 'user_id'], unique=False)
    op.create_table('oauth_clients',
    sa.Column('client_id', sa.String(), nullable=False),
    sa.Column('secret_hash', sa.String(), nullable=True),
    sa.Column('name', sa.String(), nullable=False),
    sa.Column('uris', sa.Text(), nullable=False),
    sa.Column('auth_method', sa.String(), nullable=False),
    sa.Column('created_at', sa.Float(), nullable=False),
    sa.Column('registered', sa.Integer(), nullable=False),
    sa.PrimaryKeyConstraint('client_id')
    )
    op.create_table('users',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('username', sa.String(), nullable=False),
    sa.Column('display_name', sa.String(), nullable=False),
    sa.Column('password_hash', sa.String(), nullable=False),
    sa.Column('created_at', sa.String(), nullable=False),
    sa.Column('password_changed_at', sa.String(), nullable=False),
    sa.Column('disabled', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), server_default='0', nullable=False),
    sa.PrimaryKeyConstraint('user_id'),
    sa.UniqueConstraint('username')
    )
    op.create_table('catalog_history',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('cursor', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('kind', sa.String(), nullable=False),
    sa.Column('id', sa.String(), nullable=False),
    sa.Column('revision', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('payload', sa.Text(), nullable=False),
    sa.Column('updated_at', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'cursor')
    )
    op.create_table('catalog_items',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('kind', sa.String(), nullable=False),
    sa.Column('id', sa.String(), nullable=False),
    sa.Column('revision', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('payload', sa.Text(), nullable=False),
    sa.Column('updated_at', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'kind', 'id')
    )
    op.create_table('changes',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('cursor', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('record', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'cursor')
    )
    op.create_table('commands',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('command_id', sa.String(), nullable=False),
    sa.Column('digest', sa.String(), nullable=False),
    sa.Column('result', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'command_id')
    )
    op.create_table('food_item_cache',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('key', sa.String(), nullable=False),
    sa.Column('input', sa.Text(), nullable=False),
    sa.Column('result', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'key')
    )
    op.create_table('imported_databases',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('source', sa.Text(), nullable=False),
    sa.Column('imported_at', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id')
    )
    op.create_table('inference_requests',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('id', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), autoincrement=True, nullable=False),
    sa.Column('at', sa.Double(), nullable=False),
    sa.Column('day', sa.String(), nullable=False),
    sa.Column('kind', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('id')
    )
    op.create_index('inference_requests_owner_at', 'inference_requests', ['user_id', 'at'], unique=False)
    op.create_index('inference_requests_owner_day', 'inference_requests', ['user_id', 'day', 'kind'], unique=False)
    op.create_table('meal_week_drafts',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('draft_id', sa.String(), nullable=False),
    sa.Column('created_at', sa.String(), nullable=False),
    sa.Column('body', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'draft_id')
    )
    op.create_table('oauth_grants',
    sa.Column('grant_id', sa.String(), nullable=False),
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('client_id', sa.String(), nullable=False),
    sa.Column('scope', sa.String(), nullable=False),
    sa.Column('created_at', sa.Float(), nullable=False),
    sa.Column('revoked', sa.Integer(), server_default='0', nullable=False),
    sa.ForeignKeyConstraint(['client_id'], ['oauth_clients.client_id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('grant_id')
    )
    op.create_table('records',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('entity_type', sa.String(), nullable=False),
    sa.Column('entity_id', sa.String(), nullable=False),
    sa.Column('revision', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('deleted', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('payload', sa.Text(), nullable=False),
    sa.Column('updated_at', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'entity_type', 'entity_id')
    )
    op.create_table('reminder_copy_cache',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('key', sa.String(), nullable=False),
    sa.Column('result', sa.Text(), nullable=False),
    sa.Column('created_at', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'key')
    )
    op.create_table('retired_account_data',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('table_name', sa.String(), nullable=False),
    sa.Column('row_json', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], )
    )
    op.create_table('sessions',
    sa.Column('session_hash', sa.String(), nullable=False),
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('csrf', sa.String(), nullable=False),
    sa.Column('created_at', sa.Float(), nullable=False),
    sa.Column('expires_at', sa.Float(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('session_hash')
    )
    op.create_table('tracker_commands',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('command_id', sa.String(), nullable=False),
    sa.Column('digest', sa.String(), nullable=False),
    sa.Column('result', sa.Text(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'command_id')
    )
    op.create_table('tracker_workouts',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('source_key', sa.String(), nullable=False),
    sa.Column('revision', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('deleted', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('payload', sa.Text(), nullable=False),
    sa.Column('observed_at', sa.String(), nullable=False),
    sa.Column('received_at', sa.String(), nullable=False),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'source_key')
    )
    op.create_table('training_plan',
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('id', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('revision', sa.BigInteger().with_variant(sa.Integer(), 'sqlite'), nullable=False),
    sa.Column('payload', sa.Text(), nullable=False),
    sa.Column('updated_at', sa.String(), nullable=False),
    sa.CheckConstraint('id=1'),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('user_id', 'id')
    )
    op.create_table('oauth_codes',
    sa.Column('code_hash', sa.String(), nullable=False),
    sa.Column('client_id', sa.String(), nullable=False),
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('redirect_uri', sa.String(), nullable=False),
    sa.Column('challenge', sa.String(), nullable=False),
    sa.Column('scope', sa.String(), nullable=False),
    sa.Column('resource', sa.String(), nullable=True),
    sa.Column('grant_id', sa.String(), nullable=False),
    sa.Column('expires_at', sa.Float(), nullable=False),
    sa.Column('used', sa.Integer(), server_default='0', nullable=False),
    sa.ForeignKeyConstraint(['client_id'], ['oauth_clients.client_id'], ),
    sa.ForeignKeyConstraint(['grant_id'], ['oauth_grants.grant_id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('code_hash')
    )
    op.create_table('oauth_tokens',
    sa.Column('token_hash', sa.String(), nullable=False),
    sa.Column('kind', sa.String(), nullable=False),
    sa.Column('grant_id', sa.String(), nullable=False),
    sa.Column('user_id', sa.String(), nullable=False),
    sa.Column('client_id', sa.String(), nullable=False),
    sa.Column('scope', sa.String(), nullable=False),
    sa.Column('created_at', sa.Float(), nullable=False),
    sa.Column('expires_at', sa.Float(), nullable=False),
    sa.Column('revoked', sa.Integer(), server_default='0', nullable=False),
    sa.ForeignKeyConstraint(['client_id'], ['oauth_clients.client_id'], ),
    sa.ForeignKeyConstraint(['grant_id'], ['oauth_grants.grant_id'], ),
    sa.ForeignKeyConstraint(['user_id'], ['users.user_id'], ),
    sa.PrimaryKeyConstraint('token_hash')
    )
    op.create_index('oauth_tokens_grant', 'oauth_tokens', ['grant_id'], unique=False)


def downgrade():
    # Downgrading the baseline would drop every table and all accounts' data. Restore a backup
    # instead (POSTGRES.md, "Backup and recovery").
    raise RuntimeError('The baseline revision cannot be downgraded; restore a backup instead')
