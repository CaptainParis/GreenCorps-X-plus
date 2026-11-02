#!/usr/bin/env bash
# Build the remapped jar and install it into both Prism 2b2t instances (MAIN + ALT).
# Replaces any older greencorps-*.jar so each instance loads this version only.
#
# Destinations:
#   1. argument (single dest; skips alt)
#   2. DEPLOY_MODS (+ DEPLOY_MODS_ALT if set)
#   3. deploy.mods + deploy.mods.alt in local.properties
#   4. instance.mods (MAIN only fallback)
#
# local.properties is gitignored. Example:
#   instance.mods=…/2b2t – MAIN/minecraft/mods
#   deploy.mods=…/2b2t – MAIN/minecraft/mods
#   deploy.mods.alt=…/2b2t – ALT/minecraft/mods
#
# DEPLOY_SKIP_BUILD=1 skips Gradle when build/libs is already current.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

prop() {
	local key="$1"
	local file="$root/local.properties"
	[[ -f "$file" ]] || return 0
	awk -F= -v k="$key" '$1==k { v=substr($0, index($0, "=")+1) } END { print v }' "$file" | tr -d '\r'
}

install_one() {
	local dest="$1"
	dest="${dest/#\~/$HOME}"
	if [[ ! -d "$dest" ]]; then
		echo "FAIL: mods folder not found: $dest" >&2
		exit 1
	fi
	shopt -s nullglob
	for old in "$dest"/greencorps-*.jar; do
		base=$(basename "$old")
		if [[ "$base" != "greencorps-${version}.jar" ]]; then
			rm -f "$old"
			echo "removed $old"
		fi
	done
	shopt -u nullglob
	cp -f "$jar" "$dest/greencorps-${version}.jar"
	echo "PASS: $dest/greencorps-${version}.jar"
}

version=$(awk -F= '$1=="mod_version" { print $2 }' gradle.properties | tr -d '[:space:]')
if [[ -z "$version" ]]; then
	echo "FAIL: mod_version missing from gradle.properties" >&2
	exit 1
fi

if [[ "${DEPLOY_SKIP_BUILD:-}" != "1" ]]; then
	./gradlew build
fi

jar="$root/build/libs/greencorps-${version}.jar"
if [[ ! -f "$jar" ]]; then
	echo "FAIL: missing $jar (refusing *-dev and *-sources)" >&2
	exit 1
fi

mkdir -p "$root/dist"
cp -f "$jar" "$root/dist/greencorps-${version}.jar"

if [[ -n "${1:-}" ]]; then
	install_one "$1"
elif [[ -n "${DEPLOY_MODS:-}" ]]; then
	install_one "$DEPLOY_MODS"
	if [[ -n "${DEPLOY_MODS_ALT:-}" ]]; then
		install_one "$DEPLOY_MODS_ALT"
	fi
else
	main=$(prop deploy.mods)
	alt=$(prop deploy.mods.alt)
	if [[ -z "$main" ]]; then
		main=$(prop instance.mods)
	fi
	if [[ -z "$main" ]]; then
		echo "FAIL: set deploy.mods in local.properties to the MAIN instance mods folder" >&2
		exit 1
	fi
	install_one "$main"
	if [[ -n "$alt" ]]; then
		install_one "$alt"
	fi
fi

echo "Test the new build in game."
