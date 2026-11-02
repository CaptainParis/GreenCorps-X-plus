#!/usr/bin/env bash
# Post-build gate: confirm 0.5.9 classes landed in the remapped release JAR.
# Usage: scripts/verify-release-classes.sh build/libs/greencorps-<version>.jar
set -euo pipefail

jar="${1:-}"
if [[ -z "$jar" || ! -f "$jar" ]]; then
	echo "FAIL: release JAR path required (e.g. build/libs/greencorps-0.5.9.jar)" >&2
	exit 1
fi

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

listing=$(jar tf "$jar")
fail=0
need() {
	local path="$1"
	if ! grep -F -q -- "$path" <<<"$listing"; then
		echo "FAIL: missing $path" >&2
		fail=1
	fi
}

need "dev/greencorps/modules/BaseBackup.class"
need "dev/greencorps/modules/LocationTooltip.class"
need "dev/greencorps/util/ItemLocations.class"
need "dev/greencorps/mixin/MixinDrawContextGc.class"
need "dev/greencorps/gui/WWaypointChip.class"
need "dev/greencorps/gui/GuiRound.class"
need "dev/greencorps/export/ExportRehearsal.class"

# No Re-capture all in panel
unzip -q -o "$jar" "dev/greencorps/gui/BaseBackupPanel.class" -d "$tmpdir"
if javap -c -p "$tmpdir/dev/greencorps/gui/BaseBackupPanel.class" 2>/dev/null | grep -q 'Re-capture'; then
	echo "FAIL: BaseBackupPanel still references Re-capture" >&2
	fail=1
fi

# clearRehearsalStatus + lastFailReason
unzip -q -o "$jar" "dev/greencorps/backup/BackupEngine.class" -d "$tmpdir"
if ! javap -p "$tmpdir/dev/greencorps/backup/BackupEngine.class" 2>/dev/null | grep -q 'clearRehearsalStatus'; then
	echo "FAIL: BackupEngine lacks clearRehearsalStatus" >&2
	fail=1
fi
unzip -q -o "$jar" "dev/greencorps/export/ExportRehearsal.class" -d "$tmpdir"
if ! javap -p "$tmpdir/dev/greencorps/export/ExportRehearsal.class" 2>/dev/null | grep -q 'lastFailReason'; then
	echo "FAIL: ExportRehearsal lacks lastFailReason" >&2
	fail=1
fi

# Yarn remaps Window.getFramebufferWidth → method_4489
unzip -q -o "$jar" "dev/greencorps/gui/map/GcMapView.class" -d "$tmpdir"
gcmap=$(javap -c "$tmpdir/dev/greencorps/gui/map/GcMapView.class" 2>/dev/null || true)
if ! grep -qE 'getFramebufferWidth|method_4489' <<<"$gcmap"; then
	echo "FAIL: GcMapView does not call getFramebufferWidth/method_4489" >&2
	fail=1
fi

if [[ "$fail" -ne 0 ]]; then
	echo "FAIL: verify-release-classes" >&2
	exit 1
fi
echo "PASS: verify-release-classes ($jar)"
