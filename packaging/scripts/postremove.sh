#!/bin/sh
# After removal. The data in /var/lib/lore, the database and the lore user are kept:
# delete them by hand once they are backed up or no longer needed.
set -e

if [ -d /run/systemd/system ]; then
  systemctl daemon-reload >/dev/null 2>&1 || true
fi
