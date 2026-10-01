#!/bin/sh
set -eu
umask 077
cd "$(dirname "$0")"
destination="${TOUCH_BACKUP_DIR:-/var/backups/touch}"
mkdir -p "$destination"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target="$destination/.$stamp.partial"
mkdir "$target"
# Briefly stop only Touch's application so database and blob snapshots agree.
# The database and other services stay up throughout this operation.
docker compose stop app
trap 'docker compose start app >/dev/null' EXIT HUP INT TERM
docker compose exec -T db pg_dump -U touch -d touch -Fc > "$target/database.dump"
container=$(docker compose ps -aq app)
docker cp "$container:/data/files" "$target/files"
(cd "$target" && find files -type f -exec sha256sum '{}' \;) > "$target/files.sha256"
printf '%s\n' "complete" > "$target/COMPLETE"
mv "$target" "$destination/$stamp"
target="$destination/$stamp"
docker compose start app
trap - EXIT HUP INT TERM
# Publish a marker only after both snapshot operations and restart succeeded.
docker compose exec -T app python -c 'from pathlib import Path; import time; p=Path("/data/backup-success.txt"); t=p.with_suffix(".tmp"); t.write_text(str(int(time.time())), encoding="utf-8"); t.replace(p)'
find "$destination" -mindepth 1 -maxdepth 1 -type d -name '20*T*Z' -mtime +6 -exec rm -rf -- {} +
echo "Backup completed: $target"
