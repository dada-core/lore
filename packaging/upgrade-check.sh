#!/bin/sh
# After an upgrade, or after a backup restored on an empty installation: what import-check.sh put
# in Lore is still there, whole. The same account signs in, no setup asked; the space and its two
# documents; version 2 of the Markdown file current and version 1 in its history; each original
# downloaded as it was imported (its SHA-256, recorded at the import); the search; the events of
# the audit log.
#   sh packaging/upgrade-check.sh [address of Lore]
set -eu

base=${1:-http://localhost:8080}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jar=$work/cookies

fail() {
  echo "upgrade check: $*" >&2
  exit 1
}

token() {
  grep 'XSRF-TOKEN' "$jar" | tail -1 | cut -f7
}

get() {
  curl -fsS -b "$jar" -c "$jar" "$base$1"
}

# The value of a field of a JSON text, the first time it appears: enough for these answers.
field() {
  grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

get /api/v1/session > "$work/session.json" || fail "no answer from $base"
grep -q '"setupRequired":false' "$work/session.json" || fail "the installation asks for a new setup: $(cat "$work/session.json")"
curl -fsS -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' -X POST "$base/api/v1/auth/login" \
  -d '{"email":"wilfried.kouakou@dada.group","password":"correct-horse-battery"}' >/dev/null || fail "the account no longer signs in"
get /api/v1/session >/dev/null
echo "upgrade check: the same account signs in, no setup asked"

space=$(get /api/v1/spaces | grep -o '"id":"[0-9a-f-]*","name":"Infrastructure"' | cut -d'"' -f4)
[ -n "$space" ] || fail "no space Infrastructure: $(get /api/v1/spaces)"
get "/api/v1/spaces/$space/documents?size=50" > "$work/documents.json"
grep -q '"total":2' "$work/documents.json" || fail "the space does not hold its two documents: $(cat "$work/documents.json")"
pdf=$(grep -o '"id":"[0-9a-f-]*","title":"Restore"' "$work/documents.json" | cut -d'"' -f4)
markdown=$(grep -o '"id":"[0-9a-f-]*","title":"Restore a database"' "$work/documents.json" | cut -d'"' -f4)
[ -n "$pdf" ] && [ -n "$markdown" ] || fail "the documents changed: $(cat "$work/documents.json")"

# Each original as it was imported: the SHA-256 recorded at the import, the file downloaded now.
original() {
  get "/api/v1/documents/$1/versions/$2" > "$work/version.json" || fail "no version $2 of $1"
  recorded=$(field sha256 < "$work/version.json")
  get "/api/v1/documents/$1/versions/$2/file" > "$work/file" || fail "the file of version $2 of $1 does not download"
  [ "$recorded" = "$(sha256sum < "$work/file" | cut -d' ' -f1)" ] || fail "version $2 of $1 differs from the file imported"
}
get "/api/v1/documents/$markdown" | grep -q '"currentVersion":{"id":"[0-9a-f-]*","number":2' \
  || fail "version 2 of the Markdown file is no longer current: $(get "/api/v1/documents/$markdown")"
original "$markdown" 1
original "$markdown" 2
original "$pdf" 1
echo "upgrade check: the documents, their versions and their files are whole"

get '/api/v1/search?q=rows' | grep -qF "\"id\":\"$markdown\"" || fail "the search no longer finds version 2"
get '/api/v1/search?q=pg_restore' | grep -qF '"anchor":"h-' || fail "the search no longer finds the passage"
echo "upgrade check: the search finds the documents"

get '/api/v1/admin/audit?size=100' > "$work/audit.json" || fail "no audit log"
for action in document.imported document.version_imported space.created; do
  grep -qF "\"action\":\"$action\"" "$work/audit.json" || fail "no $action event in the audit log: $(cat "$work/audit.json")"
done
echo "upgrade check: the audit log kept its events"
