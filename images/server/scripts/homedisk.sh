#!/bin/bash
# one sandbox's home disk. the server runs this in a short-lived helper container that sees only this
# home's backing volume at /storage and the loop devices; it never sees a mounted home, and nothing in a
# sandbox ever sees the backing image, so a command cannot resize or corrupt it directly.
#
#   homedisk.sh prepare SIZE_MB RESERVE_MB   create the image on first use or grow it to SIZE_MB, attach it,
#                                            print the device
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
    target=$(( size * 1024 * 1024 ))
    current=$(stat -c %s "$image")
    # a home only ever grows, and only to the size its sandbox was given; it is never shrunk or reformatted.
    (( current <= target )) || {
      echo "the existing home image is larger than ${size} MB; a home is never shrunk" >&2
      exit 1
    }
    if (( current < target )); then
      read -r available block_size < <(stat -f -c '%a %S' /storage)
      (( available * block_size >= target - current + reserve * 1024 * 1024 )) || {
        echo "not enough host space to grow the home to ${size} MB and keep the ${reserve} MB reserve" >&2
        exit 1
      }
      # the added range is allocated like the rest, so the home stays a real size rather than a promise.
      fallocate -l "${size}M" "$image"
    fi
    # the filesystem follows the image. this also finishes a grow that stopped between the two steps,
    # which left an image of the right size around a filesystem of the old one.
    read -r blocks fs_block_size < <(dumpe2fs -h "$image" 2>/dev/null | awk -F: '
      /^Block count:/ { gsub(/ /, "", $2); count = $2 }
      /^Block size:/ { gsub(/ /, "", $2); bsize = $2 }
      END { print count, bsize }')
    [[ "$blocks" =~ ^[0-9]+$ && "$fs_block_size" =~ ^[0-9]+$ ]] || exit 1
    if (( blocks * fs_block_size < target )); then
      # resize2fs refuses an image that was not checked since it was last mounted; 1 means it fixed something.
      checked=0
      e2fsck -f -p "$image" >&2 || checked=$?
      (( checked <= 1 )) || exit 1
      resize2fs "$image" >&2
    fi
    # --nooverlap reuses the attachment an interrupted run left behind instead of adding a second one;
    # that device still has the size it was attached with, so it is told the image may have grown.
    device=$(losetup --find --show --nooverlap "$image")
    losetup --set-capacity "$device"
    echo "$device"
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
