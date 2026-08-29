#!/usr/bin/env bash
# Build rootfs.tar.gz (Termux-based) for the DSH Android app.
#
# Runs on CI before gradle: downloads Termux aarch64 packages, lays out a
# minimal rootfs, copies the dsh workspace (post `pnpm install` + `npm run
# build`) into /opt/dsh-core, and packs app/src/main/assets/rootfs.tar.gz.
#
# Layout:
#   /bin/busybox, /usr/bin/node, /usr/lib/*.so   — Termux binaries & libs
#   /usr/lib/dsh -> /opt/dsh-core                — dsh entry (apps/cli/lib/bin.js)
#   /opt/dsh-core/{apps,packages,node_modules}   — full workspace, real files
#   /home/.dsh, /workspace, /tmp                 — runtime dirs
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
ASSETS="$ROOT/apps/mobile/android/app/src/main/assets"
WORK="$(mktemp -d)"
RFS="$WORK/rootfs"
trap 'rm -rf "$WORK"' EXIT

echo "== [1/5] Fetch Termux aarch64 packages =="
BASE=https://packages.termux.dev/apt/termux-main
IDX="$WORK/Packages"
curl -fsSL "$BASE/dists/stable/main/binary-aarch64/Packages" -o "$IDX"
PKGS="proot libtalloc libandroid-shmem nodejs busybox openssl libicu libc++ libsqlite zlib libffi c-ares"
for p in $PKGS; do
  FN="$(awk -v pat="^Package: ${p}\$" 'BEGIN{RS=""} $0 ~ pat { if (match($0, /Filename: [^\n]+/)) print substr($0, RSTART+10, RLENGTH-10); exit }' "$IDX")"
  echo "  $p -> $FN"
  curl -fsSL "$BASE/$FN" -o "$WORK/$(basename "$FN" | tr ':' '_')"
done

echo "== [2/5] Extract packages =="
mkdir -p "$WORK/x"
for d in "$WORK"/*.deb; do dpkg-deb -x "$d" "$WORK/x"; done
X="$WORK/x/data/data/com.termux/files/usr"

echo "== [3/5] Layout rootfs =="
mkdir -p "$RFS/bin" "$RFS/usr/bin" "$RFS/usr/lib" "$RFS/tmp" "$RFS/var/tmp" \
         "$RFS/home/.dsh" "$RFS/workspace" "$RFS/etc"
cp -a "$X/lib/." "$RFS/usr/lib/"
cp -a "$X/bin/busybox" "$RFS/bin/busybox"
cp -a "$X/bin/node" "$RFS/usr/bin/node"
chmod 755 "$RFS/bin/busybox" "$RFS/usr/bin/node"
ln -s /opt/dsh-core "$RFS/usr/lib/dsh"

echo "== [4/5] Copy dsh workspace =="
CORE="$RFS/opt/dsh-core"
mkdir -p "$CORE"
cp -a "$ROOT/apps" "$CORE/apps"
cp -a "$ROOT/packages" "$CORE/packages"
cp -a "$ROOT/node_modules" "$CORE/node_modules"
for f in package.json pnpm-workspace.yaml pnpm-lock.yaml tsconfig.json LICENSE; do
  [ -f "$ROOT/$f" ] && cp -a "$ROOT/$f" "$CORE/"
done

echo "== [5/5] Pack rootfs.tar.gz =="
mkdir -p "$ASSETS"
tar czf "$ASSETS/rootfs.tar.gz" -C "$RFS" \
  --exclude='*.md' --exclude='*.map' --exclude='*.tsbuildinfo' \
  --exclude='__tests__' --exclude='*/test' --exclude='*/tests' \
  --exclude='*/docs' --exclude='*/examples' --exclude='*/.github' \
  .
ls -lh "$ASSETS/rootfs.tar.gz"
echo "rootfs built OK"
