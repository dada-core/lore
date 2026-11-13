#!/bin/sh
# Installs a Lore package on this machine (a test container, as root), starts Lore the way the
# systemd service does, checks it, then removes the package. Used by the CI on every supported
# release.
#   sh packaging/test.sh <path to the .deb or .rpm> <database host>
# The database runs elsewhere: PostgreSQL 18, role and database lore, password lore-test.
# TIMEOUT: seconds to wait for Lore (default 180).
set -eu

package=$1
db_host=$2
timeout=${TIMEOUT:-180}
# shellcheck disable=SC1091 # the file of the release under test
. /etc/os-release
echo "== $PRETTY_NAME, $(uname -m)"

fail() {
  echo "FAILED on $PRETTY_NAME: $*" >&2
  [ -f /tmp/lore.log ] && tail -n 60 /tmp/lore.log >&2
  exit 1
}

if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq "$package" curl >/dev/null || fail "installation of $package"
else
  dnf install -y -q "$package" >/dev/null || fail "installation of $package"
  command -v curl >/dev/null 2>&1 || dnf install -y -q curl >/dev/null
fi

for path in /opt/lore/runtime/bin/java /opt/lore/lib/lore.jar /usr/bin/lore /etc/lore/lore.env /usr/lib/systemd/system/lore.service; do
  [ -e "$path" ] || fail "$path is missing"
done
[ "$(stat -c '%U:%G %a' /etc/lore/lore.env)" = "root:lore 640" ] || fail "rights of /etc/lore/lore.env"
[ "$(stat -c '%U:%G' /var/lib/lore/files)" = "lore:lore" ] || fail "owner of /var/lib/lore/files"
/opt/lore/runtime/bin/java -version 2>&1 | head -1

sed -i "s|^LORE_DB_HOST=.*|LORE_DB_HOST=$db_host|; s|^LORE_DB_PASSWORD=.*|LORE_DB_PASSWORD=lore-test|" /etc/lore/lore.env

# The ExecStart line of the unit, with its Environment and EnvironmentFile, as the lore user.
exec_start=$(sed -n 's/^ExecStart=//p' /usr/lib/systemd/system/lore.service)
java_opts=$(sed -n 's/^Environment=LORE_JAVA_OPTS=//p' /usr/lib/systemd/system/lore.service)
runuser -u lore -- sh -c "LORE_JAVA_OPTS='$java_opts'; set -a; . /etc/lore/lore.env; set +a; cd /var/lib/lore; exec $exec_start" >/tmp/lore.log 2>&1 &
pid=$!

elapsed=0
until curl -fsS http://localhost:8080/actuator/health/readiness 2>/dev/null | grep -q '"UP"'; do
  kill -0 "$pid" 2>/dev/null || fail "Lore stopped"
  [ "$elapsed" -lt "$timeout" ] || fail "not ready after $timeout s"
  sleep 3
  elapsed=$((elapsed + 3))
done
echo "ready after about $elapsed s"

curl -fsS http://localhost:8080/api/v1/session | grep -q '"setupRequired":true' || fail "a fresh installation does not ask for the setup"
curl -fsS http://localhost:8080/setup | grep -q '<div id="root">' || fail "the setup page does not answer"
grep -q "setup code" /tmp/lore.log || fail "no setup code in the logs"
lore help | grep -q reset-password || fail "lore help"
if command -v systemd-analyze >/dev/null 2>&1; then
  # Warnings and errors only: systemd 239 (RHEL 8) also prints its debug messages otherwise.
  SYSTEMD_LOG_LEVEL=notice systemd-analyze verify /usr/lib/systemd/system/lore.service || fail "systemd-analyze verify"
fi

kill "$pid"
wait "$pid" 2>/dev/null || true
if command -v apt-get >/dev/null 2>&1; then
  apt-get remove -y -qq lore >/dev/null || fail "removal"
else
  dnf remove -y -q lore >/dev/null || fail "removal"
fi
[ ! -e /opt/lore ] || fail "/opt/lore is left after removal"
[ -d /var/lib/lore/files ] || fail "the data in /var/lib/lore was deleted"
echo "OK $PRETTY_NAME, $(uname -m)"
