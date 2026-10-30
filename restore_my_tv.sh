#!/usr/bin/env bash
# Script untuk kembalikan (restore) Mi TV Stick 4K ke keadaan asal
set -euo pipefail
cd "$(dirname "$0")"

TV_HOST="${TV_HOST:-192.168.0.141}"
echo "Mengembalikan tetapan dan semua aplikasi asal ke Mi TV Stick ($TV_HOST)..."
TV_HOST="$TV_HOST" tools/restore.sh debloat_mistick.txt com.google.android.tvlauncher
echo "Selesai! Launcher asal telah diaktifkan semula."

