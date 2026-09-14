#!/bin/bash
# the host firewall under every sandbox. the server runs this in a short-lived helper container with
# NET_ADMIN in the host's network namespace; sandboxes hold no capabilities and never see these rules.
#
#   firewall.sh addresses                        the host's own ipv4 addresses, one per line
#   firewall.sh gateways                         the host's default ipv4 gateways, one per line
#   firewall.sh apply PREFIX BRIDGE < RULES      replace the PREFIX chains with RULES, print the fingerprint
#   firewall.sh check PREFIX BRIDGE              print the fingerprint
#   firewall.sh remove PREFIX                    unhook and delete every PREFIX chain
#
# RULES is iptables-restore input built by the server. this script only guards it and hooks it in.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

fail() {
  echo "$*" >&2
  exit 1
}

action="${1:?missing action}"

if [[ "$action" == addresses ]]; then
  ip -4 -o addr show | awk '{ split($4, a, "/"); print a[1] }'
  exit 0
fi

if [[ "$action" == gateways ]]; then
  ip -4 route show default | awk '$1 == "default" && $2 == "via" { print $3 }' | sort -u
  exit 0
fi

prefix="${2:?missing chain prefix}"
[[ "$prefix" =~ ^RGL_[0-9A-F]{8}$ ]] || fail "invalid chain prefix"

# the daemon programs one netfilter backend, and rules written to the other land in tables nothing
# traverses. use the backend whose FORWARD chain shows docker's own rules; never guess.
# (no pipe into grep -q: it would close the pipe early and pipefail would read that as a failure.)
ip4=""
for candidate in iptables-nft iptables-legacy; do
  forward="$("$candidate" -S FORWARD 2>/dev/null || true)"
  if [[ "$forward" == *"-j DOCKER"* ]]; then
    ip4="$candidate"
    break
  fi
done
[[ -n "$ip4" ]] || fail "docker's iptables rules are not visible from here: unknown netfilter backend"
ip6="${ip4/iptables/ip6tables}"
ipv6=false
if "$ip6" -S FORWARD >/dev/null 2>&1; then ipv6=true; fi

# the rule at the top of a chain is line 2 of -S: line 1 is its policy or declaration.
position() {
  local tool="$1" chain="$2" rule="$3" found
  found="$("$tool" -S "$chain" | grep -n -x -F -- "-A $chain $rule" | head -1 | cut -d: -f1 || true)"
  echo "position $tool $chain $rule ${found:-missing}"
}

ensure_first() {
  local tool="$1" chain="$2" rule="$3"
  if [[ "$("$tool" -S "$chain" | sed -n 2p)" != "-A $chain $rule" ]]; then
    # shellcheck disable=SC2086
    while "$tool" -D "$chain" $rule 2>/dev/null; do :; done
    # shellcheck disable=SC2086
    "$tool" -I "$chain" 1 $rule
  fi
}

# everything that must still be true, compared rule for rule by the server with what applying printed.
# counting rules is not reading them: one deleted reject, or a hook that is no longer first, leaves the
# shape intact and the policy gone.
fingerprint() {
  local bridge="$1"
  "$ip4" -S | grep -F -- "$prefix" || true
  position "$ip4" FORWARD "-j DOCKER-USER"
  position "$ip4" DOCKER-USER "-j $prefix"
  position "$ip4" INPUT "-j ${prefix}_IN"
  if [[ "$ipv6" == true ]]; then
    "$ip6" -S | grep -F -- "$prefix" || true
    position "$ip6" FORWARD "-j ${prefix}_6"
    position "$ip6" INPUT "-j ${prefix}_6"
  fi
  # presence only: a bridge's operstate flips with the first attached container and is not drift.
  if [[ -e "/sys/class/net/$bridge" ]]; then echo "bridge $bridge present"; else echo "bridge $bridge missing"; fi
}

bridge_arg() {
  local bridge="${1:?missing bridge}"
  [[ "$bridge" =~ ^[a-zA-Z0-9_.-]{1,15}$ ]] || fail "invalid bridge name"
  echo "$bridge"
}

case "$action" in
  check)
    fingerprint "$(bridge_arg "${3:-}")"
    ;;

  apply)
    bridge="$(bridge_arg "${3:-}")"
    ip link show "$bridge" >/dev/null || fail "bridge $bridge does not exist"
    rules="$(cat)"
    [[ "$rules" == "*filter"* && "$rules" == *COMMIT ]] || fail "rules are not a filter table"
    # the input may declare and fill only this server's chains; the hooks below are the only other edits.
    while read -r line; do
      case "$line" in
        "" | "*filter" | COMMIT) continue ;;
        :*) name="${line%% *}" && name="${name#:}" ;;
        "-F "* | "-A "*) name="$(awk '{ print $2 }' <<<"$line")" ;;
        *) fail "unexpected rules line: $line" ;;
      esac
      [[ "$name" == "$prefix" || "$name" == "${prefix}_"* ]] || fail "rules touch a foreign chain: $name"
    done <<<"$rules"

    # docker creates DOCKER-USER lazily; never assume it exists or is hooked.
    "$ip4" -N DOCKER-USER 2>/dev/null || true
    "${ip4}-restore" --noflush <<<"$rules"
    ensure_first "$ip4" FORWARD "-j DOCKER-USER"
    ensure_first "$ip4" DOCKER-USER "-j $prefix"
    ensure_first "$ip4" INPUT "-j ${prefix}_IN"

    # chains of sandboxes whose policy was released: the dispatch rules pointing at them are gone.
    declared="$(grep -o '^:[^ ]*' <<<"$rules" | cut -c2- || true)"
    for chain in $("$ip4" -S | awk -v p="-N ${prefix}_S" 'index($0, p) == 1 { print $2 }'); do
      if ! grep -q -x -F -- "$chain" <<<"$declared"; then
        "$ip4" -F "$chain"
        "$ip4" -X "$chain"
      fi
    done

    # nothing crosses the bridge over ipv6 in either direction. sandboxes also disable ipv6 inside, and a
    # kernel without ipv6 simply has nothing here to filter.
    if [[ "$ipv6" == true ]]; then
      "$ip6" -N "${prefix}_6" 2>/dev/null || true
      "$ip6" -F "${prefix}_6"
      "$ip6" -A "${prefix}_6" -i "$bridge" -j DROP
      "$ip6" -A "${prefix}_6" -o "$bridge" -j DROP
      ensure_first "$ip6" FORWARD "-j ${prefix}_6"
      ensure_first "$ip6" INPUT "-j ${prefix}_6"
    fi

    fingerprint "$bridge"
    ;;

  remove)
    while "$ip4" -D DOCKER-USER -j "$prefix" 2>/dev/null; do :; done
    while "$ip4" -D INPUT -j "${prefix}_IN" 2>/dev/null; do :; done
    for chain in $("$ip4" -S | awk -v p="$prefix" '$1 == "-N" && index($2, p) == 1 { print $2 }'); do
      "$ip4" -F "$chain"
    done
    for chain in $("$ip4" -S | awk -v p="$prefix" '$1 == "-N" && index($2, p) == 1 { print $2 }'); do
      "$ip4" -X "$chain"
    done
    if [[ "$ipv6" == true ]]; then
      while "$ip6" -D FORWARD -j "${prefix}_6" 2>/dev/null; do :; done
      while "$ip6" -D INPUT -j "${prefix}_6" 2>/dev/null; do :; done
      "$ip6" -F "${prefix}_6" 2>/dev/null || true
      "$ip6" -X "${prefix}_6" 2>/dev/null || true
    fi
    ;;

  *)
    fail "unknown action: $action"
    ;;
esac
