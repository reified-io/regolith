#!/bin/bash
# one sandbox's home disk. the server runs this in a short-lived helper container that sees only this
# home's backing volume at /storage and the loop devices; it never sees a mounted home, and nothing in a
# sandbox ever sees the backing image, so a command cannot resize or corrupt it directly.
#
#   homedisk.sh prepare SIZE_MB RESERVE_MB   create the image on first use, attach it, print the device
#   homedisk.sh release                      detach every loop device attached to the image
#   homedisk.sh size                         print the image size in bytes, or nothing without an image
#   homedisk.sh probe                        print the next free loop device; needs no volume
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
umask 077

if [[ "${1:-}" == probe ]]; then
  losetup --find
  exit 0
fi

exec 9>/storage/lock
flock -x 9
image=/storage/home.ext4

case "${1:-}" in
  prepare)
    size="${2:?missing size}"
    reserve="${3:?missing reserve}"
    [[ "$size" =~ ^[1-9][0-9]*$ && "$reserve" =~ ^[0-9]+$ ]] || exit 2
    if [[ ! -e "$image" ]]; then
      trap 'rm -f /storage/home.new' EXIT
      rm -f /storage/home.new
      read -r available block_size < <(stat -f -c '%a %S' /storage)
      (( available * block_size >= (size + reserve) * 1024 * 1024 )) || {
        echo "not enough host space for a ${size} MB home and the ${reserve} MB reserve" >&2
        exit 1
      }
      touch /storage/home.new
      # copy-on-write would let the preallocated blocks be shared and the size stop being real.
      if [[ "$(stat -f -c %T /storage)" == btrfs ]]; then
        chattr +C /storage/home.new
      fi
      # real blocks, reserved before formatting; discard would quietly make the image sparse again.
      fallocate -l "${size}M" /storage/home.new
      mkfs.ext4 -q -F -m 0 -E nodiscard,lazy_itable_init=0,lazy_journal_init=0,root_owner=1000:1000 /storage/home.new
      debugfs -w -R 'rmdir lost+found' /storage/home.new >&2
      chmod 600 /storage/home.new
      mv /storage/home.new "$image"
      trap - EXIT
    fi
    [[ -f "$image" && ! -L "$image" ]] || exit 1
    # an existing home is never resized or reformatted here.
    [[ "$(stat -c %s "$image")" == "$(( size * 1024 * 1024 ))" ]] || {
      echo "the existing home image is not ${size} MB; it is never resized automatically" >&2
      exit 1
    }
    # --nooverlap reuses the attachment an interrupted run left behind instead of adding a second one.
    losetup --find --show --nooverlap "$image"
    ;;

  size)
    if [[ -f "$image" && ! -L "$image" ]]; then stat -c %s "$image"; fi
    ;;

  release)
    if [[ -f "$image" && ! -L "$image" ]]; then
      while read -r device; do
        [[ "$device" =~ ^/dev/loop[0-9]+$ ]] || exit 1
        losetup --detach "$device"
      done < <(losetup --associated "$image" --noheadings --output NAME)
    fi
    ;;

  *)
    exit 2
    ;;
esac
