"""Retain an anonymous identity for messages after account deletion."""
from alembic import op
import sqlalchemy as sa

revision = "c36f281a9012"
down_revision = "b8210c9a9e30"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("users", sa.Column("deleted_at", sa.BigInteger(), nullable=True))


def downgrade():
    op.drop_column("users", "deleted_at")
