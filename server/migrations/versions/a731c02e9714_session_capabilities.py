"""Negotiate destructive message actions with both active clients."""
from alembic import op
import sqlalchemy as sa

revision = "a731c02e9714"
down_revision = "f620ba741901"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("mobile_sessions", sa.Column("supports_recall", sa.Boolean(), nullable=False,
                                               server_default=sa.false()))


def downgrade():
    op.drop_column("mobile_sessions", "supports_recall")
