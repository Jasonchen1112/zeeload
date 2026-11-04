#!/usr/bin/env python3
"""Exercise the live pf parent hook with command fixtures, never the host pf."""
import os
import argparse
from pathlib import Path
import subprocess
import tempfile

DEFAULT_SOURCE = Path(__file__).resolve().parents[2]
PARENT = (
    'scrub-anchor "base_v4" all fragment reassemble\n'
    'scrub-anchor "base_nat66" all fragment reassemble\n'
    'nat-anchor "base_v4" all\n'
    'nat-anchor "base_nat66" all\n'
    'rdr-anchor "base_v4" all\n'
    'rdr-anchor "base_nat66" all\n'
    'anchor "base_v4" all\n'
    'anchor "base_nat66" all\n'
)

STUB = r'''#!/bin/bash
case "${0##*/}" in
  id) echo 0 ;;
  ifconfig)
    if [ "$1" = -l ]; then echo 'lo0 bridge100'; else
      printf 'bridge100: flags\n    inet 192.168.2.1 netmask 0xffffff00\n    status: active\n    member: ap1\n'
    fi ;;
  arp) echo '? (192.168.2.3) at 24:b0:a9:9d:82:63 on bridge100 ifscope [bridge]' ;;
  ping) exit 0 ;;
  dig) exit 0 ;;
  lsof)
    if [ -f "$LISTEN_MARKER" ]; then echo 'mitmdump 123 user 3u IPv4 TCP 127.0.0.1:18080 (LISTEN)'; else exit 1; fi ;;
  pgrep) exit 0 ;;
  pkill) exit 0 ;;
  pfctl)
    printf '%s\n' "$*" >> "$PF_CALLS"
    if [ "$1" = -n ]; then /sbin/pfctl "$@" >/dev/null 2>&1; exit $?; fi
    if [ "$1" = -k ]; then exit 0; fi
    printf 'No ALTQ support in kernel\nALTQ related functions disabled\n' >&2
    if [ "$1" = -s ]; then
      case "$2" in
        info) echo 'Status: Enabled' ;;
        nat) echo 'rdr-anchor "com.apple.internet-sharing" all' ;;
      esac
      exit 0
    fi
    if [ "$1" = -a ]; then
      anchor=$2; shift 2
      case "$anchor:$1:$2" in
        com.apple.internet-sharing:-s:nat)
          awk '/^(nat|rdr)/' "$PF_PARENT"; exit 0 ;;
        com.apple.internet-sharing:-s:rules)
          awk '/^(scrub|anchor)/' "$PF_PARENT"; exit 0 ;;
        com.apple.internet-sharing:-f:*)
          if [ "$FAIL_PARENT" = 1 ] && [ ! -f "$PF_FAILED" ]; then
            touch "$PF_FAILED"; echo 'fixture load failure' >&2; exit 1
          fi
          cp "$2" "$PF_PARENT"; exit 0 ;;
        com.apple.internet-sharing/carstore-capture:-s:nat)
          cat "$PF_CHILD"
          if [ "${3:-}" = -v ]; then echo '  [ Evaluations: 1 Packets: 0 Bytes: 0 States: 0 ]'; fi
          exit 0 ;;
        com.apple.internet-sharing/carstore-capture:-s:rules) exit 0 ;;
        com.apple.internet-sharing/carstore-capture:-f:*) cp "$2" "$PF_CHILD"; exit 0 ;;
        com.apple.internet-sharing/carstore-capture:-F:all) : > "$PF_CHILD"; exit 0 ;;
        com.apple/carstore-capture:-s:nat) exit 0 ;;
        com.apple/carstore-capture:-F:all) exit 0 ;;
      esac
    fi
    echo "unexpected pfctl: $*" >&2; exit 99 ;;
  mitmdump)
    case "${1:-}" in
      --version) echo 'Mitmproxy: fixture' ;;
      --help) echo '--emudhra' ;;
      *) touch "$LISTEN_MARKER"; echo 'fixture proxy listening'; sleep 4 ;;
    esac ;;
esac
'''


def run(source, fail_parent=False, emergency=False):
    with tempfile.TemporaryDirectory(prefix='zeeload-pf-parent-') as tmp:
        root = Path(tmp)
        for folder in ('scripts', 'addons', 'venv/bin', 'bin'):
            (root / folder).mkdir(parents=True)
        (root / 'scripts/transparent-test.sh').write_text(
            (source / 'scripts/transparent-test.sh').read_text()
        )
        (root / 'scripts/stop-transparent.sh').write_text(
            (source / 'scripts/stop-transparent.sh').read_text()
        )
        (root / 'addons/store_log.py').write_text('')
        for name in ('id', 'ifconfig', 'arp', 'ping', 'dig', 'lsof', 'pgrep', 'pkill', 'pfctl'):
            path = root / 'bin' / name
            path.write_text(STUB)
            path.chmod(0o755)
        mitmdump = root / 'venv/bin/mitmdump'
        mitmdump.write_text(STUB)
        mitmdump.chmod(0o755)
        parent = root / 'parent.conf'
        child = root / 'child.conf'
        if emergency:
            parent.write_text(
                PARENT.replace('rdr-anchor "base_nat66" all\n',
                               'rdr-anchor "base_nat66" all\nrdr-anchor "carstore-capture" all\n')
            )
        else:
            parent.write_text(PARENT)
        child.write_text('rdr pass on bridge100 inet proto tcp from 192.168.2.3 to any port 443 -> 127.0.0.1 port 18080\n' if emergency else '')
        if emergency:
            (root / 'mitm-config').mkdir()
            (root / 'mitm-config/carstore-parent-hook.active').write_text('fixture\n')
        env = {k: v for k, v in os.environ.items() if not k.startswith(('SIDELOAD_', 'SUDO_'))}
        env.update(
            PATH=str(root / 'bin') + ':/usr/bin:/bin:/usr/sbin:/sbin',
            SIDELOAD_IFACE='bridge100',
            SIDELOAD_CAR_IP='192.168.2.3',
            SIDELOAD_CAR_MAC='24:b0:a9:9d:82:63',
            SIDELOAD_VENV=str(root / 'venv'),
            PF_PARENT=str(parent),
            PF_CHILD=str(child),
            PF_CALLS=str(root / 'pf-calls.txt'),
            PF_FAILED=str(root / 'pf-failed'),
            LISTEN_MARKER=str(root / 'listening'),
            FAIL_PARENT='1' if fail_parent else '0',
        )
        command = (
            ['/bin/bash', str(root / 'scripts/stop-transparent.sh')]
            if emergency else
            ['/bin/bash', str(root / 'scripts/transparent-test.sh'), '--all-443']
        )
        result = subprocess.run(
            command,
            env=env, capture_output=True, text=True, timeout=20,
        )
        assert parent.read_text() == PARENT, (parent.read_text(), result.stdout, result.stderr)
        assert child.read_text() == '', (child.read_text(), result.stdout, result.stderr)
        assert not (root / 'mitm-config/carstore-parent-hook.active').exists()
        calls = (root / 'pf-calls.txt').read_text()
        assert '-a com.apple.internet-sharing -f ' in calls, calls
        if emergency:
            assert result.returncode == 0, (result.stdout, result.stderr)
            assert '[ok] 已移除互联网共享父 anchor' in result.stdout
        elif fail_parent:
            assert result.returncode != 0 and '父 anchor 挂接失败' in result.stderr
        else:
            assert result.returncode == 0, (result.stdout, result.stderr)
            assert '[ok] 已临时挂接父 anchor' in result.stdout
            assert '[ok] 已移除互联网共享父 anchor' in result.stdout


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-root', type=Path, default=DEFAULT_SOURCE)
    source = parser.parse_args().source_root
    run(source, False)
    print('PASS: parent hook load and cleanup')
    run(source, True)
    print('PASS: failed parent load cleanup')
    run(source, emergency=True)
    print('PASS: emergency stop restores parent hook')
