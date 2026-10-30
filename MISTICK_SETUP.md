# Panduan Mi TV Stick 4K (CleanYourTV)

## Status Terkini
- **Model**: Xiaomi TV Stick 4K (`MiTV-AYFR0`, codename `soul`)
- **Android**: Android TV 11 (SDK 30)
- **IP Address**: `192.168.0.141`
- **Launcher**: CleanYourTV (Gaya Apple TV) - Saiz: 561 KB
- **Dock Apps**: SmartTube, Netflix, IPTV Streamer Pro, Durioo+
- **Backup**: Disimpan di folder `backup/` dan `~/.cleanyourtv/`

---

## 1. Cara Kembalikan Ke Asal (Restore)
Jika anda mahu kembalikan semua aplikasi asal dan launcher Xiaomi seperti sedia kala:
```bash
./restore_my_tv.sh
```
Atau manual:
```bash
TV_HOST=192.168.0.141 tools/restore.sh debloat_mistick.txt com.google.android.tvlauncher
```

---

## 2. Cara Aktifkan Semula Aplikasi Tertentu (Enable App)
Jika anda mahu aktifkan semula mana-mana aplikasi yang di-disable:
```bash
TV_HOST=192.168.0.141 tools/tv.py "pm enable <nama_package>"
```
Contoh:
- Aktifkan Prime Video:
  ```bash
  TV_HOST=192.168.0.141 tools/tv.py "pm enable com.amazon.amazonvideo.livingroom"
  ```
- Aktifkan Xiaomi Update Service:
  ```bash
  TV_HOST=192.168.0.141 tools/tv.py "pm enable com.xiaomi.mitv.updateservice"
  ```

---

## 3. Cara Tukar Susunan Dock & Tema / Wallpaper

### A. Tukar Tema (Themes)
Repo ini menyediakan pelbagai tema menarik di dalam folder `themes/` (contoh: `themes/oled`, `themes/aurora`, `themes/slate`, `themes/halloween`).
Untuk pasang tema:
```bash
TV_HOST=192.168.0.141 tools/theme.sh themes/oled
```
Untuk reset ke tema asal:
```bash
TV_HOST=192.168.0.141 tools/theme.sh --reset
```

### B. Tukar Aplikasi di Dock
Edit fail [launcher/app/src/main/res/values/config.xml](file:///home/awi/tv/launcher/app/src/main/res/values/config.xml), bahagian `dock_apps`, kemudian jalankan:
```bash
JAVA_HOME=/home/awi/tv/.jdk17 ANDROID_HOME=/home/awi/tv/.android-sdk ./launcher/gradlew assembleRelease
./reinstall_launcher.sh
```

---

## 4. Keselamatan: Tutup Akses ADB (Penting)
Selepas berpuas hati dengan tetapan:
1. Pergi ke TV anda: **Settings → Device Preferences → Developer options**.
2. Matikan **USB debugging** (dan Network/ADB debugging jika ada).
3. Pilih **Revoke USB debugging authorizations**.
4. Reboot TV sekali untuk memastikan port 5555 ditutup sepenuhnya.

