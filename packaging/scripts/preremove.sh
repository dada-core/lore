#!/bin/sh
# Before removal: stop and disable the service. Not on upgrade: rpm passes 0 on removal only,
# dpkg passes "remove" (or "upgrade" on upgrade).
set -e

case "${1:-}" in
  0 | remove)
    if [ -d /run/systemd/system ]; then
      systemctl disable --now lore.service >/dev/null 2>&1 || true
    fi
    ;;
  *) ;;
esac
