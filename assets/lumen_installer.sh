#!/system/bin/sh
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
#            With IMG (+ IMG_SIZE): whether root reads that file (an image on a USB stick)
#   install  `cat IMG TAIL | gsi_tool install --gsi-size TOTAL --userdata-size USERDATA --no-reboot`, then
#            `gsi_tool enable --single-boot`; writes only the trial's own files (under /data/gsi and /metadata/gsi).
#            Any failure: `gsi_tool wipe`, nothing stays armed. MODEL: the model code the app found in the signed
#            list of projectors; the job runs only when the projector reports that code itself.
#   enable   arm an installed trial for one more boot (MODEL as for install)
#   remove   gsi_tool wipe (refused while the trial runs)
#   reboot   sync + reboot (only when the user pressed Restart)
# It never reboots on its own and never writes a partition, misc, uenv or vbmeta.
# A file on a USB stick is passed as the app sees it (/storage/<volume id>/...); root reads it where vold mounted the
# volume (/mnt/media_rw/<volume id>/...), which needs no FUSE view and no app permission.
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
MEDIA_RW=${LI_MEDIA_RW:-/mnt/media_rw}
DATA_MEDIA=${LI_DATA_MEDIA:-/data/media}
XGIMICONFIG=${LI_XGIMICONFIG:-/mnt/vendor/xgimiconfig}
MAPPER=${LI_MAPPER:-/dev/block/mapper}

# The script knows no model: which models may take a trial is the signed list of projectors' (checked by the app).
# A write job carries the model code the app checked (MODEL) and runs only on a projector that reports that code.

NONCE=
ACTION=
for k in IMG TAIL IMG_SIZE TAIL_SIZE TOTAL USERDATA IMG_SHA TAIL_SHA NEED_KIB MODEL; do eval "J_$k="; done

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
# the path root reads for a path of the app: /storage/<id>/x -> /mnt/media_rw/<id>/x when that volume is mounted there
# (a USB stick), anything else unchanged
dev_path() {
  case "$1" in
    /storage/emulated/[0-9]*/*)
      # the built-in shared storage (/sdcard/LumenOS): root reads the files under /data/media/<user>, no FUSE view
      v=${1#/storage/emulated/}; u=${v%%/*}; rest=${v#*/}
      case "$u" in *[!0-9]*) echo "$1" ;; *) if [ -e "$DATA_MEDIA/$u/$rest" ]; then echo "$DATA_MEDIA/$u/$rest"; else echo "$1"; fi ;; esac ;;
    /storage/emulated/*|/storage/self/*) echo "$1" ;;
    /storage/*/*)
      v=${1#/storage/}; id=${v%%/*}; rest=${v#*/}
      if [ -n "$id" ] && [ -d "$MEDIA_RW/$id" ]; then echo "$MEDIA_RW/$id/$rest"; else echo "$1"; fi ;;
    *) echo "$1" ;;
  esac
}

# ---- job.conf: only known keys, only safe characters
[ -r "$JOB" ] || { : >"$STATUS"; echo "LUMENINST_FAIL - bad_job no job.conf" >>"$STATUS"; exit 1; }
while IFS='=' read -r k v; do
  case "$v" in *[!A-Za-z0-9._/-]*) continue ;; esac
  case "$k" in
    ACTION) case "$v" in probe|install|enable|remove|reboot) ACTION=$v ;; esac ;;
    NONCE) case "$v" in [0-9a-f]*) NONCE=$v ;; esac ;;
    IMG|TAIL|IMG_SIZE|TAIL_SIZE|TOTAL|USERDATA|IMG_SHA|TAIL_SHA|NEED_KIB|MODEL) eval "J_$k=\$v" ;;
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
  kv media_rw "$(ls "$MEDIA_RW" 2>/dev/null | tr '\n' ',')"
  if [ -n "$J_IMG" ]; then
    p=$(dev_path "$J_IMG")
    kv img_path "$p"
    if [ -r "$p" ] && [ "$(fsize "$p")" = "$J_IMG_SIZE" ]; then kv img_readable 1; else kv img_readable 0; fi
  fi
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
  J_IMG=$(dev_path "$J_IMG"); J_TAIL=$(dev_path "$J_TAIL")
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
esac
fail bad_job "unknown action"
