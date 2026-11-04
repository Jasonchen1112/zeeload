#!/bin/bash
# 只读环境检查（不需要管理员密码）。在接车机前后各跑一次。
# 用法：bash scripts/check-environment.sh

set -uo pipefail
BASE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [ -n "${SIDELOAD_VENV:-}" ]; then
  MITMDUMP=$SIDELOAD_VENV/bin/mitmdump
elif [ -x "$BASE/venv/bin/mitmdump" ]; then
  MITMDUMP=$BASE/venv/bin/mitmdump
else
  MITMDUMP=$(command -v mitmdump 2>/dev/null || echo mitmdump)
fi
PORT=${SIDELOAD_PORT:-18080}
DOMAINS=${SIDELOAD_DOMAINS:-"snc-api-gw.zeekrlife.com snc-download-cdn.zeekrlife.com"}

# 自动探测：共享接口（bridge*）与车机（DHCP 租约按名称识别）
detect_iface() {
  local cand addr status best=""
  for cand in $(ifconfig -l 2>/dev/null | tr ' ' '\n' | grep '^bridge'); do
    addr=$(ifconfig "$cand" 2>/dev/null | awk '/inet /{print $2; exit}')
    [ -n "$addr" ] || continue
    case "$addr" in 192.168.*|10.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) ;; *) continue ;; esac
    status=$(ifconfig "$cand" 2>/dev/null | awk '/status:/{print $2; exit}')
    if [ "$status" = "active" ]; then
      ifconfig "$cand" 2>/dev/null | grep -q 'member: ap' && { echo "$cand"; return; }
      best="${best:-$cand}"
    fi
  done
  echo "$best"
}

detect_lease() {
  local prefix="$1" mode="$2"
  [ -r /var/db/dhcpd_leases ] || return
  awk -v prefix="$prefix" -v mode="$mode" '
    /^\{/ { name=""; ip=""; hw=""; next }
    /name=/ { sub(/.*name=/, ""); name=$0 }
    /ip_address=/ { sub(/.*ip_address=/, ""); ip=$0 }
    /hw_address=/ { sub(/.*hw_address=/, ""); hw=$0 }
    /^\}/ {
      if (ip ~ ("^" prefix)) {
        wanted = (name ~ /(auto|headunit|hu|dhu)/)
        if (mode == "ip" && wanted) print ip
        if (mode == "mac" && wanted) { n=split(hw, p, ","); s=p[n]; m=split(s, o, ":");
          if (m >= 6) printf "%s:%s:%s:%s:%s:%s\n", o[m-5], o[m-4], o[m-3], o[m-2], o[m-1], o[m] }
      }
    }
  ' /var/db/dhcpd_leases
}

# DHCP 名称可能为空；只在共享接口上存在唯一匹配的 ARP 设备时自动选择。
resolve_car_from_arp() {
  local prefix="$1" own peers selected
  [ -n "$prefix" ] || return 0
  own=$(ifconfig "$IFACE" 2>/dev/null | awk '/inet /{print $2; exit}')
  peers=$(arp -an 2>/dev/null | awk -v iface="$IFACE" -v prefix="$prefix" -v own="$own" '
    $3 == "at" && $5 == "on" && $6 == iface {
      ip=$2; gsub(/[()]/, "", ip); mac=tolower($4)
      if (index(ip, prefix) != 1 || ip == own || mac == "ff:ff:ff:ff:ff:ff") next
      if (split(mac, oct, ":") != 6) next
      valid=1
      for (i=1; i<=6; i++) if (oct[i] !~ /^[0-9a-f][0-9a-f]?$/) valid=0
      if (valid) print ip, mac
    }
  ' | sort -u)
  if [ -z "$CAR_IP" ]; then
    selected=$(printf '%s\n' "$peers" | awk -v mac="$CAR_MAC" '
      NF == 2 && (mac == "" || $2 == tolower(mac)) { count++; ip=$1 }
      END { if (count == 1) print ip }
    ')
    CAR_IP=$selected
  fi
  if [ -n "$CAR_IP" ] && [ -z "$CAR_MAC" ]; then
    CAR_MAC=$(printf '%s\n' "$peers" | awk -v ip="$CAR_IP" '$1 == ip {print $2; exit}')
  fi
}

IFACE=${SIDELOAD_IFACE:-$(detect_iface)}
prefix=""
if [ -n "$IFACE" ]; then
  prefix=$(ifconfig "$IFACE" 2>/dev/null | awk '/inet /{print $2; exit}' | sed 's/\.[0-9]*$/\./')
fi
CAR_IP=${SIDELOAD_CAR_IP:-}
CAR_MAC=${SIDELOAD_CAR_MAC:-}
if [ -n "$prefix" ]; then
  CAR_IP=${CAR_IP:-$(detect_lease "$prefix" ip | head -1)}
  resolve_car_from_arp "$prefix"
fi

say() { printf '%s\n' "$*"; }
ok() { printf '[ok] %s\n' "$*"; }
bad() { printf '[!!] %s\n' "$*"; }

say "== 时间 =="; date
say ""
say "== 工具 =="
if [ -x "$MITMDUMP" ]; then
  ok "mitmdump: $("$MITMDUMP" --version 2>&1 | head -1)"
  grep -q -- '--emudhra' <("$MITMDUMP" --help 2>&1) && ok "支持 --emudhra" || bad "当前 mitmdump 不支持 --emudhra"
else
  bad "找不到 $MITMDUMP"
fi
[ -f "$BASE/addons/store_log.py" ] && ok "addon 存在" || bad "缺少 addons/store_log.py"

say ""
say "== 接口与地址 =="
if [ -z "$IFACE" ]; then
  bad "未检测到共享接口：请先开启「互联网共享」，或用 SIDELOAD_IFACE 指定"
else
  ifconfig "$IFACE" 2>/dev/null | awk '/inet /{print "  '"$IFACE"' -> " $2}'
fi
gw=$(netstat -rn -f inet 2>/dev/null | awk '$1=="default" && $4 ~ /^en/ {print $2" via "$4; exit}')
say "  默认路由：${gw:-未找到}"
say "  热点 DHCP 配置（/etc/bootpd.plist）中的 DNS："
grep -A2 dhcp_domain_name_server /etc/bootpd.plist 2>/dev/null | grep string | sed 's/^/    /'

say ""
say "== 车机 =="
if [ -z "$CAR_IP" ]; then
  bad "未识别到唯一车机地址：请检查热点连接，或用 SIDELOAD_CAR_IP 指定目标 IP"
  arp -an | awk -v iface="$IFACE" '$5 == "on" && $6 == iface' | sed 's/^/  /'
else
  if [ -n "$CAR_MAC" ] && arp -n "$CAR_IP" 2>/dev/null | grep -Fqi "$CAR_MAC"; then
    ok "ARP：$CAR_IP -> $CAR_MAC"
  else
    bad "ARP 中没有 $CAR_IP -> ${CAR_MAC:-未知 MAC}（车机 IP 可能变化）"
  fi
  ping -c 1 -t 2 "$CAR_IP" >/dev/null 2>&1 && ok "ping $CAR_IP 通" || bad "ping $CAR_IP 失败"
fi

say ""
say "== 目标域名解析（Mac 侧，车机使用 $IFACE 上的地址作为 DNS）=="
for d in $DOMAINS; do
  say "  $d:"
  if command -v dig >/dev/null 2>&1; then
    dig +short A "$d" 2>/dev/null | grep -E '^[0-9]+\.' | sed 's/^/    /'
  else
    host -t A "$d" 2>/dev/null | awk '/has address/{print "    "$NF}'
  fi
done

say ""
say "== 端口与进程 =="
if command -v lsof >/dev/null 2>&1; then
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    bad "端口 $PORT 已被占用："
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | sed 's/^/  /'
  else
    ok "端口 $PORT 空闲"
  fi
fi
pgrep -fl 'mitmdump' | sed 's/^/  /' || ok "没有运行中的 mitmdump"

say ""
say "== 权限提示 =="
if /sbin/pfctl -s info >/dev/null 2>&1; then
  ok "当前用户可读 pf（少见）"
else
  say "  pfctl 需要管理员权限：透明转发测试请用 sudo 运行 scripts/transparent-test.sh"
fi
