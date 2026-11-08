#!/usr/bin/env bash
set -e

script_dir=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/release/check-apk.sh
. "$script_dir/check-apk.sh"
repo_root=$(git -C "$script_dir/../.." rev-parse --show-toplevel 2>/dev/null) || {
  echo "Run release.sh from a checkout of the Airchive Git repository." >&2
  exit 1
}
version_file="$repo_root/config/airchive-version.properties"
keystore="$HOME/.airchive/airchive-release.jks"
key_alias=airchive-release
store_service=airchive-release-store-password
key_service=airchive-release-key-password
expected_certificate=2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202
notes_file=
publish_release=true

read_version_property() {
  local key
  key=$1
  sed -n "s/^[[:space:]]*${key}[[:space:]]*=[[:space:]]*//p" "$version_file" | tail -1 | tr -d '\r' | sed 's/[[:space:]]*$//'
}

if [ "${1:-}" = "--install" ]; then
  publish_release=false
  install_serial=${2:-}
  if [ -z "$install_serial" ]; then
    echo "--install requires an explicit device serial before any build is started." >&2
    exit 2
  fi
  [ "$#" -eq 2 ] || { echo "Usage: release.sh --install <serial>" >&2; exit 2; }
  version_name=$(read_version_property versionName)
else
  if [ "$#" -lt 1 ]; then
    echo "Usage: release.sh <versionName> --notes-file <path> | release.sh --install <serial>" >&2
    exit 2
  fi
  version_name=$1
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --notes-file)
        [ "$#" -ge 2 ] || { echo "--notes-file requires a path." >&2; exit 2; }
        notes_file=$2
        shift 2
        ;;
      *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
  done
  if [ -z "$notes_file" ] || [ ! -f "$notes_file" ]; then
    echo "Publishing requires an existing --notes-file." >&2
    exit 2
  fi
fi

version_code=$(read_version_property versionCode)
if [ -z "$version_name" ] || [ -z "$version_code" ] || ! [[ "$version_code" =~ ^[0-9]+$ ]]; then
  echo "config/airchive-version.properties must contain valid versionName and versionCode values." >&2
  exit 1
fi
if [ "$(read_version_property versionName)" != "$version_name" ]; then
  echo "Requested version '$version_name' does not match config/airchive-version.properties." >&2
  exit 1
fi

cd "$repo_root"
if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree is dirty; commit or remove changes before releasing." >&2
  exit 1
fi
head_commit=$(git rev-parse HEAD)
git fetch origin main >/dev/null
if ! git merge-base --is-ancestor "$head_commit" origin/main; then
  echo "HEAD is not contained in origin/main; refusing to build or sign." >&2
  exit 1
fi

if [ "$publish_release" = true ]; then
  tag="v$version_name"
  if git show-ref --verify --quiet "refs/tags/$tag"; then
    echo "Release tag $tag already exists locally." >&2
    exit 1
  fi
  if git ls-remote --exit-code --tags origin "refs/tags/$tag" >/dev/null 2>&1; then
    echo "Release tag $tag already exists on origin." >&2
    exit 1
  fi
fi

if [ ! -f "$keystore" ]; then
  echo "Release keystore not found at $keystore; run setup-signing.sh first." >&2
  exit 1
fi
command -v security >/dev/null 2>&1 || { echo "macOS security is required for Keychain access." >&2; exit 1; }
store_password=$(security find-generic-password -s "$store_service" -a "$USER" -w) || {
  echo "Keychain item '$store_service' was not found; run setup-signing.sh." >&2
  exit 1
}
key_password=$(security find-generic-password -s "$key_service" -a "$USER" -w) || {
  echo "Keychain item '$key_service' was not found; run setup-signing.sh." >&2
  exit 1
}
if [ -z "$store_password" ] || [ -z "$key_password" ]; then
  echo "Keychain returned an empty signing password; run setup-signing.sh." >&2
  exit 1
fi

(
  export AIRCHIVE_RELEASE_STORE_FILE="$keystore"
  export AIRCHIVE_RELEASE_KEY_ALIAS="$key_alias"
  export AIRCHIVE_RELEASE_STORE_PASSWORD="$store_password"
  export AIRCHIVE_RELEASE_KEY_PASSWORD="$key_password"
  ./gradlew --no-daemon --no-configuration-cache :androidApp:assembleRelease "-Pairchive.buildCommit=$head_commit"
)
unset store_password key_password

apk="$repo_root/androidApp/build/outputs/apk/release/androidApp-release.apk"
"$script_dir/check-apk.sh" "$apk" "$version_name" "$version_code" "$head_commit"

if [ "$publish_release" = false ]; then
  if [ -z "${ANDROID_HOME:-}" ] || [ ! -x "$ANDROID_HOME/platform-tools/adb" ]; then
    echo "ANDROID_HOME/platform-tools/adb is required for --install." >&2
    exit 1
  fi
  adb="$ANDROID_HOME/platform-tools/adb"
  state=$("$adb" -s "$install_serial" get-state 2>&1) || {
    echo "Target device '$install_serial' is not connected or authorized; APK is ready for a later install." >&2
    exit 1
  }
  if [ "$(printf '%s' "$state" | tr -d '\r\n')" != "device" ]; then
    echo "Target device '$install_serial' is not ready; refusing to install." >&2
    exit 1
  fi
  package_info=$("$adb" -s "$install_serial" shell dumpsys package cloud.airchive.personal 2>&1) || {
    echo "Could not inspect the installed Airchive package; refusing a fresh install." >&2
    exit 1
  }
  installed_code=$(printf '%s\n' "$package_info" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)
  if [ -z "$installed_code" ]; then
    echo "No existing Airchive installation was found; refusing a fresh install." >&2
    exit 1
  fi
  if [ "$installed_code" -ge "$version_code" ]; then
    echo "Installed versionCode $installed_code is not lower than new versionCode $version_code; refusing to install." >&2
    exit 1
  fi
  installed_path=$("$adb" -s "$install_serial" shell pm path cloud.airchive.personal 2>&1 | sed -n 's/^package://p' | tr -d '\r' | grep '/base\.apk$' | head -1)
  if [ -z "$installed_path" ]; then
    echo "Could not identify the installed base APK; refusing to install." >&2
    exit 1
  fi
  temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airchive-installed-apk.XXXXXX")
  trap 'rm -r "$temp_dir"' EXIT HUP INT TERM
  "$adb" -s "$install_serial" pull "$installed_path" "$temp_dir/installed.apk" >/dev/null
  build_tools_dir=$(find_build_tools)
  apksigner="$build_tools_dir/apksigner"
  installed_signers=$("$apksigner" verify --print-certs "$temp_dir/installed.apk" 2>&1) || {
    echo "Could not verify the installed APK signature; refusing to install." >&2
    exit 1
  }
  signer_count=$(printf '%s\n' "$installed_signers" | grep -c 'Signer #[0-9][0-9]* certificate SHA-256 digest:' || true)
  installed_certificate=$(printf '%s\n' "$installed_signers" | sed -n 's/.*certificate SHA-256 digest: *//p' | head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]')
  if [ "$signer_count" -ne 1 ] || [ "$installed_certificate" != "$expected_certificate" ]; then
    echo "Installed app does not use the pinned Airchive release identity; refusing to install." >&2
    exit 1
  fi
  "$adb" -s "$install_serial" install -r "$apk"
  printf 'Installed versionCode %s over existing versionCode %s on %s.\n' "$version_code" "$installed_code" "$install_serial"
  exit 0
fi

asset_name="airchive-$version_name-$version_code.apk"
asset_path="$repo_root/androidApp/build/outputs/apk/release/$asset_name"
cp "$apk" "$asset_path"
git tag "$tag" "$head_commit"
git push origin "$tag"
gh release create "$tag" "$asset_path" --draft --title "$tag" --notes-file "$notes_file" --verify-tag
printf 'Created draft release %s with %s.\n' "$tag" "$asset_name"
