#!/usr/bin/env bash
# Rebuild the supplied apktool-decoded NOVO PLAYER source with an embedded
# WireGuard WARP screen. Intended for a GitHub Actions Ubuntu runner.
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INPUT="$ROOT/NOVO_PLAYER_v3.8(40)_base_src.zip"
OUT="$ROOT/output"
WORK="$ROOT/.build-work"
APKTOOL_VERSION="2.11.1"
WIREGUARD_VERSION="1.0.20250531"

if [[ ! -f "$INPUT" ]]; then
  echo "Missing source archive: $INPUT" >&2
  exit 2
fi
command -v java >/dev/null || { echo "Java is required" >&2; exit 2; }
command -v gradle >/dev/null || { echo "Gradle is required (GitHub workflow installs it)" >&2; exit 2; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 2; }
command -v unzip >/dev/null || { echo "unzip is required" >&2; exit 2; }

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$ANDROID_HOME" && -d "$ANDROID_HOME/build-tools" ]] || { echo "Android SDK/build-tools are required" >&2; exit 2; }
BUILD_TOOLS="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -V | tail -n1)"
BT="$ANDROID_HOME/build-tools/$BUILD_TOOLS"
for tool in zipalign apksigner; do
  [[ -x "$BT/$tool" ]] || { echo "Missing Android build tool: $BT/$tool" >&2; exit 2; }
done

# Catch malformed inline Python before spending time on the Android build.
python3 - "$0" <<'PYCHECK'
import re, sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
blocks = list(re.finditer(r"<<'([A-Z][A-Z0-9_]*)'\n(.*?)\n\1\n", text, re.S))
if not blocks:
    raise SystemExit("No quoted Python heredocs found in the build script")
for index, match in enumerate(blocks, 1):
    compile(match.group(2), f"{path}:heredoc-{match.group(1)}-{index}", "exec")
print(f"Validated {len(blocks)} embedded Python blocks")
PYCHECK

rm -rf "$OUT" "$WORK"
mkdir -p "$OUT" "$WORK/base" "$WORK/tools"
unzip -q "$INPUT" -d "$WORK/base"
BASE_DIR="$WORK/base/NOVO_PLAYER_v3.8(40)_base_src"
[[ -f "$BASE_DIR/AndroidManifest.xml" && -d "$BASE_DIR/smali" ]] || {
  echo "The ZIP does not look like the supplied apktool-decoded source" >&2; exit 2;
}

# This source export came from a tool that writes apktool.json; Apktool CLI
# requires apktool.yml. Convert the metadata before invoking Apktool.
if [[ ! -f "$BASE_DIR/apktool.yml" ]]; then
  python3 - "$BASE_DIR/apktool.json" "$BASE_DIR/apktool.yml" <<'PYMETA'
import json, sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:3])
if not source.is_file():
    raise SystemExit(f"Neither apktool.yml nor apktool.json exists under {source.parent}")
meta = json.loads(source.read_text(encoding="utf-8"))
q = lambda value: json.dumps(str(value), ensure_ascii=False)
lines = ["!!brut.androlib.meta.MetaInfo"]
def scalar(name, value):
    lines.append(f"{name}: {json.dumps(value, ensure_ascii=False) if isinstance(value, bool) else q(value)}")
scalar("apkFileName", meta.get("apkFileName", "NOVO_PLAYER.apk"))
scalar("compressionType", meta.get("compressionType", False))
lines.append("doNotCompress:")
for value in meta.get("doNotCompress", []):
    lines.append(f"- {q(value)}")
lines.append(f"isFrameworkApk: {str(bool(meta.get('isFrameworkApk', False))).lower()}")
pkg = meta.get("PackageInfo", {})
lines.extend(["packageInfo:", f"  forcedPackageId: {q(pkg.get('forcedPackageId', '127'))}",
              f"  renameManifestPackage: {('null' if pkg.get('renameManifestPackage') is None else q(pkg.get('renameManifestPackage')))}"])
sdk = meta.get("sdkInfo", {})
lines.extend(["sdkInfo:", f"  minSdkVersion: {q(sdk.get('minSdkVersion', '21'))}",
              f"  targetSdkVersion: {q(sdk.get('targetSdkVersion', '33'))}"])
lines.append(f"sharedLibrary: {str(bool(meta.get('sharedLibrary', False))).lower()}")
lines.append(f"sparseResources: {str(bool(meta.get('sparseResources', False))).lower()}")
lines.append("unknownFiles:")
for key, value in sorted(meta.get("unknownFiles", {}).items()):
    lines.append(f"  {q(key)}: {q(value)}")
framework = meta.get("UsesFramework", {})
lines.append("usesFramework:")
lines.append("  ids:")
for value in framework.get("ids", [1]):
    lines.append(f"  - {value}")
tag = framework.get("tag")
lines.append(f"  tag: {('null' if tag is None else q(tag))}")
lines.append(f"version: {q('2.11.1')}")
version = meta.get("VersionInfo", {})
lines.extend(["versionInfo:", f"  versionCode: {q(version.get('versionCode', '1'))}",
              f"  versionName: {q(version.get('versionName', '1.0'))}"])
destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
PYMETA
fi

# Compile the new Activity/controller and fetch the official embeddable tunnel AAR.
gradle --no-daemon -p "$ROOT/warp-addon" :app:assembleDebug \
  -PwireguardVersion="$WIREGUARD_VERSION"
ADDON_APK="$ROOT/warp-addon/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$ADDON_APK" ]] || { echo "WARP component APK was not produced" >&2; exit 3; }
mkdir -p "$WORK/addon"
unzip -q "$ADDON_APK" -d "$WORK/addon"

# The base source already contains classes.dex and classes2.dex from its Smali
# trees. Append addon dex files after them, and merge WireGuard's native backend.
DEX_INDEX=3
while IFS= read -r dex; do
  cp "$dex" "$BASE_DIR/classes${DEX_INDEX}.dex"
  DEX_INDEX=$((DEX_INDEX + 1))
done < <(find "$WORK/addon" -maxdepth 1 -type f -name 'classes*.dex' | sort -V)
if [[ $DEX_INDEX -eq 3 ]]; then echo "No compiled add-on DEX files found" >&2; exit 3; fi
if [[ -d "$WORK/addon/lib" ]]; then
  mkdir -p "$BASE_DIR/lib"
  cp -a "$WORK/addon/lib/." "$BASE_DIR/lib/"
fi

# Register the controller screen and the WireGuard VpnService in this APK.
python3 - "$BASE_DIR/AndroidManifest.xml" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
if 'com.alisat.vpn.WarpActivity' in s or 'com.wireguard.android.backend.GoBackend$VpnService' in s:
    raise SystemExit('WARP components already exist in the manifest; refusing duplicate injection')
needle = '</application>'
if needle not in s:
    raise SystemExit('Could not find </application> in AndroidManifest.xml')
fragment = '''
        <activity android:name="com.alisat.vpn.WarpActivity" android:exported="false" android:label="Cloudflare WARP" android:screenOrientation="portrait" android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <service android:name="com.wireguard.android.backend.GoBackend$VpnService" android:permission="android.permission.BIND_VPN_SERVICE" android:exported="false">
            <intent-filter><action android:name="android.net.VpnService" /></intent-filter>
        </service>
    '''
s = s.replace(needle, fragment + '\n' + needle, 1)
p.write_text(s, encoding='utf-8')
PY

# Install a WARP shortcut into the existing app screens.
python3 - "$BASE_DIR/smali/com/shadeed/ibopro/apps/LTVApp.smali" <<'PYHOOK'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')
if 'WarpOverlayInstaller;->install' in s:
    raise SystemExit('WARP overlay hook already exists in LTVApp.smali')
start = s.find('.method public onCreate()V')
end = s.find('.end method', start)
if start < 0 or end < 0:
    raise SystemExit('Could not find LTVApp.onCreate() in Smali source')
method = s[start:end]
pos = method.rfind('    return-void')
if pos < 0:
    raise SystemExit('Could not find return from LTVApp.onCreate()')
hook = '    invoke-static {p0}, Lcom/alisat/vpn/WarpOverlayInstaller;->install(Landroid/app/Application;)V\n\n'
method = method[:pos] + hook + method[pos:]
p.write_text(s[:start] + method + s[end:], encoding='utf-8')
PYHOOK

APKTOOL_JAR="$WORK/tools/apktool.jar"
curl --fail --location --silent --show-error \
  "https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar" \
  -o "$APKTOOL_JAR"
java -jar "$APKTOOL_JAR" b "$BASE_DIR" -o "$WORK/unsigned.apk" -f

# apktool handles the two original Smali trees. Add the compiled component dex
# and native libraries to the final package after apktool's resource rebuild.
while IFS= read -r dex; do
  (cd "$BASE_DIR" && zip -q "$WORK/unsigned.apk" "$(basename "$dex")")
done < <(find "$BASE_DIR" -maxdepth 1 -type f -name 'classes[3-9]*.dex' | sort -V)
if [[ -d "$BASE_DIR/lib" ]]; then
  (cd "$BASE_DIR" && zip -qr "$WORK/unsigned.apk" lib)
fi

UNALIGNED="$WORK/unaligned.apk"
FINAL="$OUT/NOVO_PLAYER_3.8_WARP.apk"
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$UNALIGNED"

if [[ -n "${ANDROID_KEYSTORE_BASE64:-}" ]]; then
  KEYSTORE="$WORK/release.keystore"
  printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE"
  : "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD GitHub secret}"
  : "${ANDROID_KEY_ALIAS:?Set ANDROID_KEY_ALIAS GitHub secret}"
  : "${ANDROID_KEY_PASSWORD:?Set ANDROID_KEY_PASSWORD GitHub secret}"
  ANDROID_KEYSTORE_PASSWORD="$ANDROID_KEYSTORE_PASSWORD" \
  ANDROID_KEY_PASSWORD="$ANDROID_KEY_PASSWORD" \
    "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$ANDROID_KEY_ALIAS" \
      --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
      --out "$FINAL" "$UNALIGNED"
else
  KEYSTORE="$WORK/temporary-debug.keystore"
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname 'CN=NOVO PLAYER temporary build, OU=CI, O=Local, L=NA, S=NA, C=US' >/dev/null 2>&1
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --out "$FINAL" "$UNALIGNED"
  echo "Built with a temporary CI key. Configure Android signing secrets for a persistent release signature." >&2
fi
"$BT/apksigner" verify "$FINAL"
echo "APK: $FINAL"
