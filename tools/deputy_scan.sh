#!/usr/bin/env bash
# deputy_scan.sh: inventory deep links, intent filters and App Actions
# declarations for every third-party app on a connected Android phone.
#
# Usage: connect the phone with USB debugging on, then:
#   bash deputy_scan.sh
# Output goes to ~/deputy-scan/ (summary.md is the file to read or share).
# Read-only: it lists and pulls APKs, it never launches anything on the phone.

set -u
OUT="$HOME/deputy-scan"
mkdir -p "$OUT/dumps" "$OUT/apks" "$OUT/actions"

command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first."; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "No device. Plug in the phone and allow USB debugging."; exit 1; }

# Find aapt2 for reading App Actions XML (optional but useful).
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -z "$AAPT2" ] && echo "Note: aapt2 not found, skipping App Actions scan. Set ANDROID_HOME to enable it."

SUM="$OUT/summary.md"
echo "# Deputy scan, $(date '+%Y-%m-%d %H:%M')" > "$SUM"
echo "" >> "$SUM"

PKGS=$(adb shell pm list packages -3 | sed 's/^package://' | tr -d '\r' | sort)
TOTAL=$(echo "$PKGS" | wc -l | tr -d ' ')
echo "Scanning $TOTAL third-party apps..."

for P in $PKGS; do
  adb shell dumpsys package "$P" > "$OUT/dumps/$P.txt" 2>/dev/null

  # Pull only the Activity Resolver Table section: that's what the app accepts.
  SECTION=$(awk '/Activity Resolver Table:/{f=1} /Receiver Resolver Table:|Service Resolver Table:|Domain verification status:|Key Set Manager:|Packages:/{if(f)exit} f' "$OUT/dumps/$P.txt")
  SCHEMES=$(echo "$SECTION" | grep -o 'Scheme: "[^"]*"' | sort -u | sed 's/Scheme: //' | tr '\n' ' ')
  HOSTS=$(echo "$SECTION" | grep -o 'Authority: "[^"]*"' | sort -u | sed 's/Authority: //' | tr '\n' ' ')
  PATHS=$(echo "$SECTION" | grep -oE '(Path|PathPrefix|PathPattern|AdvancedPathPattern): "[^"]*"' | sort -u | head -15 | tr '\n' ' ')
  ACTIONS=$(echo "$SECTION" | grep -o 'Action: "[^"]*"' | sed 's/Action: //' | grep -v 'android.intent.action.MAIN' | sort -u | tr '\n' ' ')

  # App Actions: look for res/xml files that declare <capability>.
  CAPS=""
  if [ -n "$AAPT2" ]; then
    APKPATH=$(adb shell pm path "$P" | tr -d '\r' | grep -m1 'base.apk' | sed 's/^package://')
    if [ -n "$APKPATH" ] && adb pull "$APKPATH" "$OUT/apks/$P.apk" >/dev/null 2>&1; then
      for X in $(unzip -l "$OUT/apks/$P.apk" 2>/dev/null | awk '{print $4}' | grep '^res/xml/.*\.xml$'); do
        TREE=$("$AAPT2" dump xmltree "$OUT/apks/$P.apk" --file "$X" 2>/dev/null)
        if echo "$TREE" | grep -q 'E: capability'; then
          echo "$TREE" > "$OUT/actions/$P.$(basename "$X").txt"
          NAMES=$(echo "$TREE" | grep -A2 'E: capability' | grep -o 'android:name.*=\"[^"]*\"' | grep -o '"[^"]*"' | tr '\n' ' ')
          CAPS="$CAPS $NAMES"
        fi
      done
      rm -f "$OUT/apks/$P.apk"   # keep disk use small; re-pull if you need it
    fi
  fi

  if [ -n "$SCHEMES$HOSTS$ACTIONS$CAPS" ]; then
    {
      echo "## $P"
      [ -n "$CAPS" ]    && echo "- **App Actions capabilities:** $CAPS"
      [ -n "$SCHEMES" ] && echo "- Schemes: $SCHEMES"
      [ -n "$HOSTS" ]   && echo "- Hosts: $HOSTS"
      [ -n "$PATHS" ]   && echo "- Paths (first 15): $PATHS"
      [ -n "$ACTIONS" ] && echo "- Actions: $ACTIONS"
      echo ""
    } >> "$SUM"
  fi
  printf '.'
done
echo ""

# Pull Latch to the top so it's easy to find.
LATCH=$(echo "$PKGS" | grep -i latch)
{
  echo "---"
  echo "Latch package(s) found: ${LATCH:-none}"
  echo "Apps with App Actions declarations: $(ls "$OUT/actions" 2>/dev/null | sed 's/\..*//' | sort -u | wc -l | tr -d ' ')"
} >> "$SUM"

echo "Done. Read $SUM (full dumps in $OUT/dumps, App Actions XML in $OUT/actions)."
