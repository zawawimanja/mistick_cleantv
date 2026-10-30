#!/usr/bin/env bash
# Script untuk pasang / kemaskini launcher CleanYourTV ke Mi TV Stick 4K
set -euo pipefail
cd "$(dirname "$0")"

TV_HOST="${TV_HOST:-192.168.0.141}"
echo "Memasang launcher CleanYourTV ke Mi TV Stick ($TV_HOST)..."
HOME_KEY_SERVICE=1 SKIP_BUILD=1 TV_HOST="$TV_HOST" launcher/build-and-install.sh
echo "Selesai!"

