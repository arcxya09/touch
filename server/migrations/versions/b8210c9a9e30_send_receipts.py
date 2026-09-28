"""Preserve idempotency after message content is removed."""
from alembic import op
import sqlalchemy as sa

revision = "b8210c9a9e30"
down_revision = "ae54dbc89ba9"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "send_receipts",
        sa.Column("sender_id", sa.String(36), sa.ForeignKey("users.id"), primary_key=True),
        sa.Column("client_id", sa.String(36), primary_key=True),
    )
    op.execute("INSERT INTO send_receipts (sender_id, client_id) SELECT sender_id, client_id FROM messages")


def downgrade():
    op.drop_table("send_receipts")
