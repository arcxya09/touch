#!/bin/sh
set -eu
umask 077
if [ "$#" -ne 2 ] || [ "$2" != "--replace-touch-data" ]; then
    echo "Usage: $0 /absolute/backup/directory --replace-touch-data" >&2
    exit 2
fi
source_dir=$(realpath "$1")
test -f "$source_dir/database.dump"
test -d "$source_dir/files"
if [ -f "$source_dir/files.sha256" ]; then
    (cd "$source_dir" && sha256sum -c files.sha256)
fi
cd "$(dirname "$0")"
docker compose exec -T db pg_restore --list < "$source_dir/database.dump" >/dev/null
docker compose stop app
trap 'echo "Restore stopped before verification. Touch remains stopped; inspect the saved pre-restore snapshot before restarting." >&2' EXIT HUP INT TERM
container=$(docker compose ps -aq app)
# Archive current data separately before deliberately restoring an older snapshot.
archive="${TOUCH_BACKUP_DIR:-/var/backups/touch}/before-restore-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$archive"
docker compose exec -T db pg_dump -U touch -d touch -Fc > "$archive/database.dump"
docker cp "$container:/data/files" "$archive/files"
docker compose exec -T db pg_restore -U touch -d touch --clean --if-exists --single-transaction < "$source_dir/database.dump"
docker cp "$source_dir/files/." "$container:/data/files"
# Revoke all restored sessions. Blob garbage collection removes old unreferenced files later.
docker compose exec -T db psql -U touch -d touch -c 'DELETE FROM mobile_sessions; DELETE FROM admin_sessions;'
docker compose start app
trap - EXIT HUP INT TERM
echo "Restored. Verify /health and account/history access; all clients must log in again."
