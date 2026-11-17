#!/bin/sh
# Imports a small PDF and a Markdown file into a fresh installation of Lore, waits until their
# text is extracted, then downloads the PDF and reads the rendering of the Markdown. It proves that
# the extraction process starts with the Java of the installation (the packages bundle a reduced
# Java runtime, which must hold what Apache Tika needs), and that the reader serves both.
#   sh packaging/import-check.sh <log of Lore, which holds the setup code> [address of Lore]
set -eu

log=$1
base=${2:-http://localhost:8080}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
jar=$work/cookies

fail() {
  echo "import check: $*" >&2
  exit 1
}

# The code a fresh installation prints: the line two lines after "enter this setup code".
code=$(grep -A2 "enter this setup code" "$log" | tail -1 | tr -d ' \r')
[ -n "$code" ] || fail "no setup code in $log"

# The CSRF token, from the cookie file (its seventh column).
token() {
  grep 'XSRF-TOKEN' "$jar" | tail -1 | cut -f7
}

json() {
  curl -fsS -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(token)" -H 'Content-Type: application/json' "$@"
}

first_id() {
  grep -o '"id":"[0-9a-f-]*"' | head -1 | cut -d'"' -f4
}

curl -fsS -c "$jar" "$base/api/v1/session" >/dev/null
json -X POST "$base/api/v1/setup" -d "{\"setupCode\":\"$code\",\"organizationName\":\"DadaGroup\",\"locale\":\"en\",\"timeZone\":\"UTC\",\"displayName\":\"Wilfried Kouakou\",\"email\":\"wilfried.kouakou@dada.group\",\"password\":\"correct-horse-battery\"}" >/dev/null \
  || fail "initial setup"
space=$(json -X POST "$base/api/v1/spaces" -d '{"name":"Infrastructure"}' | first_id)
[ -n "$space" ] || fail "no space"

# A PDF of one page, made by the tests of Lore (SampleFiles.pdf).
base64 -d > "$work/restore.pdf" <<'PDF'
JVBERi0xLjYKJfbk/N8KMSAwIG9iago8PAovVHlwZSAvQ2F0YWxvZwovVmVyc2lvbiAvMS42Ci9Q
YWdlcyAyIDAgUgo+PgplbmRvYmoKNSAwIG9iago8PAovTGVuZ3RoIDEwNAovRmlsdGVyIC9GbGF0
ZURlY29kZQo+PgpzdHJlYW0NCnicHck7DoMwEAXAfk/xShIhwBEoPRJUqaK9wGIWofAxss39A25n
WqayNzAGPJGpwR9qKrxfFXik7KshOq+Is2KUKIMExeTdlmSVeD0Gsct55DftsLPaJa13q4biAf4R
P6lj+gNx7CA/DQplbmRzdHJlYW0KZW5kb2JqCjggMCBvYmoKPDwKL0xlbmd0aCAxODcKL1R5cGUg
L09ialN0bQovTiA1Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCi9GaXJzdCAyNwo+PgpzdHJlYW0NCnic
VY7dCoJAEIVf5TxB4/qLIEJKEUQQFnQhXpgushC74WrU2zcq9HMxA+ebMzPHhQMPvgsfwosRQgQh
IohIIEno/LpL0LHupAXtVWtRerxQoALlZtQDBNL03wk6yFbVmXmidFYOpgqFyz2Kp15Ntl7yrjuf
okJaM/YNv/AXkBs98NwimPXyYMuQ4/0AwTm/cgkwu+g0XodZTlCAstrKZbKTt4ccVFODNroxrdId
6KL0Wlv1AXzwDSTmTM8NCmVuZHN0cmVhbQplbmRvYmoKOSAwIG9iago8PAovTGVuZ3RoIDQwCi9S
b290IDEgMCBSCi9JRCBbPEYxMUY2ODU2ODQ5N0I2QURFMzc3NDJBMjIzMUMzRjIxNDQ1RTNFOUQ3
OUNGQTZBRjUyOTYyNTcxMzEwMUU3NzE+IDxGMTFGNjg1Njg0OTdCNkFERTM3NzQyQTIyMzFDM0Yy
MTQ0NUUzRTlENzlDRkE2QUY1Mjk2MjU3MTMxMDFFNzcxPl0KL1R5cGUgL1hSZWYKL1NpemUgMTAK
L0luZGV4IFswIDEwXQovVyBbMSAyIDFdCi9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0K
eJxjYGD4z8jAz8DEwAHCjEDMxMjgB2IzAzELIyMDAyOTEgMAMbABwg0KZW5kc3RyZWFtCmVuZG9i
agpzdGFydHhyZWYKNTQ2CiUlRU9GCg==
PDF
document=$(curl -fsS -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(token)" -F "file=@$work/restore.pdf;type=application/pdf" \
  "$base/api/v1/spaces/$space/documents" | first_id) || fail "import"
[ -n "$document" ] || fail "no document"

# Waits until the text of a document is extracted.
indexed() {
  elapsed=0
  while :; do
    status=$(curl -fsS -b "$jar" "$base/api/v1/documents/$1" | grep -o '"extractionStatus":"[A-Z]*"' | cut -d'"' -f4)
    case $status in
      INDEXED) break ;;
      FAILED) fail "the extraction failed: $(curl -fsS -b "$jar" "$base/api/v1/documents/$1")" ;;
    esac
    [ "$elapsed" -lt 120 ] || fail "still $status after 120 s"
    sleep 2
    elapsed=$((elapsed + 2))
  done
}

indexed "$document"
echo "import check: a PDF imported and indexed in about $elapsed s"

# The original comes back as it was imported, never compressed, never shown as a page.
curl -fsS -b "$jar" -H 'Accept-Encoding: gzip' -D "$work/headers" -o "$work/downloaded.pdf" \
  "$base/api/v1/documents/$document/versions/1/file" || fail "download"
# sha256sum (coreutils) rather than cmp (diffutils), absent from minimal RHEL images.
[ "$(sha256sum < "$work/restore.pdf")" = "$(sha256sum < "$work/downloaded.pdf")" ] || fail "the file downloaded differs from the file imported"
grep -qi '^x-content-type-options: nosniff' "$work/headers" || fail "no nosniff on the original"
! grep -qi '^content-encoding:' "$work/headers" || fail "the original was compressed"
echo "import check: the PDF downloaded is the file imported"

# A Markdown file: its rendering, cleaned, with the anchors of its titles.
printf '# Restore a database\n\nRun `pg_restore` from the last backup.\n\n## Frequent errors\n\n<script>alert(1)</script>\n' \
  > "$work/restore.md"
markdown=$(curl -fsS -b "$jar" -c "$jar" -H "X-XSRF-TOKEN: $(token)" -F "file=@$work/restore.md;type=text/markdown" \
  "$base/api/v1/spaces/$space/documents" | first_id) || fail "import of the Markdown file"
[ -n "$markdown" ] || fail "no Markdown document"
indexed "$markdown"
curl -fsS -b "$jar" -o "$work/rendering.json" "$base/api/v1/documents/$markdown/versions/1/rendering" || fail "rendering"
grep -qF 'id=\"h-2\"' "$work/rendering.json" || fail "no anchor in the rendering: $(cat "$work/rendering.json")"
grep -qF '<code>pg_restore</code>' "$work/rendering.json" || fail "no code in the rendering"
! grep -qF '<script' "$work/rendering.json" || fail "a script went through the rendering"
echo "import check: a Markdown file rendered for reading"
