"""Per-user conversation deletion."""
from alembic import op
import sqlalchemy as sa

revision = "e59a7c1832d0"
down_revision = "d481fa209b73"
branch_labels = None
depends_on = None


def upgrade():
    for side in ("a", "b"):
        op.add_column("conversations", sa.Column(side + "_hidden", sa.Boolean(), nullable=False,
                                               server_default=sa.false()))


def downgrade():
    for side in ("a", "b"):
        op.drop_column("conversations", side + "_hidden")
