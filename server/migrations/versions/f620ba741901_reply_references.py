"""Content-free reply references and conversation time index."""
from alembic import op
import sqlalchemy as sa

revision = "f620ba741901"
down_revision = "e59a7c1832d0"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("messages", sa.Column("reply_to_id", sa.String(36), nullable=True))
    op.add_column("messages", sa.Column("reply_to_seq", sa.BigInteger(), nullable=True))
    op.add_column("messages", sa.Column("reply_to_created_at", sa.BigInteger(), nullable=True))
    op.create_index("ix_message_time", "messages", ["conversation_id", "created_at"])


def downgrade():
    op.drop_index("ix_message_time", table_name="messages")
    for column in ("reply_to_created_at", "reply_to_seq", "reply_to_id"):
        op.drop_column("messages", column)
