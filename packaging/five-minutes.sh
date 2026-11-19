#!/bin/sh
# The five minutes, timed, as a person would do them on a machine with Docker: download
# docker-compose.yml, write .env, start Lore, read the setup code in its logs; then, in a browser
# (journey 1, web/e2e/01-five-minutes.spec.ts), the initial setup, the Infrastructure space, a
# runbook and a PDF imported, a pasted error searched and opened at its passage. Only the time of
# the machine counts: the person reads and types in the two minutes left. Fails above the limit.
#   sh packaging/five-minutes.sh <docker-compose.yml: an address or a file> [limit in seconds, 180]
# From the repository, with the interface's dependencies installed (npm ci in web/) and Chromium
# for Playwright (npx playwright install chromium). The images are downloaded unless present.
# FIVE_MINUTES_PORT: the port docker-compose.yml publishes, 8080 by default.
set -eu

source=$1
limit=${2:-180}
port=${FIVE_MINUTES_PORT:-8080}
repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
compose="docker compose --project-name lore-five-minutes --project-directory $work"
steps=$work/steps.tsv
: > "$steps"

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then $compose logs --no-color 2>/dev/null | tail -n 200 || true; fi
  [ -n "${FIVE_MINUTES_KEEP:-}" ] || $compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

# In milliseconds, wherever the script runs: GNU date alone knows %N.
millis() {
  node -e 'console.log(Date.now())'
}

started=0
begin() {
  started=$(millis)
}
end() {
  echo "$1	$(( $(millis) - started ))" >> "$steps"
}

begin
case $source in
  http://* | https://*) curl -fsSL -o "$work/docker-compose.yml" "$source" ;;
  *) cp "$source" "$work/docker-compose.yml" ;;
esac
echo "DB_PASSWORD=$(openssl rand -base64 24)" > "$work/.env"
[ "$port" = 8080 ] || echo "LORE_PUBLIC_URL=http://localhost:$port" >> "$work/.env"
end "docker-compose.yml and .env"

begin
$compose up -d --quiet-pull
end "docker compose up -d, images included"

begin
code=
for _ in $(seq 170); do
  code=$($compose logs --no-log-prefix lore 2>/dev/null | grep -A2 "enter this setup code" | tail -1 | tr -d ' \r')
  [ -n "$code" ] && break
  sleep 1
done
[ -n "$code" ] || { echo "five minutes: no setup code in the logs" >&2; exit 1; }
end "Start, the setup code in the logs"

report=$work/browser.json
(
  cd "$repo/web"
  LORE_E2E_URL="http://localhost:$port" LORE_SETUP_CODE="$code" LORE_E2E_AXE=0 LORE_FIVE_MINUTES_REPORT="$report" \
    npx playwright test --project=five-minutes --reporter=list
)
node -e '
  const { durations } = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
  for (const { step, seconds } of durations) console.log(`${step}\t${Math.round(seconds * 1000)}`);
' "$report" >> "$steps"

total=$(awk -F '\t' '{ sum += $2 } END { print sum }' "$steps")
summary=$(awk -F '\t' -v total="$total" -v limit="$limit" '
  BEGIN { print "| Step | Time |"; print "|---|---:|" }
  { printf "| %s | %.1f s |\n", $1, $2 / 1000 }
  END { printf "| **Machine time** | **%.1f s** (limit: %d s) |\n", total / 1000, limit }
' "$steps")
echo "$summary"
[ -z "${GITHUB_STEP_SUMMARY:-}" ] || { echo "### Five minutes"; echo; echo "$summary"; } >> "$GITHUB_STEP_SUMMARY"
if [ "$total" -gt $((limit * 1000)) ]; then
  echo "five minutes: $((total / 1000)) s of machine time, above $limit s" >&2
  exit 1
fi
