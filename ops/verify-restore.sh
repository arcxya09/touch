#!/bin/sh
# Restore a backup into a disposable, network-isolated database only.
set -eu
if [ "$#" -ne 2 ] || [ "$2" != "--isolated" ]; then
    echo "Usage: $0 /absolute/backup/directory --isolated" >&2
    exit 2
fi
source_dir=$(realpath "$1")
test -f "$source_dir/database.dump"
test -d "$source_dir/files"
if [ -f "$source_dir/files.sha256" ]; then
    (cd "$source_dir" && sha256sum -c files.sha256)
fi
container="touch-restore-check-$(date -u +%Y%m%dT%H%M%SZ)-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT HUP INT TERM
docker run --name "$container" --network none -d -e POSTGRES_PASSWORD=isolated_test_only -e POSTGRES_DB=touch postgres:17-alpine >/dev/null
attempt=0
until docker exec "$container" pg_isready -U postgres -d touch >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then exit 1; fi
    sleep 1
done
docker exec -i "$container" pg_restore -U postgres -d touch --no-owner --single-transaction < "$source_dir/database.dump"
docker exec "$container" psql -U postgres -d touch -v ON_ERROR_STOP=1 -c 'DELETE FROM mobile_sessions; DELETE FROM admin_sessions;'
attachments=$(docker exec "$container" psql -U postgres -d touch -At -F ' ' -v ON_ERROR_STOP=1 -c 'SELECT id,sha256,size FROM attachments')
if [ -n "$attachments" ]; then
    printf '%s\n' "$attachments" | while read -r attachment checksum size; do
        case "$attachment" in *[!a-f0-9-]*|'') echo "Invalid attachment identity" >&2; exit 1;; esac
        test -f "$source_dir/files/$attachment" || { echo "Missing attachment blob" >&2; exit 1; }
        actual=$(sha256sum "$source_dir/files/$attachment")
        test "${actual%% *}" = "$checksum" || { echo "Attachment checksum mismatch" >&2; exit 1; }
        test "$(wc -c < "$source_dir/files/$attachment" | tr -d ' ')" = "$size"
    done
fi
docker exec "$container" psql -U postgres -d touch -At -v ON_ERROR_STOP=1 -c 'SELECT count(*) FROM users; SELECT count(*) FROM messages; SELECT count(*) FROM attachments;'
echo "Isolated restore succeeded: database constraints, attachment presence and snapshot checksums verified."
