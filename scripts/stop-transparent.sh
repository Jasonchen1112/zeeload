#!/bin/bash
# 紧急停止 / 恢复：卸载 anchor 规则并停止透明代理进程。可重复执行。
# 用法：sudo bash scripts/stop-transparent.sh

set -uo pipefail

ANCHOR=${SIDELOAD_ANCHOR:-}
BASE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PARENT_ANCHOR='com.apple.internet-sharing'
PARENT_HOOK='rdr-anchor "carstore-capture" all'
PARENT_MARKER=$BASE/mitm-config/carstore-parent-hook.active

pf_parent_nat() {
  pfctl -a "$PARENT_ANCHOR" -s nat 2>&1 |
    sed '/^No ALTQ support in kernel$/d; /^ALTQ related functions disabled$/d'
}

pf_parent_rules() {
  pfctl -a "$PARENT_ANCHOR" -s rules 2>&1 |
    sed '/^No ALTQ support in kernel$/d; /^ALTQ related functions disabled$/d'
}

say() { printf '%s\n' "$*"; }
warn() { printf '警告：%s\n' "$*" >&2; }

[ "$(id -u)" = 0 ] || { echo "需要管理员权限：sudo bash $0" >&2; exit 1; }

if [ -f "$PARENT_MARKER" ]; then
  if ! PARENT_NAT=$(pf_parent_nat); then
    warn "读取父 anchor 失败，请保留 $PARENT_MARKER"
  elif printf '%s\n' "$PARENT_NAT" | grep -Fqx "$PARENT_HOOK"; then
    if ! PARENT_RULES=$(pf_parent_rules); then
      warn "读取父 anchor 过滤规则失败，请保留 $PARENT_MARKER"
      exit 1
    fi
    RESTORE=$(mktemp "${TMPDIR:-/tmp}/zeeload-parent.XXXXXX") || exit 1
    {
      printf '%s\n' "$PARENT_RULES" | sed -n '/^scrub[- ]/p; /^no scrub /p'
      printf '%s\n' "$PARENT_NAT" | grep -Fxv "$PARENT_HOOK"
      printf '%s\n' "$PARENT_RULES" | sed '/^scrub[- ]/d; /^no scrub /d; /^$/d'
    } > "$RESTORE"
    if pfctl -n -a "$PARENT_ANCHOR" -f "$RESTORE" >/dev/null 2>&1 &&
       pfctl -a "$PARENT_ANCHOR" -f "$RESTORE" >/dev/null 2>&1; then
      rm -f "$PARENT_MARKER"
      say "[ok] 已移除互联网共享父 anchor 中的临时挂接"
    else
      warn "父 anchor 恢复失败，请保留 $PARENT_MARKER 并检查 pf 规则"
    fi
    rm -f "$RESTORE"
  else
    rm -f "$PARENT_MARKER"
  fi
fi

if [ -n "$ANCHOR" ]; then
  ANCHORS="$ANCHOR"
else
  ANCHORS='com.apple.internet-sharing/carstore-capture com.apple/carstore-capture'
fi
say "== 卸载 pf anchor =="
for anchor in $ANCHORS; do
  pfctl -a "$anchor" -F all 2>/dev/null
  if pfctl -a "$anchor" -s nat 2>/dev/null | grep -q .; then
    warn "anchor 仍有规则，请检查：sudo pfctl -a $anchor -s nat"
  else
    say "[ok] $anchor 规则已清空"
  fi
done

say ""
say "== 停止透明代理进程 =="
if pgrep -f 'mitmdump.*--mode transparent' >/dev/null 2>&1; then
  pkill -f 'mitmdump.*--mode transparent'
  sleep 1
  if pgrep -f 'mitmdump.*--mode transparent' >/dev/null 2>&1; then
    pkill -9 -f 'mitmdump.*--mode transparent'
    say "[ok] 已强制停止 mitmdump"
  else
    say "[ok] 已停止 mitmdump"
  fi
else
  say "[ok] 没有运行中的透明 mitmdump"
fi

say ""
say "== 当前状态 =="
pfctl -s info 2>/dev/null | awk '/Status|State Table|References/{print "  " $0}'
say "当前 anchor 列表（应只剩系统项）："
pfctl -s Anchors 2>/dev/null | sed 's/^/  /' | head -n 20

say ""
say "若车机仍无法上网：确认系统设置 -> 通用 -> 共享 -> 互联网共享 仍为开启；"
say "必要时关掉再打开一次共享，让系统重新加载它自己的 NAT/转发规则。"
