#!/bin/sh
# 在这台 Mac 上编出平板用的进程管理 App。
set -e
cd "$(dirname "$0")"
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  SDK="$ANDROID_HOME"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  SDK="$HOME/Library/Android/sdk"
else
  echo "找不到 Android SDK。设置 ANDROID_HOME，或安装到 \$HOME/Library/Android/sdk" >&2
  exit 1
fi
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-34/android.jar"
if [ ! -x "$BT/aapt2" ] || [ ! -f "$JAR" ]; then
  echo "需要 platforms;android-34 和 build-tools;36.0.0，当前 SDK 是 $SDK" >&2
  exit 1
fi
rm -rf build
mkdir -p build/classes build/dex res/mipmap-xxhdpi src/app/y700/proc

python3 - << 'PY'
from pathlib import Path
root = Path(".")
def jstr(name):
    text = (root / "device" / name).read_text()
    if not text.endswith("\n"):
        text += "\n"
    esc = (text.replace("\\", "\\\\")
               .replace("\"", "\\\"")
               .replace("\r", "\\r")
               .replace("\n", "\\n"))
    return '"' + esc + '"'
java = """package app.y700.proc;
final class Scripts {
    static final String SAMPLE = %s;
    static final String CLEAN = %s;
    private Scripts() {}
}
""" % (jstr("sample.sh"), jstr("clean.sh"))
(root / "src/app/y700/proc/Scripts.java").write_text(java)
print("scripts", len(java))
PY

if [ ! -f res/mipmap-xxhdpi/ic_launcher.png ]; then
  swift - "$PWD/res/mipmap-xxhdpi/ic_launcher.png" << 'SWIFT'
import AppKit
import Foundation
let path = CommandLine.arguments[1]
let s: CGFloat = 192
let image = NSImage(size: NSSize(width: s, height: s))
image.lockFocus()
NSColor(calibratedRed: 0x1F/255.0, green: 0x4E/255.0, blue: 0x5F/255.0, alpha: 1).setFill()
NSBezierPath(roundedRect: NSRect(x: 16, y: 16, width: 160, height: 160), xRadius: 36, yRadius: 36).fill()
let attrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 108, weight: .bold),
    .foregroundColor: NSColor.white
]
let str = "P" as NSString
let size = str.size(withAttributes: attrs)
str.draw(at: NSPoint(x: (s - size.width) / 2, y: (s - size.height) / 2 - 8), withAttributes: attrs)
image.unlockFocus()
guard let tiff = image.tiffRepresentation,
      let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else {
    fputs("icon failed\n", stderr)
    exit(1)
}
try png.write(to: URL(fileURLWithPath: path))
SWIFT
fi

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Y700 Proc, O=Android, C=CN"
fi

"$BT/aapt2" compile --dir res -o build/compiled.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --auto-add-overlay -o build/unsigned.apk build/compiled.zip
javac --release 11 -encoding UTF-8 -classpath "$JAR" -d build/classes \
  src/app/y700/proc/*.java
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex build/classes/app/y700/proc/*.class
(cd build && zip -qj unsigned.apk dex/classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out y700-proc.apk build/aligned.apk
"$BT/apksigner" verify y700-proc.apk
ls -l y700-proc.apk
