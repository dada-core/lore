#!/bin/sh
# Before the files are unpacked: the lore system user and group, which own the settings and the data.
set -e

if ! getent group lore >/dev/null; then
  groupadd --system lore
fi
if ! getent passwd lore >/dev/null; then
  useradd --system --gid lore --home-dir /var/lib/lore --no-create-home \
    --shell /usr/sbin/nologin --comment "Lore" lore
fi
