#!/bin/bash
# the host firewall under every sandbox. the server runs this in a short-lived helper container with
# NET_ADMIN in the host's network namespace; sandboxes hold no capabilities and never see these rules.
#
#   firewall.sh addresses                        the host's own ipv4 addresses, one per line
#   firewall.sh gateways                         the host's default ipv4 gateways, one per line
#   firewall.sh backend                          the iptables variant docker's own rules are in; read-only
#   firewall.sh apply PREFIX BRIDGE UID < RULES  replace the PREFIX chains with RULES, print the fingerprint
#   firewall.sh check PREFIX BRIDGE              print the fingerprint
#   firewall.sh remove PREFIX                    unhook and delete every PREFIX chain
#
# RULES is iptables-restore input built by the server: its filter table, and the nat table that sends
# proxied sandboxes to the egress proxy. this script only guards it and hooks it in. UID is the egress
# proxy's, which ipv6 refuses everything.
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

if [[ "$action" == backend ]]; then
  echo "$ip4"
  exit 0
fi

prefix="${2:?missing chain prefix}"
[[ "$prefix" =~ ^RGL_[0-9A-F]{8}$ ]] || fail "invalid chain prefix"

ip6="${ip4/iptables/ip6tables}"
ipv6=false
if "$ip6" -S FORWARD >/dev/null 2>&1; then ipv6=true; fi

# the rule at the top of a chain is line 2 of -S: line 1 is its policy or declaration. a hook in the
# nat table is named nat:CHAIN, one in the filter table by its chain alone.
position() {
  local tool="$1" table="$2" chain="$3" rule="$4" found name="$3"
  [[ "$table" == filter ]] || name="$table:$chain"
  found="$("$tool" -t "$table" -S "$chain" | grep -n -x -F -- "-A $chain $rule" | head -1 | cut -d: -f1 || true)"
  echo "position $tool $name $rule ${found:-missing}"
}

ensure_first() {
  local tool="$1" table="$2" chain="$3" rule="$4"
  if [[ "$("$tool" -t "$table" -S "$chain" | sed -n 2p)" != "-A $chain $rule" ]]; then
    # shellcheck disable=SC2086
    while "$tool" -t "$table" -D "$chain" $rule 2>/dev/null; do :; done
    # shellcheck disable=SC2086
    "$tool" -t "$table" -I "$chain" 1 $rule
  fi
}

# everything that must still be true, compared rule for rule by the server with what applying printed.
# counting rules is not reading them: one deleted reject, or a hook that is no longer first, leaves the
# shape intact and the policy gone.
fingerprint() {
  local bridge="$1"
  "$ip4" -S | grep -F -- "$prefix" || true
  "$ip4" -t nat -S | grep -F -- "$prefix" || true
  position "$ip4" filter FORWARD "-j DOCKER-USER"
  position "$ip4" filter DOCKER-USER "-j $prefix"
  position "$ip4" filter INPUT "-j ${prefix}_IN"
  position "$ip4" filter OUTPUT "-j ${prefix}_O"
  position "$ip4" nat PREROUTING "-j ${prefix}_R"
  if [[ "$ipv6" == true ]]; then
    "$ip6" -S | grep -F -- "$prefix" || true
    position "$ip6" filter FORWARD "-j ${prefix}_6"
    position "$ip6" filter INPUT "-j ${prefix}_6"
    position "$ip6" filter OUTPUT "-j ${prefix}_6O"
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
    uid="${4:-}"
    [[ "$uid" =~ ^[1-9][0-9]{0,9}$ ]] || fail "invalid egress uid"
    ip link show "$bridge" >/dev/null || fail "bridge $bridge does not exist"
    rules="$(cat)"
    [[ "$rules" == "*filter"* && "$rules" == *COMMIT ]] || fail "rules are not a filter table"
    # the input may declare and fill only this server's chains, in the filter and nat tables; the hooks
    # below are the only other edits.
    while read -r line; do
      case "$line" in
        "" | "*filter" | "*nat" | COMMIT) continue ;;
        :*) name="${line%% *}" && name="${name#:}" ;;
        "-F "* | "-A "*) name="$(awk '{ print $2 }' <<<"$line")" ;;
        *) fail "unexpected rules line: $line" ;;
      esac
      [[ "$name" == "$prefix" || "$name" == "${prefix}_"* ]] || fail "rules touch a foreign chain: $name"
    done <<<"$rules"

    # docker creates DOCKER-USER lazily; never assume it exists or is hooked.
    "$ip4" -N DOCKER-USER 2>/dev/null || true
    "${ip4}-restore" --noflush <<<"$rules"
    ensure_first "$ip4" filter FORWARD "-j DOCKER-USER"
    ensure_first "$ip4" filter DOCKER-USER "-j $prefix"
    ensure_first "$ip4" filter INPUT "-j ${prefix}_IN"
    ensure_first "$ip4" filter OUTPUT "-j ${prefix}_O"
    ensure_first "$ip4" nat PREROUTING "-j ${prefix}_R"

    # chains of sandboxes whose policy was released: the dispatch rules pointing at them are gone.
    declared="$(grep -o '^:[^ ]*' <<<"$rules" | cut -c2- || true)"
    for table in filter nat; do
      for chain in $("$ip4" -t "$table" -S | awk -v s="-N ${prefix}_S" -v t="-N ${prefix}_T" 'index($0, s) == 1 || index($0, t) == 1 { print $2 }'); do
        if ! grep -q -x -F -- "$chain" <<<"$declared"; then
          "$ip4" -t "$table" -F "$chain"
          "$ip4" -t "$table" -X "$chain"
        fi
      done
    done

    # nothing crosses the bridge over ipv6 in either direction, and the egress proxy sends nothing over it.
    # sandboxes also disable ipv6 inside, and a kernel without ipv6 simply has nothing here to filter.
    if [[ "$ipv6" == true ]]; then
      "$ip6" -N "${prefix}_6" 2>/dev/null || true
      "$ip6" -F "${prefix}_6"
      "$ip6" -A "${prefix}_6" -i "$bridge" -j DROP
      "$ip6" -A "${prefix}_6" -o "$bridge" -j DROP
      "$ip6" -N "${prefix}_6O" 2>/dev/null || true
      "$ip6" -F "${prefix}_6O"
      "$ip6" -A "${prefix}_6O" -m owner --uid-owner "$uid" -j DROP
      ensure_first "$ip6" filter FORWARD "-j ${prefix}_6"
      ensure_first "$ip6" filter INPUT "-j ${prefix}_6"
      ensure_first "$ip6" filter OUTPUT "-j ${prefix}_6O"
    fi

    fingerprint "$bridge"
    ;;

  remove)
    while "$ip4" -D DOCKER-USER -j "$prefix" 2>/dev/null; do :; done
    while "$ip4" -D INPUT -j "${prefix}_IN" 2>/dev/null; do :; done
    while "$ip4" -D OUTPUT -j "${prefix}_O" 2>/dev/null; do :; done
    while "$ip4" -t nat -D PREROUTING -j "${prefix}_R" 2>/dev/null; do :; done
    for table in filter nat; do
      for chain in $("$ip4" -t "$table" -S | awk -v p="$prefix" '$1 == "-N" && index($2, p) == 1 { print $2 }'); do
        "$ip4" -t "$table" -F "$chain"
      done
      for chain in $("$ip4" -t "$table" -S | awk -v p="$prefix" '$1 == "-N" && index($2, p) == 1 { print $2 }'); do
        "$ip4" -t "$table" -X "$chain"
      done
    done
    if [[ "$ipv6" == true ]]; then
      while "$ip6" -D FORWARD -j "${prefix}_6" 2>/dev/null; do :; done
      while "$ip6" -D INPUT -j "${prefix}_6" 2>/dev/null; do :; done
      while "$ip6" -D OUTPUT -j "${prefix}_6O" 2>/dev/null; do :; done
      for chain in "${prefix}_6" "${prefix}_6O"; do
        "$ip6" -F "$chain" 2>/dev/null || true
        "$ip6" -X "$chain" 2>/dev/null || true
      done
    fi
    ;;

  *)
    fail "unknown action: $action"
    ;;
esac
