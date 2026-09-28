"""Personal profiles, private avatars and administrator receipt preference."""
from alembic import op
import sqlalchemy as sa

revision = "d481fa209b73"
down_revision = "c36f281a9012"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("users", sa.Column("bio", sa.String(160), nullable=False, server_default=""))
    op.add_column("users", sa.Column("avatar", sa.LargeBinary(), nullable=True))
    op.add_column("users", sa.Column("avatar_version", sa.String(36), nullable=True))
    op.add_column("users", sa.Column("read_receipts_enabled", sa.Boolean(), nullable=False,
                                     server_default=sa.false()))


def downgrade():
    for field in ("read_receipts_enabled", "avatar_version", "avatar", "bio"):
        op.drop_column("users", field)
