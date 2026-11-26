#!/system/bin/sh
# SPDX-License-Identifier: LicenseRef-PolyForm-Noncommercial-1.0.0
# lumen_installer.sh: the root side of Lumen Installer, one job per run.
#
# How it runs: the app copies this file and job.conf into its own files dir, sets
#   vendor.xgimi.system.debug.cmd = "/system/bin/sh <files>/root/lumen_installer.sh"
# and starts XGIMI's stock `debugserver` service (init.xgimi.common.rc: user root, oneshot, seclabel logserver; its
# debugserver.sh ends with `cmd=$(getprop vendor.xgimi.system.debug.cmd); ${cmd}`). The stock
# firmwares are SELinux permissive, so an ordinary app may set that property and start the service.
#
# Jobs (job.conf: ACTION=, NONCE=, then the parameters the app computed; parsed, never sourced):
#   probe    read only: what an app cannot see (root, gsi_tool, /metadata/gsi, a trial installed or running, a
#            pending A/B update and what a finished one left, free space of /data in KiB, size of super in KiB).
#   install  `cat IMG TAIL | gsi_tool install --gsi-size TOTAL --userdata-size USERDATA --no-reboot`, then
#            `gsi_tool enable --single-boot`; writes only the trial's own files (under /data/gsi and /metadata/gsi).
#            Any failure: `gsi_tool wipe`, nothing stays armed. MODEL: the model code the app found in the signed
#            list of projectors; the job runs only when the projector reports that code itself.
#   enable   arm an installed trial for one more boot (MODEL as for install)
#   remove   gsi_tool wipe (refused while the trial runs)
#   reboot   sync + reboot (only when the user pressed Restart)
#   dump     read only: the stock system info the user agreed to send (a few MB in the zip, the app adds the stock
#            settings app without its media for a firmware the catalog does not know). Runs every line of dump_table below (getprop, the partition table, dumpsys of every
#            service we study a unit with, pm lists, logcat -b all, /proc and /sys reads, the vendor and XGIMI config
#            text files, the stock settings app's own settings), one output file each in $HERE/dump (capped, readable
#            by the app), plus commands.txt (name, exit code, bytes, seconds, command). SETTINGS_PKG: the stock settings
#            app the app found (its settings files are read). Partition CONTENT is read only for the sha256 of the A/B
#            firmware partitions of DUMP_HASH (an XGIMI update writes them the same on every unit); a partition in one
#            copy (persist, uenv, tvcertificate, xgimicri, xgimisps, xgimidatabase, project_id ...) is the unit's own
#            and only listed with its size. Never Wi-Fi, accounts, keys, DRM or HDCP material, calibration data. The
#            app removes personal data before anything leaves. The full firmware copy to a USB stick is LumenDump's
#            (apps/LumenDump, apps/core/lumen-dump), not this app's.
# It never reboots on its own and never writes a partition, misc, uenv or vbmeta.
#
# Output (status.txt, tailed by the app): LUMENINST_START <nonce>, KV k=v, LOG text, LUMENINST_DONE <nonce> or
# LUMENINST_FAIL <nonce> <code> [detail].
#
# Portability: Android's sh is mksh with toybox; some projectors have a 32-bit userspace, so the shell never does arithmetic on
# byte counts (only KiB, and byte counts are compared as strings). Paths are overridable for the host tests
# (LI_* variables; the stock init service passes none of them).

HERE=${LI_HERE:-$(cd "$(dirname "$0")" && pwd)}
JOB=$HERE/job.conf
STATUS=${LI_STATUS:-$HERE/status.txt}
METADATA=${LI_METADATA:-/metadata}
DATA=${LI_DATA:-/data}
BYNAME=${LI_BYNAME:-/dev/block/by-name}
SYSBLOCK=${LI_SYSBLOCK:-/sys/class/block}
XGIMICONFIG=${LI_XGIMICONFIG:-/mnt/vendor/xgimiconfig}
MAPPER=${LI_MAPPER:-/dev/block/mapper}
MOUNTS=${LI_MOUNTS:-/proc/mounts}

# The script knows no model: which models may take a trial is the signed list of projectors' (checked by the app).
# A write job carries the model code the app checked (MODEL) and runs only on a projector that reports that code.

NONCE=
ACTION=
for k in IMG TAIL IMG_SIZE TAIL_SIZE TOTAL USERDATA IMG_SHA TAIL_SHA NEED_KIB MODEL SETTINGS_PKG; do eval "J_$k="; done

out() { echo "$*" >>"$STATUS" 2>/dev/null; }
kv() { out "KV $1=$2"; }
say() { out "LOG $*"; }
done_() { sync; out "LUMENINST_DONE $NONCE"; exit 0; }
fail() { c=$1; shift; out "LUMENINST_FAIL $NONCE $c $*"; sync; exit 1; }

prop() { getprop "$1" 2>/dev/null; }
have() { command -v "$1" >/dev/null 2>&1; }
free_kib() { df -k "$1" 2>/dev/null | awk 'NR==2 {print $4}'; }
fsize() { ls -lnL "$1" 2>/dev/null | awk '{print $5}'; }   # -L: the size of the file a link points to
sha() { sha256sum "$1" 2>/dev/null | awk '{print $1}'; }
blk_kib() {
  dev=$(readlink -f "$BYNAME/$1" 2>/dev/null) || return 1
  n=${dev##*/}
  [ -r "$SYSBLOCK/$n/size" ] || return 1
  s=$(cat "$SYSBLOCK/$n/size") || return 1
  echo $((s / 2))
}
model() { m=$(prop ro.boot.xgimi.modelname); [ -n "$m" ] || m=$(prop ro.xgimi.modelname); echo "$m"; }

# ---- job.conf: only known keys, only safe characters
[ -r "$JOB" ] || { : >"$STATUS"; echo "LUMENINST_FAIL - bad_job no job.conf" >>"$STATUS"; exit 1; }
while IFS='=' read -r k v; do
  case "$v" in *[!A-Za-z0-9._/-]*) continue ;; esac
  case "$k" in
    ACTION) case "$v" in probe|install|enable|remove|reboot|dump) ACTION=$v ;; esac ;;
    NONCE) case "$v" in [0-9a-f]*) NONCE=$v ;; esac ;;
    IMG|TAIL|IMG_SIZE|TAIL_SIZE|TOTAL|USERDATA|IMG_SHA|TAIL_SHA|NEED_KIB|MODEL|SETTINGS_PKG) eval "J_$k=\$v" ;;
  esac
done <"$JOB"
: >"$STATUS" 2>/dev/null
chmod 666 "$STATUS" 2>/dev/null
[ -n "$NONCE" ] || NONCE=-
out "LUMENINST_START $NONCE"
[ -n "$ACTION" ] || fail bad_job "no action"
[ "$(id -u 2>/dev/null)" = 0 ] || fail not_root "uid $(id -u 2>/dev/null)"

gsi_installed() {
  [ -e "$METADATA/gsi/dsu/install_status" ] && return 0
  gsi_tool status 2>/dev/null | grep -qx 'installed' && return 0
  return 1
}
ota_pending() {
  [ -e "$METADATA/ota/snapshot-boot" ] && return 0
  ls "$METADATA/ota/snapshots" 2>/dev/null | grep -q . && return 0
  return 1
}
# What a stock update left, for the app's early word (core/StockUpdate judges it like the trial's Snapshots.java):
# the state file as hex, the files libsnapshot's RemoveAllUpdateState deletes besides snapshot-boot, every *-cow device
# first stage mapped from the running table (name:KiB) and libsnapshot's snapshot devices. Read only.
ota_state_hex() { [ -f "$METADATA/ota/state" ] && od -An -tx1 -v "$METADATA/ota/state" 2>/dev/null | tr -d ' \n'; }
ota_markers() {
  for f in rollback-indicator allow-forward-merge old-partition-metadata snapshot-boot-without-slot-switch; do
    [ -e "$METADATA/ota/$f" ] && printf '%s,' "$f"
  done
}
cow_parts() {
  for d in "$MAPPER"/*-cow; do
    [ -e "$d" ] || continue
    k=$(readlink -f "$d" 2>/dev/null); k=${k##*/}
    s=$(cat "$SYSBLOCK/$k/size" 2>/dev/null)
    case "$s" in ''|*[!0-9]*) s=0 ;; esac
    printf '%s:%s,' "${d##*/}" "$((s / 2))"
  done
}
snap_devs() {
  for d in "$MAPPER"/*; do
    case "${d##*/}" in *-base|*-src|*-cow-img|*-user-cow|*-user-cow-init) printf '%s,' "${d##*/}" ;; esac
  done
}
log_cmd() {  # run a command, every output line as LOG, keep its exit code in RC
  t=$HERE/cmd.out
  "$@" >"$t" 2>&1
  RC=$?
  while IFS= read -r l; do [ -n "$l" ] && say "$1: $l"; done <"$t"
  rm -f "$t"
}
# a write job only for the model the app checked: MODEL must be a model code and the projector must report it
write_allowed() {
  m=$(model)
  case "$J_MODEL" in [A-Z][0-9][0-9][0-9][0-9]) ;; *) return 1 ;; esac
  [ -n "$m" ] && [ "$m" = "$J_MODEL" ]
}

case "$ACTION" in
probe)
  kv model "$(model)"
  # the serial stays on the unit: the Sparks card sends only its hash (apps/core/lumen-contrib)
  kv serial "$(prop ro.serialno)"
  kv board "$(prop ro.boot.hardware)"
  kv stock "$(prop ro.vendor.build.version.incremental)"
  kv product "$(prop ro.vendor.xgimi.product.name)"
  # the stock config folder of this model code (one XGIMI build serves several codes; a firmware without the folder
  # of this code is another model's): only a model code (a capital letter and four digits) is looked up
  case "$(model)" in
    [A-Z][0-9][0-9][0-9][0-9]) ls "$XGIMICONFIG/$(model)" 2>/dev/null | grep -qx 'system\.ini' && kv model_config 1 || kv model_config 0 ;;
    *) kv model_config 0 ;;
  esac
  kv vbstate "$(prop ro.boot.verifiedbootstate)"
  kv slot "$(prop ro.boot.slot_suffix)"
  [ "$(prop ro.virtual_ab.enabled)" = true ] && kv virtual_ab 1 || kv virtual_ab 0
  have gsi_tool && kv gsi_tool 1 || kv gsi_tool 0
  [ -d "$METADATA/gsi" ] && kv metadata_gsi 1 || kv metadata_gsi 0
  [ "$(prop ro.gsid.image_running)" = 1 ] && kv trial_running 1 || kv trial_running 0
  if have gsi_tool; then
    log_cmd gsi_tool status
  fi
  gsi_installed && kv trial_installed 1 || kv trial_installed 0
  [ -e "$METADATA/gsi/dsu/one_shot_boot" ] && kv trial_one_shot 1 || kv trial_one_shot 0
  ota_pending && kv ota_pending 1 || kv ota_pending 0
  kv ota_state "$(ota_state_hex)"
  kv ota_markers "$(ota_markers)"
  kv cow_parts "$(cow_parts)"
  kv snap_devs "$(snap_devs)"
  kv data_free_kib "$(free_kib "$DATA")"
  kv super_kib "$(blk_kib super)"
  done_
  ;;

install)
  write_allowed || fail not_allowed "model $(model), job ${J_MODEL:-none}"
  for k in IMG TAIL IMG_SIZE TAIL_SIZE TOTAL USERDATA IMG_SHA TAIL_SHA NEED_KIB MODEL; do
    eval "v=\$J_$k"; [ -n "$v" ] || fail bad_job "missing $k"
  done
  [ "$(prop ro.boot.verifiedbootstate)" = orange ] || fail not_orange "$(prop ro.boot.verifiedbootstate)"
  have gsi_tool || fail no_gsi_tool
  [ -d "$METADATA/gsi" ] || fail no_metadata_gsi
  [ "$(prop ro.gsid.image_running)" = 1 ] && fail trial_running
  gsi_installed && fail trial_installed "remove the installed trial first"
  ota_pending && fail ota_pending
  say "image $J_IMG"
  [ -r "$J_IMG" ] && [ -r "$J_TAIL" ] || fail files "image or tail missing"
  [ "$(fsize "$J_IMG")" = "$J_IMG_SIZE" ] || fail files "image size $(fsize "$J_IMG") != $J_IMG_SIZE"
  [ "$(fsize "$J_TAIL")" = "$J_TAIL_SIZE" ] || fail files "tail size $(fsize "$J_TAIL") != $J_TAIL_SIZE"
  f=$(free_kib "$DATA")
  [ -n "$f" ] && [ "$f" -ge "$J_NEED_KIB" ] || fail no_space "free ${f:-?} KiB, need $J_NEED_KIB KiB"
  say "checking the tail and the image (sha256)"
  [ "$(sha "$J_TAIL")" = "$J_TAIL_SHA" ] || fail sha "tail"
  [ "$(sha "$J_IMG")" = "$J_IMG_SHA" ] || fail sha "image"
  say "gsi_tool install --gsi-size $J_TOTAL --userdata-size $J_USERDATA --no-reboot"
  t=$HERE/cmd.out
  cat "$J_IMG" "$J_TAIL" | gsi_tool install --gsi-size "$J_TOTAL" --userdata-size "$J_USERDATA" --no-reboot >"$t" 2>&1
  RC=$?
  while IFS= read -r l; do [ -n "$l" ] && say "gsi_tool: $l"; done <"$t"
  rm -f "$t"
  if [ "$RC" != 0 ]; then
    log_cmd gsi_tool wipe
    fail gsi_install "rc $RC"
  fi
  log_cmd gsi_tool enable --single-boot
  if [ "$RC" != 0 ]; then
    log_cmd gsi_tool wipe
    fail gsi_enable "rc $RC"
  fi
  if [ ! -e "$METADATA/gsi/dsu/one_shot_boot" ]; then
    log_cmd gsi_tool wipe
    fail one_shot_missing "no $METADATA/gsi/dsu/one_shot_boot: not armed, removed"
  fi
  log_cmd gsi_tool status
  kv armed 1
  done_
  ;;

enable)
  write_allowed || fail not_allowed "model $(model), job ${J_MODEL:-none}"
  [ "$(prop ro.gsid.image_running)" = 1 ] && fail trial_running
  gsi_installed || fail not_installed
  log_cmd gsi_tool enable --single-boot
  [ "$RC" = 0 ] || fail gsi_enable "rc $RC"
  [ -e "$METADATA/gsi/dsu/one_shot_boot" ] || fail one_shot_missing
  kv armed 1
  done_
  ;;

remove)
  [ "$(prop ro.gsid.image_running)" = 1 ] && fail trial_running "restart to the XGIMI system first"
  log_cmd gsi_tool wipe
  [ "$RC" = 0 ] || fail wipe "rc $RC"
  gsi_installed && fail wipe "still installed"
  done_
  ;;

reboot)
  say "restarting"
  sync
  out "LUMENINST_DONE $NONCE"
  sleep 1
  reboot
  exit 0
  ;;
dump)
  D=$HERE/dump
  rm -rf "$D"; mkdir -p "$D" && chmod 777 "$D" || fail dump_dir "$D"
  T=; have timeout && T="timeout 120"
  case "$J_SETTINGS_PKG" in *[!A-Za-z0-9._]*|.*|*..*) J_SETTINGS_PKG= ;; esac
  # what never leaves: Wi-Fi, accounts, keys, DRM, HDCP, calibration (a name with one of these is never read)
  NEVER='wifi|wpa|account|keystore|keybox|widevine|playready|drm|hdcp|attest|cert|secure|private|password|token|cali|persist'
  # the services we read with dumpsys when we study a unit; never wifi, account, keystore, notification, location,
  # clipboard, user, backup, credential, device policy, media.drm and other DRM services
  DUMPSYS='activity_activities activity_services activity_processes alarm audio battery bluetooth_manager cpuinfo
deviceidle display dropbox gfxinfo gpu hardware_properties hdmi_control input input_method jobscheduler lights
media.audio_flinger media.audio_policy media.camera media.extractor media.player media.resource_manager
media_router media_session meminfo overlay package power role sensorservice SurfaceFlinger thermalservice tv_input
uimode usb webviewupdate window diskstats devicestoragemonitor color_display media.metrics statusbar'
  # one line per output file: name|command (the command runs under sh -c, its output is capped by cap_of)
  dump_table() {
    # printf, not a here-document: a shell writes a here-document to a temp file first, and where the root route may not
    # (no /tmp on Android, a TMPDIR it cannot write) the table came out empty: commands.txt of 0 bytes and no root
    # file in every dump received up to Installer 1.0.9
    printf '%s\n' \
      'getprop|getprop' \
      'partitions|cat /proc/partitions' \
      'by_name|@by_name' \
      'lpdump|lpdump' \
      'slot_hashes|@slot_hashes' \
      'mounts|cat /proc/mounts; echo; df -k; echo; cat /proc/swaps' \
      'cmdline|cat /proc/cmdline; echo; cat /proc/bootconfig' \
      'kernel|cat /proc/version; uname -a; echo; cat /proc/modules; echo; getenforce; echo; cat /proc/filesystems' \
      'cpu|cat /proc/cpuinfo; echo; cat /proc/meminfo; echo; cat /proc/loadavg /proc/uptime; for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "$c $(cat $c/cpufreq/scaling_cur_freq $c/cpufreq/scaling_max_freq $c/cpufreq/scaling_governor 2>/dev/null | tr '\''\n'\'' '\'' '\'')"; done' \
      'proc|for f in interrupts devices iomem misc buddyinfo vmstat zoneinfo asound/cards asound/pcm asound/devices bus/input/devices; do echo "== /proc/$f"; cat /proc/$f; done' \
      'sys|@sys' \
      'dmesg|dmesg' \
      'logcat|logcat -d -b all -v threadtime' \
      'packages|pm list packages -f -U -i --show-versioncode; echo; echo disabled:; pm list packages -d; echo; pm list features; echo; pm list libraries; echo; pm list instrumentation' \
      'permissions|pm list permissions -g -f' \
      'services|service list; echo; dumpsys -l; echo; lshal; echo; lshal -itrpc --neat' \
      'processes|ps -A -o USER,PID,PPID,VSZ,RSS,WCHAN,NAME; echo; ps -A -T -o PID,TID,NAME' \
      'xgimi_packages|for p in $(pm list packages | sed -n '\''s/^package:\(com\.xgimi\..*\)$/\1/p'\''); do echo "== $p"; dumpsys package "$p" | head -c 262144; done' \
      'settings|for n in global system secure; do echo "== $n"; settings list $n; done' \
      'settings_app|@settings_app' \
      'build_props|for f in /system/build.prop /vendor/build.prop /odm/build.prop /odm/etc/build.prop /vendor/odm/etc/build.prop /product/etc/build.prop /system_ext/etc/build.prop /vendor_dlkm/etc/build.prop /vendor/etc/xgimimodelname/*/build.prop; do [ -f "$f" ] && { echo "== $f"; cat "$f"; }; done' \
      'init|ls -lR /system/etc/init /vendor/etc/init /odm/etc/init /product/etc/init /system_ext/etc/init; for f in /system/etc/init/hw/*.rc /system/etc/init/*.rc /vendor/etc/init/*.rc /vendor/etc/init/hw/*.rc /odm/etc/init/*.rc /product/etc/init/*.rc /system_ext/etc/init/*.rc; do [ -f "$f" ] && { echo "== $f"; cat "$f"; }; done' \
      'vintf|for f in /vendor/etc/vintf/*.xml /vendor/etc/vintf/manifest/*.xml /odm/etc/vintf/*.xml /odm/etc/vintf/manifest/*.xml /system/etc/vintf/*.xml /system/etc/vintf/manifest/*.xml; do [ -f "$f" ] && { echo "== $f"; cat "$f"; }; done' \
      'vendor_configs|@configs /vendor/etc /odm/etc /system/etc/permissions /system/etc/sysconfig /product/etc/permissions /product/etc/sysconfig /system_ext/etc/permissions' \
      'tvconfig|@tvconfig' \
      'xgimiconfig|@xgimiconfig' \
      'xgimi_files|awk '\''$2 ~ /xgimi|tvconfig|tvservice/ {print $2}'\'' /proc/mounts | sort -u | while read -r m; do echo "== $m"; ls -lR "$m"; done' \
      'net|ip -br link; echo; ip link' \
      'audio_mixer|tinymix -D 0' \
      'anr|ls -l /data/anr /data/tombstones; for f in $(ls -t /data/anr 2>/dev/null | head -4); do echo "== /data/anr/$f"; head -c 2097152 "/data/anr/$f"; done' \
      'updates|ls -lR /metadata/ota /data/ota_package /data/misc/update_engine_log; for f in $(ls -t /data/misc/update_engine_log 2>/dev/null | head -2); do echo "== $f"; tail -c 1048576 "/data/misc/update_engine_log/$f"; done; echo; getprop | grep -E '\''ota|update|slot'\'''
    for v in $DUMPSYS; do
      case "$v" in
        activity_*) echo "dumpsys_$v|dumpsys -t 60 activity ${v#activity_}" ;;
        dropbox) echo "dumpsys_dropbox|dumpsys -t 60 dropbox --print" ;;
        *) echo "dumpsys_$(echo "$v" | tr '.A-Z' '_a-z')|dumpsys -t 60 $v" ;;
      esac
    done
  }
  cap_of() {   # bytes one output file may keep (its end)
    case "$1" in
      # the newest part of the log is what helps, configs and the package list a little more: a few MB in the zip
      dumpsys_package|vendor_configs|tvconfig|xgimiconfig) echo 16777216 ;;
      *) echo 8388608 ;;
    esac
  }
  # every by-name entry: name, target, size in KiB (no content)
  dump_by_name() {
    for e in "$BYNAME"/*; do
      [ -e "$e" ] || continue
      n=${e##*/}
      echo "$n $(readlink -f "$e" 2>/dev/null) $(blk_kib "$n" 2>/dev/null || echo ?)K"
    done
  }
  # the A/B firmware partitions only: sha256, and the kernel banner of boot
  DUMP_HASH="boot dtbo vbmeta vbmeta_system mboot bootdata tvconfig tvservice optee"
  dump_slot_hashes() {
    for p in $DUMP_HASH; do
      for s in _a _b; do
        e=$BYNAME/$p$s
        [ -e "$e" ] || continue
        [ -e "$BYNAME/${p}_a" ] && [ -e "$BYNAME/${p}_b" ] || continue
        echo "$p$s $(sha "$e")"
        [ "$p" = boot ] && dd if="$e" bs=1048576 count=64 2>/dev/null | strings 2>/dev/null | grep -m2 -E 'Linux version|vermagic='
      done
    done
  }
  # text files under the folders given (config, permission and feature files), each at most 512 KiB, names on NEVER
  # left out and listed
  text_files() {
    for top in "$@"; do
      [ -d "$top" ] || continue
      find "$top" -type f 2>/dev/null | sort | while read -r f; do
        case "$f" in *.xml|*.conf|*.cfg|*.json|*.ini|*.txt|*.prop|*.rc|*.csv|*.tbl|*.idc|*.kl|*.kcm) ;; *) continue ;; esac
        if echo "$f" | grep -qiE "$NEVER"; then echo "== $f (left out)"; continue; fi
        s=$(fsize "$f")
        case "$s" in ''|*[!0-9]*) continue ;; esac
        if [ "${#s}" -gt 6 ] || [ "$s" -gt 524288 ]; then echo "== $f ($s bytes, left out)"; continue; fi
        echo "== $f"
        cat "$f"
        echo
      done
    done
  }
  dump_sys() {
    echo "== /sys/class"; ls /sys/class
    echo "== drivers"; for b in /sys/bus/*/drivers; do echo "$b: $(ls "$b" | tr '\n' ' ')"; done
    echo "== thermal"; for z in /sys/class/thermal/thermal_zone*; do echo "$z $(cat $z/type 2>/dev/null) $(cat $z/temp 2>/dev/null)"; done
    echo "== usb"; for u in /sys/bus/usb/devices/*; do [ -f "$u/idVendor" ] && echo "${u##*/} $(cat $u/idVendor):$(cat $u/idProduct) $(cat $u/manufacturer 2>/dev/null) $(cat $u/product 2>/dev/null)"; done
    echo "== input"; for i in /sys/class/input/input*; do echo "${i##*/} $(cat $i/name 2>/dev/null)"; done
    echo "== leds"; ls /sys/class/leds
    echo "== drm"; for c in /sys/class/drm/*; do [ -f "$c/status" ] && echo "${c##*/} $(cat $c/status) $(head -3 $c/modes 2>/dev/null | tr '\n' ' ')"; done
    echo "== power_supply"; for c in /sys/class/power_supply/*; do echo "${c##*/} $(cat $c/type 2>/dev/null) $(cat $c/status 2>/dev/null) $(cat $c/capacity 2>/dev/null)"; done
    echo "== mmc"; for c in /sys/block/mmcblk*/device; do echo "$c $(cat $c/name $c/type $c/manfid $c/oemid $c/fwrev 2>/dev/null | tr '\n' ' ')"; done
    echo "== block"; for b in /sys/block/*; do echo "${b##*/} $(cat $b/size 2>/dev/null)"; done
    echo "== devicetree"; cat /sys/firmware/devicetree/base/model 2>/dev/null; echo; tr '\0' ' ' </sys/firmware/devicetree/base/compatible 2>/dev/null; echo
    echo "== wakeup"; cat /sys/power/state /sys/kernel/wakeup_reasons/last_resume_reason /sys/mtk_pm/wakeup_source 2>/dev/null
    echo "== modules with parameters"; ls /sys/module/*/parameters 2>/dev/null | head -c 1048576
  }
  # the stock settings app: where its files are, its package dump, its own settings files (names, sizes, and the
  # shared_prefs texts; the app removes values of keys that name a network, an account, an id or a place)
  dump_settings_app() {
    p=$J_SETTINGS_PKG
    [ -n "$p" ] || { echo "no stock settings app named"; return 0; }
    echo "== package $p"; pm path "$p"; dumpsys package "$p" | head -c 4194304
    for d in "$DATA/data/$p" "$DATA/user_de/0/$p"; do
      [ -d "$d" ] || continue
      echo "== files $d"; ls -lR "$d" 2>/dev/null | head -c 1048576
      for f in "$d"/shared_prefs/*.xml; do
        [ -f "$f" ] || continue
        if echo "${f##*/}" | grep -qiE "$NEVER"; then echo "== $f (left out)"; continue; fi
        echo "== $f"; head -c 1048576 "$f"; echo
      done
    done
  }
  # the MTK TV config: its mounted folders' text files
  dump_tvconfig() {
    awk '$2 ~ /tvconfig/ {print $2}' "$MOUNTS" | sort -u | while read -r m; do text_files "$m"; done
    [ -d /vendor/tvconfig ] && text_files /vendor/tvconfig
  }
  # the XGIMI model config: the top files and the folder of this model code, never another model's, never cali
  dump_xgimiconfig() {
    m=$(model)
    case "$m" in [A-Z][0-9][0-9][0-9][0-9]) ;; *) echo "no model code"; return 0 ;; esac
    ls -l "$XGIMICONFIG" 2>/dev/null
    for f in "$XGIMICONFIG"/*; do [ -f "$f" ] && text_files_one "$f"; done
    text_files "$XGIMICONFIG/$m"
  }
  text_files_one() { case "$1" in *.ini|*.xml|*.json|*.txt|*.cfg|*.conf) echo "$1" | grep -qiE "$NEVER" || { echo "== $1"; head -c 524288 "$1"; echo; } ;; esac; }
  : >"$D/commands.txt"
  dump_table | while IFS= read -r line; do
    name=${line%%|*}; cmd=${line#*|}
    case "$name" in *[!a-z0-9_]*|'') continue ;; esac
    t0=$(date +%s 2>/dev/null)
    case "$cmd" in
      @by_name) dump_by_name >"$D/$name.tmp" 2>&1; rc=$? ;;
      @slot_hashes) dump_slot_hashes >"$D/$name.tmp" 2>&1; rc=$? ;;
      @sys) dump_sys >"$D/$name.tmp" 2>&1; rc=$? ;;
      @settings_app) dump_settings_app >"$D/$name.tmp" 2>&1; rc=$? ;;
      @configs\ *) text_files ${cmd#@configs } >"$D/$name.tmp" 2>&1; rc=$? ;;
      @tvconfig) dump_tvconfig >"$D/$name.tmp" 2>&1; rc=$? ;;
      @xgimiconfig) dump_xgimiconfig >"$D/$name.tmp" 2>&1; rc=$? ;;
      *) $T sh -c "$cmd" >"$D/$name.tmp" 2>&1 </dev/null; rc=$? ;;
    esac
    tail -c "$(cap_of "$name")" "$D/$name.tmp" >"$D/$name.txt" 2>/dev/null
    rm -f "$D/$name.tmp"
    chmod 666 "$D/$name.txt" 2>/dev/null
    t1=$(date +%s 2>/dev/null)
    echo "$name rc=$rc bytes=$(fsize "$D/$name.txt") s=$((${t1:-0} - ${t0:-0})) | $cmd" >>"$D/commands.txt"
  done
  chmod 666 "$D/commands.txt" 2>/dev/null
  kv dump_dir "$D"
  kv dump_files "$(ls "$D" | wc -l | tr -d ' ')"
  done_
  ;;
esac
fail bad_job "unknown action"
