#!/usr/bin/env bash
# Reuse the two helper classes and ByeDPI patches missing from the published
# 1.0.5 source tree. All generated output stays under app/build/.
set -euo pipefail

if [[ $# != 1 ]]; then
    echo "Usage: $0 OUTPUT_DIRECTORY" >&2
    exit 2
fi
out=$1
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

apk_url='https://github.com/rupleide/NetFixMobile/releases/download/v1.0.5/NetFix-Mobile-v1.0.5-release.apk'
dex_url='https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip'
apk_sha='181836904b55cd27184cf279ef19b2e36523df87081888dc76e4425b5a54659f'
dex_sha='ee7c45eb3c1d2474a6145d8d447e651a736a22d9664b6d3d3be5a5a817dda23a'

curl --fail --location --silent --show-error "$apk_url" -o "$tmp/upstream.apk"
echo "$apk_sha  $tmp/upstream.apk" | sha256sum --check --status || {
    echo 'Upstream APK checksum mismatch' >&2; exit 1;
}
curl --fail --location --silent --show-error "$dex_url" -o "$tmp/dex-tools.zip"
echo "$dex_sha  $tmp/dex-tools.zip" | sha256sum --check --status || {
    echo 'dex2jar checksum mismatch' >&2; exit 1;
}
unzip -q "$tmp/dex-tools.zip" -d "$tmp/dex-tools"
unzip -q "$tmp/upstream.apk" classes2.dex -d "$tmp"
tool=$(find "$tmp/dex-tools" -name d2j-dex2jar.sh -print -quit)
[[ -n "$tool" ]] || { echo 'dex2jar tool not found' >&2; exit 1; }
chmod +x "$(dirname "$tool")"/*.sh
"$tool" "$tmp/classes2.dex" -f -o "$tmp/classes.jar"

mkdir -p "$out/libs" "$out/jniLibs"
python3 - "$tmp/classes.jar" "$tmp/upstream.apk" "$out" <<'PY'
import os
import sys
from zipfile import ZIP_DEFLATED, ZipFile

classes, apk_path, out = sys.argv[1:]
prefixes = (
    'com/rupleide/netfix/core/dpibypass/CloudflareUnfreeze',
    'com/rupleide/netfix/core/dpibypass/SecureDnsRelay',
)
with ZipFile(classes) as src, ZipFile(os.path.join(out, 'libs/upstream-helpers.jar'), 'w', ZIP_DEFLATED) as dst:
    names = [name for name in src.namelist() if name.endswith('.class') and name.startswith(prefixes)]
    for prefix in prefixes:
        assert prefix + '.class' in names, f'Missing {prefix}'
    for name in names:
        dst.writestr(name, src.read(name))
    print(f'Recovered {len(names)} upstream helper classes')

with ZipFile(apk_path) as apk:
    for abi in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
        dest = os.path.join(out, 'jniLibs', abi, 'libbyedpi.so')
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, 'wb') as f:
            f.write(apk.read(f'lib/{abi}/libbyedpi.so'))
PY
