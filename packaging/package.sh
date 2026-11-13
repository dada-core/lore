#!/bin/sh
# Builds the installation files of Lore from target/lore.jar (built with the interface, see
# CONTRIBUTING.md). Everything lands in build/dist.
#   sh packaging/package.sh archive          lore-<version>.tar.gz and .zip (needs tar and zip)
#   sh packaging/package.sh runtime          Java runtime for the packages, with jlink (needs a JDK 21)
#   sh packaging/package.sh packages <arch>  lore_<version>_<arch>.deb and lore-<version>-1.<arch>.rpm,
#                                            arch amd64 or arm64; needs the runtime built on that arch
#   sh packaging/package.sh all              the three, for the architecture of this machine
# The version comes from the jar, or from VERSION. nfpm runs from the PATH, else in Docker.
set -eu

cd "$(dirname "$0")/.."
JAR=target/lore.jar
OUT=build/dist
NFPM_IMAGE=goreleaser/nfpm:v2.49.0

fail() {
  echo "package.sh: $*" >&2
  exit 1
}

[ -f "$JAR" ] || fail "$JAR is missing: build the interface, then run ./mvnw -DskipTests package."
if [ -z "${VERSION:-}" ]; then
  VERSION=$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Implementation-Version: //p')
fi
[ -n "$VERSION" ] || fail "no version found in $JAR: set VERSION."
export VERSION
mkdir -p "$OUT"

archive() {
  name=lore-$VERSION
  stage=build/archive/$name
  rm -rf build/archive
  mkdir -p "$stage/bin" "$stage/lib" "$stage/conf"
  cp "$JAR" "$stage/lib/lore.jar"
  cp packaging/lore "$stage/bin/lore"
  cp packaging/lore.cmd "$stage/bin/lore.cmd"
  cp packaging/lore.env "$stage/conf/lore.env.example"
  cp LICENSE "$stage/LICENSE"
  chmod 0755 "$stage/bin/lore"
  chmod 0644 "$stage/bin/lore.cmd" "$stage/lib/lore.jar" "$stage/conf/lore.env.example" "$stage/LICENSE"
  rm -f "$OUT/$name.tar.gz" "$OUT/$name.zip"
  (cd build/archive && tar --owner=0 --group=0 --numeric-owner -czf "../../$OUT/$name.tar.gz" "$name")
  (cd build/archive && zip -qrX "../../$OUT/$name.zip" "$name")
  echo "$OUT/$name.tar.gz"
  echo "$OUT/$name.zip"
}

runtime() {
  [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jlink" ] || fail "JAVA_HOME must point to a JDK 21."
  rm -rf build/runtime build/jar
  mkdir -p build/jar
  (cd build/jar && unzip -q "../../$JAR")
  modules=$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 21 \
    --recursive --class-path 'build/jar/BOOT-INF/lib/*' build/jar/BOOT-INF/classes)
  # Modules only reached by reflection or service lookup: TLS with elliptic curves, the date and
  # number formats of the interface languages, all character sets, DNS lookups, JVM metrics.
  modules=$modules,jdk.crypto.ec,jdk.localedata,jdk.charsets,jdk.naming.dns,jdk.management,jdk.zipfs
  echo "Modules: $modules"
  "$JAVA_HOME/bin/jlink" --add-modules "$modules" --include-locales en,fr \
    --strip-debug --no-header-files --no-man-pages --compress zip-6 --output build/runtime
  rm -rf build/jar
  build/runtime/bin/java -version
}

packages() {
  ARCH=${1:-}
  case $ARCH in
    amd64) rpm_arch=x86_64 ;;
    arm64) rpm_arch=aarch64 ;;
    *) fail "packages needs the architecture: amd64 or arm64." ;;
  esac
  export ARCH
  [ -x build/runtime/bin/java ] || fail "build/runtime is missing: run 'package.sh runtime' first, on $ARCH."
  deb=$OUT/lore_${VERSION}_$ARCH.deb
  rpm=$OUT/lore-$VERSION-1.$rpm_arch.rpm
  run_nfpm package --config packaging/nfpm.yaml --packager deb --target "$deb"
  run_nfpm package --config packaging/nfpm.yaml --packager rpm --target "$rpm"
}

run_nfpm() {
  if command -v nfpm >/dev/null 2>&1; then
    nfpm "$@"
  else
    docker run --rm -e VERSION -e ARCH -v "$PWD:/src" -w /src "$NFPM_IMAGE" "$@"
  fi
}

case ${1:-} in
  archive) archive ;;
  runtime) runtime ;;
  packages) packages "${2:-}" ;;
  all)
    archive
    runtime
    packages "$(dpkg --print-architecture 2>/dev/null || uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/')"
    ;;
  *) fail "usage: package.sh archive | runtime | packages <amd64|arm64> | all" ;;
esac
