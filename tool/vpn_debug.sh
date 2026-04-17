#!/usr/bin/env bash

set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
LOG_DIR="${VPN_DEBUG_LOG_DIR:-.vpn-debug}"
LOG_FILE="${VPN_DEBUG_LOG_FILE:-}"
LOG_MODE="${VPN_DEBUG_LOG_MODE:-append}"
COMMAND=""
LIVE_TAIL_PID=""
LIVE_PIDS=()

print_usage() {
  cat <<EOF
Unified VPN debugging toolkit for Libreswan + L2TP environments.

Usage:
  $SCRIPT_NAME [--log-dir DIR] [--log-file FILE] <command> [options]

Global options:
  --log-dir DIR         Directory for generated logs (default: .vpn-debug)
  --log-file FILE       Explicit log file path
  -h, --help            Show this help

Commands:
  log [opts]            Live aggregated VPN logging (tail -f style, clean file)
  check                 Process/listener/IPsec status sanity checks
  xfrm                  XFRM snapshot (/proc/net/xfrm_stat + xfrm state/policy)
  diag [opts]           Full IPsec/L2TP diagnostics
  capture [opts]        tcpdump capture for IKE + NAT-T traffic
  watch [opts]          Repeatedly run check + xfrm snapshots
  watch-all [opts]      Repeatedly run full diagnostics with clean log each run
  pluto-logs [opts]     Enable Libreswan(pluto) logs in Docker server

Common options:
  --iface IFACE         Network interface (e.g. wlo1)
  --client-ip IP        Client IP hint for correlation output
  --adb-serial SERIAL   Optional adb device serial for logcat
  --adb-filter FILTER   ADB logcat filter (default: tunnel_engine:V)
  --interval SECONDS    Loop interval for watch (default: 5)
  --container NAME      Docker container (default: ipsec-vpn-server)
  --plutodebug          Add plutodebug=all (noisy; temporary use only)

Examples:
  $SCRIPT_NAME log --iface wlo1
  $SCRIPT_NAME check
  $SCRIPT_NAME diag --iface wlo1 --client-ip 192.168.1.100
  $SCRIPT_NAME capture --iface wlo1
  $SCRIPT_NAME watch --interval 3
  $SCRIPT_NAME watch-all --iface wlo1 --client-ip 192.168.1.100 --interval 3
  $SCRIPT_NAME pluto-logs --container ipsec-vpn-server --plutodebug
EOF
}

log_header() {
  echo ""
  echo "================================================================================"
  echo "$1"
  echo "time: $(date -Iseconds)"
  echo "host: $(hostname -f 2>/dev/null || hostname)"
  echo "================================================================================"
}

start_log() {
  mkdir -p "$LOG_DIR"
  if [[ -z "$LOG_FILE" ]]; then
    LOG_FILE="$LOG_DIR/vpn-debug-$(date +%Y%m%d_%H%M%S).log"
  fi
  mkdir -p "$(dirname "$LOG_FILE")"
  if [[ "$LOG_MODE" == "write" ]]; then
    : > "$LOG_FILE"
    exec > >(tee "$LOG_FILE") 2>&1
  else
    touch "$LOG_FILE"
    exec > >(tee -a "$LOG_FILE") 2>&1
  fi
  echo "log file: $LOG_FILE"
  echo "log mode: $LOG_MODE"
}

start_stream() {
  local stream_name="$1"
  shift
  (
    "$@" 2>&1 | while IFS= read -r line; do
      printf '[%s] [%s] %s\n' "$(date -Iseconds)" "$stream_name" "$line"
    done >> "$LOG_FILE"
  ) &
  LIVE_PIDS+=("$!")
}

cleanup_live_streams() {
  local pid
  for pid in "${LIVE_PIDS[@]}"; do
    kill "$pid" 2>/dev/null || true
  done
  if [[ -n "$LIVE_TAIL_PID" ]]; then
    kill "$LIVE_TAIL_PID" 2>/dev/null || true
  fi
}

resolve_ipsec_cmd() {
  if command -v ipsec >/dev/null 2>&1; then
    command -v ipsec
    return 0
  fi

  local candidate
  for candidate in /usr/sbin/ipsec /sbin/ipsec /usr/local/sbin/ipsec; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done

  return 1
}

run_check() {
  log_header "vpn_debug check"

  echo "[category: processes]"
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -a xl2tpd 2>/dev/null || echo "(no xl2tpd process)"
    pgrep -a pluto 2>/dev/null || pgrep -a ipsec 2>/dev/null || echo "(no pluto/ipsec process matched)"
  else
    ps aux 2>/dev/null | grep -E '[x]l2tpd|[p]luto' || true
  fi
  echo ""

  echo "[category: listeners]"
  if command -v ss >/dev/null 2>&1; then
    ss -ulpn 2>/dev/null | grep -E ':(1701|4500|500)\b' || echo "(no UDP listener matches)"
  else
    echo "ss not found"
  fi
  echo ""

  echo "[category: libreswan status]"
  local ipsec_cmd
  if ipsec_cmd="$(resolve_ipsec_cmd)"; then
    "$ipsec_cmd" status 2>/dev/null || sudo -n "$ipsec_cmd" status 2>/dev/null || echo "(ipsec status failed; try running script with sudo)"
  else
    echo "ipsec CLI not found (checked PATH and common sbin locations)"
  fi
  echo ""

  echo "[hints]"
  echo "- For Android/IPsec-L2TP transport mode, verify selectors include inner UDP/1701."
  echo "- WAN firewall should allow UDP 500 and 4500."
  echo "- Cleartext UDP 1701 is post-decap and usually not visible on physical interface tcpdump."
}

run_xfrm() {
  log_header "vpn_debug xfrm"

  echo "[category: /proc/net/xfrm_stat]"
  if [[ -r /proc/net/xfrm_stat ]]; then
    cat /proc/net/xfrm_stat
  else
    echo "(unreadable; root may be required)"
  fi
  echo ""

  echo "[category: ip xfrm state]"
  if command -v ip >/dev/null 2>&1; then
    if ip xfrm state >/dev/null 2>&1; then
      ip xfrm state 2>/dev/null
    elif sudo -n ip xfrm state >/dev/null 2>&1; then
      sudo -n ip xfrm state 2>/dev/null
    else
      echo "(ip xfrm state failed; need root/CAP_NET_ADMIN)"
    fi
  else
    echo "ip not found"
  fi
  echo ""

  echo "[category: ip xfrm policy]"
  if command -v ip >/dev/null 2>&1; then
    if ip xfrm policy >/dev/null 2>&1; then
      ip xfrm policy 2>/dev/null | grep -E '^(src |dst |dir |tmpl |proto )' || true
    elif sudo -n ip xfrm policy >/dev/null 2>&1; then
      sudo -n ip xfrm policy 2>/dev/null | grep -E '^(src |dst |dir |tmpl |proto )' || true
    else
      echo "(ip xfrm policy failed; need root/CAP_NET_ADMIN)"
    fi
  else
    echo "ip not found"
  fi
}

run_diag() {
  local iface="$1"
  local client_ip="$2"
  log_header "vpn_debug diag"

  run_xfrm
  echo ""

  echo "[category: UDP listeners]"
  if command -v ss >/dev/null 2>&1; then
    ss -ulpn 2>/dev/null | grep -E ':(500|4500|1701)\b' || echo "(none matched)"
  else
    echo "ss not found"
  fi
  echo ""

  echo "[category: tcpdump hints]"
  if [[ -z "$iface" ]]; then
    echo "Set --iface IFACE for ready-made tcpdump lines."
  else
    echo "Capture IKE + NAT-T:"
    echo "  sudo tcpdump -ni $iface 'udp port 500 or udp port 4500'"
    if [[ -n "$client_ip" ]]; then
      echo ""
      echo "Focus on client traffic:"
      echo "  sudo tcpdump -ni $iface host $client_ip and udp port 4500"
    fi
  fi
  echo ""
  echo "SPI correlation: wire SPI from client should match gateway 'dir in' SA."
}

run_capture() {
  local iface="$1"
  if [[ -z "$iface" ]]; then
    echo "error: --iface is required for capture" >&2
    exit 1
  fi

  log_header "vpn_debug capture"
  echo "Capturing on interface: $iface"
  echo "Filter: udp port 500 or udp port 4500"
  echo "Press Ctrl+C to stop capture."
  exec sudo tcpdump -ni "$iface" 'udp port 500 or udp port 4500'
}

run_watch() {
  local interval="$1"
  local count=1
  log_header "vpn_debug watch"
  echo "interval seconds: $interval"
  echo "Press Ctrl+C to stop."

  while true; do
    echo ""
    echo "----- iteration $count -----"
    run_check
    echo ""
    run_xfrm
    count=$((count + 1))
    sleep "$interval"
  done
}

run_watch_all() {
  local iface="$1"
  local client_ip="$2"
  local interval="$3"
  local count=1
  log_header "vpn_debug watch-all"
  echo "interval seconds: $interval"
  echo "iface: ${iface:-<not-set>}"
  echo "client-ip: ${client_ip:-<not-set>}"
  echo "Press Ctrl+C to stop."

  while true; do
    echo ""
    echo "----- full iteration $count -----"
    run_check
    echo ""
    run_diag "$iface" "$client_ip"
    count=$((count + 1))
    sleep "$interval"
  done
}

run_log() {
  local iface="$1"
  local container="$2"
  local adb_serial="$3"
  local adb_filter="$4"
  local adb_args=(adb)

  LOG_MODE="write"
  if [[ -z "$LOG_FILE" ]]; then
    LOG_FILE="$LOG_DIR/vpn-debug-live.log"
  fi
  mkdir -p "$LOG_DIR"
  mkdir -p "$(dirname "$LOG_FILE")"
  : > "$LOG_FILE"

  {
    echo "================================================================================"
    echo "vpn_debug log"
    echo "time: $(date -Iseconds)"
    echo "host: $(hostname -f 2>/dev/null || hostname)"
    echo "log file: $LOG_FILE"
    echo "mode: write (clean file on each run)"
    echo "================================================================================"
    echo ""
  } >> "$LOG_FILE"

  if [[ -n "$adb_serial" ]]; then
    adb_args+=(-s "$adb_serial")
  fi

  {
    run_check
    echo ""
    run_diag "$iface" ""
    echo ""
  } >> "$LOG_FILE" 2>&1

  if command -v adb >/dev/null 2>&1; then
    "${adb_args[@]}" start-server >/dev/null 2>&1 || true
    echo "[$(date -Iseconds)] [adb-logcat] adb devices snapshot:" >> "$LOG_FILE"
    "${adb_args[@]}" devices -l 2>&1 | while IFS= read -r line; do
      printf '[%s] [adb-logcat] %s\n' "$(date -Iseconds)" "$line" >> "$LOG_FILE"
    done
    if "${adb_args[@]}" get-state >/dev/null 2>&1; then
      # Keep ADB output focused and avoid replaying large historical buffers.
      start_stream "adb-logcat" "${adb_args[@]}" logcat -T 1 -v threadtime "*:S" "$adb_filter"
    else
      echo "[$(date -Iseconds)] [adb-logcat] no connected adb device; skipping." >> "$LOG_FILE"
    fi
  else
    echo "[$(date -Iseconds)] [adb-logcat] adb not found; skipping." >> "$LOG_FILE"
  fi

  if command -v ip >/dev/null 2>&1; then
    if ip xfrm state >/dev/null 2>&1; then
      start_stream "xfrm-monitor" ip xfrm monitor
    elif command -v sudo >/dev/null 2>&1; then
      # xfrm netlink monitor often requires root/CAP_NET_ADMIN.
      if sudo -n ip xfrm state >/dev/null 2>&1; then
        start_stream "xfrm-monitor" sudo ip xfrm monitor
      else
        echo "[$(date -Iseconds)] [xfrm-monitor] permission denied; run with sudo or grant CAP_NET_ADMIN." >> "$LOG_FILE"
      fi
    else
      echo "[$(date -Iseconds)] [xfrm-monitor] permission denied; sudo not found." >> "$LOG_FILE"
    fi
  else
    echo "[$(date -Iseconds)] [xfrm-monitor] ip command not found; skipping." >> "$LOG_FILE"
  fi

  if [[ -n "$iface" ]]; then
    if command -v tcpdump >/dev/null 2>&1; then
      if sudo -n true >/dev/null 2>&1; then
        start_stream "tcpdump" sudo -n tcpdump -l -ni "$iface" "udp port 500 or udp port 4500"
      else
        echo "[$(date -Iseconds)] [tcpdump] sudo requires a TTY/password; skipping packet capture. To enable: configure sudo NOPASSWD for tcpdump or run this script from an interactive terminal and authenticate sudo once." >> "$LOG_FILE"
      fi
    else
      echo "[$(date -Iseconds)] [tcpdump] tcpdump not found; skipping." >> "$LOG_FILE"
    fi
  else
    echo "[$(date -Iseconds)] [tcpdump] --iface not set; skipping packet capture." >> "$LOG_FILE"
  fi

  if command -v docker >/dev/null 2>&1 && docker inspect "$container" >/dev/null 2>&1; then
    start_stream "pluto-auth-log" docker exec -i "$container" sh -c "touch /var/log/auth.log && tail -n 0 -F /var/log/auth.log"
    start_stream "xl2tpd-log" docker exec -i "$container" sh -c '
      for f in /var/log/xl2tpd.log /var/log/messages /var/log/syslog; do
        touch "$f" 2>/dev/null || true
      done
      # Only pass non-empty list of files to tail to avoid unbound variable error
      files=""
      for file in /var/log/xl2tpd.log /var/log/messages /var/log/syslog; do
        if [ -e "$file" ]; then
          files="$files \"$file\""
        fi
      done
      # Fallback: If nothing exists, still try (avoiding unbound error)
      eval "tail -n 0 -F $files" 2>/dev/null
    '
  else
    echo "[$(date -Iseconds)] [pluto-auth-log] container '$container' not available; skipping." >> "$LOG_FILE"
  fi

  echo "[$(date -Iseconds)] [vpn-debug] live aggregation started. Press Ctrl+C to stop." >> "$LOG_FILE"
  echo "Reading live output from: $LOG_FILE"
  echo "Press Ctrl+C to stop."

  trap cleanup_live_streams INT TERM EXIT
  tail -n +1 -f "$LOG_FILE" &
  LIVE_TAIL_PID="$!"
  wait "$LIVE_TAIL_PID" || true
}

run_pluto_logs() {
  local container="$1"
  local enable_plutodebug="$2"
  log_header "vpn_debug pluto-logs"

  if ! command -v docker >/dev/null 2>&1; then
    echo "error: docker is required" >&2
    exit 1
  fi

  if ! docker inspect "$container" >/dev/null 2>&1; then
    echo "error: container '$container' not found. Start it with docker compose up -d" >&2
    exit 1
  fi

  echo "Installing rsyslog and restarting ipsec in '$container'..."
  docker exec -i "$container" sh -s <<'EOS'
set -e
if [ -f /etc/alpine-release ]; then
  apk add --no-cache rsyslog
  rm -f /var/run/rsyslogd.pid
  rsyslogd
  rc-service ipsec stop 2>/dev/null || true
  rc-service -D ipsec start >/dev/null 2>&1
  if ! grep -q rsyslogd /opt/src/run.sh; then
    sed -i '\|pluto\.pid|a rm -f /var/run/rsyslogd.pid; rsyslogd' /opt/src/run.sh
  fi
elif [ -f /etc/debian_version ]; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq rsyslog
  rm -f /var/run/rsyslogd.pid
  rsyslogd
  service ipsec restart
  if ! grep -q rsyslogd /opt/src/run.sh; then
    sed -i '\|pluto\.pid|a rm -f /var/run/rsyslogd.pid; rsyslogd' /opt/src/run.sh
  fi
else
  echo "error: unsupported base image (need Alpine or Debian)" >&2
  exit 1
fi
EOS

  if [[ "$enable_plutodebug" -eq 1 ]]; then
    echo "Enabling plutodebug=all..."
    docker exec -i "$container" sh -s <<'EOS'
set -e
if grep -q '^[[:space:]]*plutodebug=' /etc/ipsec.conf; then
  echo "(plutodebug already set in /etc/ipsec.conf)"
else
  sed -i '/^config setup/a\    plutodebug=all' /etc/ipsec.conf
fi
if [ -f /etc/alpine-release ]; then
  rc-service ipsec stop 2>/dev/null || true
  rc-service -D ipsec start >/dev/null 2>&1
else
  service ipsec restart
fi
EOS
  fi

  echo ""
  echo "Done. Read logs with:"
  echo "  docker exec -it $container grep pluto /var/log/auth.log"
  echo "  docker exec -it $container tail -f /var/log/auth.log"
}

parse_global_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --log-dir)
        LOG_DIR="${2:-}"
        shift 2
        ;;
      --log-file)
        LOG_FILE="${2:-}"
        shift 2
        ;;
      -h|--help)
        print_usage
        exit 0
        ;;
      log|check|xfrm|diag|capture|watch|watch-all|pluto-logs)
        COMMAND="$1"
        shift
        break
        ;;
      *)
        echo "error: unknown option or command '$1'" >&2
        print_usage
        exit 1
        ;;
    esac
  done

  if [[ -z "$COMMAND" ]]; then
    print_usage
    exit 1
  fi

  REMAINING_ARGS=("$@")
}

main() {
  parse_global_args "$@"
  if [[ "$COMMAND" == "log" ]]; then
    local iface=""
    local container="ipsec-vpn-server"
    local adb_serial=""
    local adb_filter="tunnel_engine:V"
    while [[ ${#REMAINING_ARGS[@]} -gt 0 ]]; do
      case "${REMAINING_ARGS[0]}" in
        --iface) iface="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
        --container) container="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
        --adb-serial) adb_serial="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
        --adb-filter) adb_filter="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
        *) echo "error: unknown log option '${REMAINING_ARGS[0]}'" >&2; exit 1 ;;
      esac
    done
    run_log "$iface" "$container" "$adb_serial" "$adb_filter"
    return
  fi

  if [[ "$COMMAND" == "watch-all" ]]; then
    LOG_MODE="write"
    if [[ -z "$LOG_FILE" ]]; then
      LOG_FILE="$LOG_DIR/vpn-debug-watch-all.log"
    fi
  fi
  start_log

  case "$COMMAND" in
    check)
      run_check
      ;;
    xfrm)
      run_xfrm
      ;;
    diag)
      local iface=""
      local client_ip=""
      while [[ ${#REMAINING_ARGS[@]} -gt 0 ]]; do
        case "${REMAINING_ARGS[0]}" in
          --iface) iface="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          --client-ip) client_ip="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          *) echo "error: unknown diag option '${REMAINING_ARGS[0]}'" >&2; exit 1 ;;
        esac
      done
      run_diag "$iface" "$client_ip"
      ;;
    capture)
      local iface=""
      while [[ ${#REMAINING_ARGS[@]} -gt 0 ]]; do
        case "${REMAINING_ARGS[0]}" in
          --iface) iface="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          *) echo "error: unknown capture option '${REMAINING_ARGS[0]}'" >&2; exit 1 ;;
        esac
      done
      run_capture "$iface"
      ;;
    watch)
      local interval=5
      while [[ ${#REMAINING_ARGS[@]} -gt 0 ]]; do
        case "${REMAINING_ARGS[0]}" in
          --interval) interval="${REMAINING_ARGS[1]:-5}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          *) echo "error: unknown watch option '${REMAINING_ARGS[0]}'" >&2; exit 1 ;;
        esac
      done
      run_watch "$interval"
      ;;
    watch-all)
      local iface=""
      local client_ip=""
      local interval=5
      while [[ ${#REMAINING_ARGS[@]} -gt 0 ]]; do
        case "${REMAINING_ARGS[0]}" in
          --iface) iface="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          --client-ip) client_ip="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          --interval) interval="${REMAINING_ARGS[1]:-5}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          *) echo "error: unknown watch-all option '${REMAINING_ARGS[0]}'" >&2; exit 1 ;;
        esac
      done
      run_watch_all "$iface" "$client_ip" "$interval"
      ;;
    pluto-logs)
      local container="ipsec-vpn-server"
      local plutodebug=0
      while [[ ${#REMAINING_ARGS[@]} -gt 0 ]]; do
        case "${REMAINING_ARGS[0]}" in
          --container) container="${REMAINING_ARGS[1]:-}"; REMAINING_ARGS=("${REMAINING_ARGS[@]:2}") ;;
          --plutodebug) plutodebug=1; REMAINING_ARGS=("${REMAINING_ARGS[@]:1}") ;;
          *) echo "error: unknown pluto-logs option '${REMAINING_ARGS[0]}'" >&2; exit 1 ;;
        esac
      done
      run_pluto_logs "$container" "$plutodebug"
      ;;
  esac
}

main "$@"
