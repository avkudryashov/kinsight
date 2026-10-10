#!/usr/bin/env bash
# Two-phone run of stage 4 (docs/superpowers/plans/2026-10-10-stage4-ui.md, Task 21): parent creates a group, child
# joins through the invitation deep link, the parent confirms, the child's screen time reaches the parent, a pause
# raises an integrity notification on the parent. Taps Compose test tags exposed as resource ids, so the phones'
# language does not matter. Clears the data of app.kinsight only; never reboots and never touches other packages.
# Usage: scripts/device/stage4-pair.sh <parent-serial> <child-serial> <apk>
set -euo pipefail
ADB=${ADB:-/home/anatoly/.local/opt/android-sdk/platform-tools/adb}
P=$1
C=$2
APK=$3
PKG=app.kinsight
RX=$PKG/.debug.DebugGroupReceiver

dump() { $ADB -s "$1" shell uiautomator dump /sdcard/kinsight-ui.xml >/dev/null 2>&1 || true; $ADB -s "$1" shell cat /sdcard/kinsight-ui.xml; }
bounds() {
  dump "$1" | tr '>' '\n' | grep -F "resource-id=\"$2\"" | head -1 |
    sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p'
}
wait_tag() {
  local d=$1 tag=$2 n=${3:-60}
  for _ in $(seq "$n"); do [ -n "$(bounds "$d" "$tag")" ] && return 0; sleep 1; done
  echo "timeout: $tag on $d" >&2
  dump "$d" | tr '>' '\n' | grep -o 'resource-id="[a-z_]*"' | sort -u >&2
  return 1
}
tap() {
  local d=$1 tag=$2
  wait_tag "$d" "$tag" "${3:-60}"
  local b
  read -r -a b <<<"$(bounds "$d" "$tag")"
  $ADB -s "$d" shell input tap $(((b[0] + b[2]) / 2)) $(((b[1] + b[3]) / 2))
  sleep 1
}
dbg() { $ADB -s "$1" shell am broadcast -n $RX -a "app.kinsight.debug.$2" | sed -n 's/.*data="\(.*\)"$/\1/p'; }

echo "== install and grant"
for d in "$P" "$C"; do
  $ADB -s "$d" install -r "$APK" >/dev/null
  $ADB -s "$d" shell pm clear $PKG >/dev/null
  for p in BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
    $ADB -s "$d" shell pm grant $PKG android.permission.$p
  done
done
$ADB -s "$C" shell appops set $PKG GET_USAGE_STATS allow

echo "== parent creates the group"
$ADB -s "$P" shell am start -n $PKG/.MainActivity >/dev/null
tap "$P" onb_create
tap "$P" onb_continue # name: the phone model
tap "$P" onb_continue # what a parent's phone does; Nearby and notifications are granted, so skipped
tap "$P" onb_continue # battery
tap "$P" onb_done
tap "$P" nav_group 30
tap "$P" grp_invite_child
URI=none
for _ in $(seq 30); do
  URI=$(dbg "$P" INVITE_URI)
  [ "${URI#kinsight://pair/}" != "$URI" ] && break
  sleep 1
done
[ "${URI#kinsight://pair/}" != "$URI" ] || { echo "no invite on the parent" >&2; exit 1; }
echo "invite ${URI:0:32}..."

echo "== child joins"
$ADB -s "$C" shell am start -a android.intent.action.VIEW -d "$URI" $PKG >/dev/null
tap "$C" onb_continue # name
tap "$C" onb_agree    # consent; Nearby granted, the scanner is skipped by the deep link
tap "$P" inv_confirm 120
tap "$C" onb_continue 120 # battery; usage access and notifications are granted
tap "$C" onb_done
tap "$P" inv_done 30
echo "parent: $(dbg "$P" STATUS)"
echo "child:  $(dbg "$C" STATUS)"

echo "== child's screen time reaches the parent"
CHILD_NAME=$($ADB -s "$C" shell getprop ro.product.model | tr -d '\r')
tap "$P" nav_overview 30
T0=$(date +%s)
for _ in $(seq 120); do
  dbg "$C" SYNC_NOW >/dev/null
  if dump "$P" | grep -qF "$CHILD_NAME"; then echo "child card on the parent after $(($(date +%s) - T0)) s"; break; fi
  sleep 5
done
dump "$P" | grep -qF "$CHILD_NAME" || { echo "no child card on the parent" >&2; exit 1; }

echo "== pause on the child raises an integrity notification on the parent"
tap "$C" nav_overview 30
if $ADB -s "$P" shell dumpsys notification --noredact | grep -q 'channel=kinsight-integrity'; then
  echo "integrity notification on the parent before the pause" >&2; exit 1
fi
tap "$C" dash_pause
tap "$C" dash_pause_confirm
T1=$(date +%s)
for _ in $(seq 120); do
  dbg "$C" SYNC_NOW >/dev/null
  if $ADB -s "$P" shell dumpsys notification --noredact | grep -q 'channel=kinsight-integrity'; then
    echo "integrity notification after $(($(date +%s) - T1)) s"; break
  fi
  sleep 5
done
$ADB -s "$P" shell dumpsys notification --noredact | grep -q 'channel=kinsight-integrity' || { echo "no integrity notification" >&2; exit 1; }
for d in "$P" "$C"; do echo "$d crashes: $($ADB -s "$d" logcat -d -b crash | grep -c "Process: $PKG," || true)"; done
echo "PASS"
