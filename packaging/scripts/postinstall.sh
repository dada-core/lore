#!/bin/sh
# After installation or upgrade. rpm passes 1 (installation) or 2 and more (upgrade); dpkg passes
# "configure" followed by the previous version, empty on a first installation.
set -e

first=false
case "${1:-}" in
  1) first=true ;;
  configure) [ -z "${2:-}" ] && first=true ;;
  *) ;;
esac

install -d -o lore -g lore -m 0750 /var/lib/lore /var/lib/lore/files
chown root:lore /etc/lore/lore.env
chmod 0640 /etc/lore/lore.env

systemd=false
[ -d /run/systemd/system ] && systemd=true
if $systemd; then
  systemctl daemon-reload >/dev/null 2>&1 || true
fi

if $first; then
  cat <<'EOF'

Lore is installed. Next steps:
  1. Create the PostgreSQL 18 role and database, for example:
       sudo -u postgres createuser --pwprompt lore
       sudo -u postgres createdb --owner lore lore
  2. Set LORE_PUBLIC_URL and LORE_DB_PASSWORD in /etc/lore/lore.env
  3. Start Lore:
       sudo systemctl enable --now lore
  4. Read the initial setup code:
       sudo journalctl -u lore | grep -A 3 "setup code"
Documentation: https://lore.dada-core.com/docs/installation/

EOF
elif $systemd; then
  # Upgrade: the new version starts at once if Lore was running.
  systemctl try-restart lore.service >/dev/null 2>&1 || true
fi
