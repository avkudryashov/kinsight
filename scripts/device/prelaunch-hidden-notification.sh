#!/usr/bin/env bash
# Pre-launch check S-M3 (docs/superpowers/plans/2026-10-11-prelaunch-platform.md, Task 12): on a paired child, a hidden
# Kinsight notification pauses usage, the child's Overview says so, the parent still learns NOTIFICATION_HIDDEN, and the
# backlog goes out once notifications are allowed again. Run after stage4-pair.sh (paired, the child shares).
# Notifications are hidden by revoking POST_NOTIFICATIONS (Android kills the process; `am start` brings Kinsight back and
# the service sees the notification as not visible). `cmd notification` cannot turn a channel off; the hand alternative
# is Settings, Apps, Kinsight, Notifications, off.
# Usage: scripts/device/prelaunch-hidden-notification.sh <parent-serial> <child-serial>
set -euo pipefail
ADB=${ADB:-/home/anatoly/.local/opt/android-sdk/platform-tools/adb}
P=$1
C=$2
PKG=app.kinsight
RX=$PKG/.debug.DebugGroupReceiver

dump() { $ADB -s "$1" shell uiautomator dump /sdcard/kinsight-ui.xml >/dev/null 2>&1 || true; $ADB -s "$1" shell cat /sdcard/kinsight-ui.xml; }
has_tag() { dump "$1" | grep -qF "resource-id=\"$2\""; }
dbg() { $ADB -s "$1" shell am broadcast -n $RX -a "app.kinsight.debug.$2" "${@:3}" | sed -n 's/.*data="\(.*\)"$/\1/p'; }
field() { dbg "$1" STATUS | tr ' ' '\n' | sed -n "s/^$2=//p"; }
until_field() { # device field value tries
  for _ in $(seq "$4"); do dbg "$1" SYNC_NOW >/dev/null || true; [ "$(field "$1" "$2")" = "$3" ] && return 0; sleep 2; done
  echo "timeout: $2 != $3 on $1" >&2; return 1
}

echo "== baseline"
dbg "$C" SHARING --ez on true >/dev/null
dbg "$C" BUMP >/dev/null
S0=$(field "$C" usageSeq)
H0=$(field "$P" hiddenEvents)
echo "child usageSeq $S0, parent hiddenEvents $H0"

echo "== hide the notification"
T0=$(date +%s)
$ADB -s "$C" shell pm revoke $PKG android.permission.POST_NOTIFICATIONS
sleep 2
$ADB -s "$C" shell am start -n $PKG/.MainActivity >/dev/null
until_field "$C" hidden true 60
echo "hidden on the child after $(($(date +%s) - T0)) s"

echo "== usage while hidden is counted, not signed"
$ADB -s "$C" shell am start -a android.settings.SETTINGS >/dev/null
sleep 40
$ADB -s "$C" shell am start -n $PKG/.MainActivity >/dev/null
sleep 2
OUT=$(dbg "$C" BUMP)
echo "$OUT"
echo "$OUT" | grep -q "paused=true" || { echo "the publish was not paused" >&2; exit 1; }
[ "$(field "$C" usageSeq)" = "$S0" ] || { echo "usage was signed while the notification was hidden" >&2; exit 1; }

echo "== the child's Overview shows the banner"
for _ in $(seq 30); do has_tag "$C" dash_notification_settings && break; sleep 1; done
has_tag "$C" dash_notification_settings || { echo "no hidden-notification banner on the child" >&2; exit 1; }

echo "== the parent hears NOTIFICATION_HIDDEN"
T1=$(date +%s)
for _ in $(seq 60); do
  dbg "$C" SYNC_NOW >/dev/null || true
  [ "$(field "$P" hiddenEvents)" -gt "$H0" ] && break
  sleep 5
done
[ "$(field "$P" hiddenEvents)" -gt "$H0" ] || { echo "no NOTIFICATION_HIDDEN on the parent" >&2; exit 1; }
echo "NOTIFICATION_HIDDEN on the parent after $(($(date +%s) - T1)) s"

echo "== allow notifications again: the backlog goes out"
T2=$(date +%s)
$ADB -s "$C" shell pm grant $PKG android.permission.POST_NOTIFICATIONS
$ADB -s "$C" shell am start -n $PKG/.MainActivity >/dev/null
until_field "$C" hidden false 60
for _ in $(seq 60); do
  dbg "$C" SYNC_NOW >/dev/null || true
  [ "$(field "$C" usageSeq)" -gt "$S0" ] && break
  sleep 5
done
[ "$(field "$C" usageSeq)" -gt "$S0" ] || { echo "the backlog was not published after the notification came back" >&2; exit 1; }
echo "backlog signed after $(($(date +%s) - T2)) s (usageSeq $(field "$C" usageSeq))"
for d in "$P" "$C"; do echo "$d crashes: $($ADB -s "$d" logcat -d -b crash | grep -c "Process: $PKG," || true)"; done
echo "PASS"
