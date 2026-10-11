#!/usr/bin/env bash
# Stage 5c on three devices (docs/superpowers/plans/2026-10-11-stage5c-shared-categories.md, Task 4): after
# stage4-pair.sh has paired <parent> and <child>, a second parent joins through the parent invitation, the first parent
# changes the category of one of the child's apps, and the second parent sees the shared category with the author.
# Taps Compose test tags exposed as resource ids. Clears the data of app.kinsight on <parent2> only; never reboots.
# Usage: scripts/device/stage5c-share.sh <parent-serial> <child-serial> <parent2-serial> <apk>
set -euo pipefail
ADB=${ADB:-/home/anatoly/.local/opt/android-sdk/platform-tools/adb}
P=$1
C=$2
Q=$3
APK=$4
PKG=app.kinsight
RX=$PKG/.debug.DebugGroupReceiver

dump() { $ADB -s "$1" shell uiautomator dump /sdcard/kinsight-ui.xml >/dev/null 2>&1 || true; $ADB -s "$1" shell cat /sdcard/kinsight-ui.xml 2>/dev/null || true; }
bounds() {
  dump "$1" | tr '>' '\n' | grep -E "$2" | head -1 |
    sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'
}
has() { [ -n "$(bounds "$1" "$2")" ]; }
wait_for() {
  local d=$1 re=$2 n=${3:-60}
  for _ in $(seq "$n"); do has "$d" "$re" && return 0; sleep 1; done
  echo "timeout: $re on $d" >&2
  dump "$d" | tr '>' '\n' | grep -o 'resource-id="[a-z_A-Z0-9.:/]*"' | sort -u >&2
  return 1
}
tap_re() {
  local d=$1 re=$2
  wait_for "$d" "$re" "${3:-60}"
  local b
  read -r -a b <<<"$(bounds "$d" "$re")"
  $ADB -s "$d" shell input tap $(((b[0] + b[2]) / 2)) $(((b[1] + b[3]) / 2))
  sleep 1.5
}
tap() { tap_re "$1" "resource-id=\"$2\"" "${3:-60}"; }
# The top-apps rows sit below the charts: swipe up (at most 8 times) until <regex> is in the dump.
scroll_to() {
  local d=$1 re=$2
  for _ in $(seq 8); do
    has "$d" "$re" && return 0
    $ADB -s "$d" shell input swipe 540 1800 540 700 300
    sleep 1
  done
  has "$d" "$re"
}
# The category chip's merged content description ("Category: Games"), else its text.
chip() {
  # The chip node itself has no text; its label is the first text node after it.
  dump "$1" | tr '>' '\n' | grep -A4 'resource-id="app_category_chip"' | sed -n 's/.* text="\([^"][^"]*\)".*/\1/p' | head -1
}
CHILD_NAME=$($ADB -s "$C" shell getprop ro.product.model | tr -d '\r')
dbg() { $ADB -s "$1" shell am broadcast -n $RX -a "app.kinsight.debug.$2" | sed -n 's/.*data="\(.*\)"$/\1/p'; }

echo "== second parent: install and grant"
$ADB -s "$Q" install -r "$APK" >/dev/null
$ADB -s "$Q" shell pm clear $PKG >/dev/null
for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
  $ADB -s "$Q" shell pm grant $PKG android.permission.$p
done

echo "== first parent invites a parent's phone"
$ADB -s "$P" shell am start -n $PKG/.MainActivity >/dev/null
tap "$P" nav_group 30
tap "$P" grp_invite_parent
URI=none
for _ in $(seq 30); do
  URI=$(dbg "$P" INVITE_URI)
  [ "${URI#kinsight://pair/}" != "$URI" ] && break
  sleep 1
done
[ "${URI#kinsight://pair/}" != "$URI" ] || { echo "no invite on the parent" >&2; exit 1; }

echo "== second parent joins"
$ADB -s "$Q" shell am start -a android.intent.action.VIEW -d "$URI" $PKG >/dev/null
confirmed=0
for _ in $(seq 120); do
  if [ $confirmed = 0 ] && has "$P" 'resource-id="inv_confirm"'; then tap "$P" inv_confirm; confirmed=1; continue; fi
  if has "$Q" 'resource-id="nav_overview"'; then break; fi
  for t in onb_done onb_agree onb_continue; do
    if has "$Q" "resource-id=\"$t\""; then tap "$Q" $t 2; break; fi
  done
  sleep 1
done
wait_for "$Q" 'resource-id="nav_overview"' 30
has "$P" 'resource-id="inv_done"' && tap "$P" inv_done 5 || true
echo "parent2: $(dbg "$Q" STATUS)"

echo "== first parent changes the category of a child's app"
for d in "$C" "$P"; do dbg "$d" SYNC_NOW >/dev/null; done
sleep 10
$ADB -s "$P" shell am start -n $PKG/.MainActivity >/dev/null
tap "$P" nav_overview 30
tap_re "$P" "text=\"$CHILD_NAME\"" 60
wait_for "$P" 'resource-id="det_' 60
scroll_to "$P" 'resource-id="det_app_[^"]*"' || true
tap_re "$P" 'resource-id="det_app_[^"]*"' 60
APP=$(dump "$P" | tr '>' '\n' | grep -o 'resource-id="app_label"[^>]*' | head -1 | sed -n 's/.*text="\([^"]*\)".*/\1/p')
[ -n "$APP" ] || APP=$(dump "$P" | tr '>' '\n' | grep 'resource-id="app_label"' | sed -n 's/.*text="\([^"]*\)".*/\1/p' | head -1)
# A target other than the app's current category, so the final check can only pass on the shared change.
wait_for "$P" 'resource-id="app_category_chip"' 30
CURRENT=$(chip "$P")
if printf '%s' "$CURRENT" | grep -qE 'Games|Игры'; then
  TARGET=VIDEO TARGET_RE='Video|Видео'
else
  TARGET=GAMES TARGET_RE='Games|Игры'
fi
tap "$P" app_category
tap "$P" "app_category_option_$TARGET" 10
tap "$P" app_category_confirm 10
echo "changed: $APP ($CURRENT) → $TARGET on the first parent"

echo "== second parent sees it"
T0=$(date +%s)
SHARED=0
for _ in $(seq 60); do
  for d in "$P" "$Q"; do dbg "$d" SYNC_NOW >/dev/null; done
  sleep 5
  $ADB -s "$Q" shell am start -n $PKG/.MainActivity >/dev/null
  tap "$Q" nav_overview 10 || true
  tap_re "$Q" "text=\"$CHILD_NAME\"" 10 || continue
  wait_for "$Q" 'resource-id="det_' 10 || continue
  scroll_to "$Q" 'resource-id="det_app_[^"]*"' || true
  tap_re "$Q" "resource-id=\"det_app_[^\"]*\"" 10 || continue
  if chip "$Q" | grep -qE "$TARGET_RE" && dump "$Q" | grep -qE 'Changed by|Изменил'; then
    SHARED=1
    echo "shared category on the second parent after $(($(date +%s) - T0)) s"
    break
  fi
  $ADB -s "$Q" shell input keyevent BACK
done
[ "$SHARED" = 1 ] || { echo "category not shared: need $TARGET_RE and «Changed by|Изменил» on the second parent" >&2; exit 1; }
for d in "$P" "$C" "$Q"; do echo "$d crashes: $($ADB -s "$d" logcat -d -b crash | grep -c "Process: $PKG," || true)"; done
echo "PASS"
