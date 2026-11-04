#!/usr/bin/env python3
"""Run macOS startup checks against command fixtures; never touch the host pf rules."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

DEFAULT_ROOT = Path(__file__).resolve().parents[2]

STUB = '''#!/bin/bash
case "${0##*/}" in
  id) echo 0 ;;
  ifconfig)
    if [ "$1" = -l ]; then echo 'lo0 bridge100'; else
      printf 'bridge100: flags\\n    inet 192.168.2.1 netmask 0xffffff00\\n    status: active\\n    member: ap1\\n'
    fi ;;
  arp) cat "$FIXTURE_ARP" ;;
  pfctl)
    printf '%s\\n' "$*" >> "$FIXTURE_PF_CALLS"
    case "$*" in
      '-s info') echo 'Status: Enabled' ;;
      '-s nat') printf 'rdr-anchor "%s" all\\n' "$FIXTURE_ANCHOR" ;;
      '-a com.apple.internet-sharing -s nat')
        printf 'nat-anchor "base_v4" all\\nrdr-anchor "base_v4" all\\n' ;;
      '-s rules'|'-s Anchors'|'-s Tables'|'-s Limits') : ;;
      *) echo 'Unexpected pf mutation' >&2; exit 99 ;;
    esac ;;
  ping) [ -n "${4:-}" ] ;;
  dig) : ;;
  lsof|pgrep) exit 1 ;;
  netstat) : ;;
  mitmdump)
    case "$1" in --version) echo 'Mitmproxy: fixture';; --help) echo '--emudhra';;
      *) echo 'Unexpected proxy launch' >&2; exit 99;; esac ;;
esac
'''

PEER = '? (192.168.2.3) at 24:b0:a9:9d:82:63 on bridge100 ifscope [bridge]\n'
SECOND = '? (192.168.2.4) at 02:00:00:00:00:04 on bridge100 ifscope [bridge]\n'


def run(source, case):
    with tempfile.TemporaryDirectory(prefix='zeeload-check-') as tmp:
        root = Path(tmp)
        for folder in ('scripts', 'addons', 'venv/bin', 'bin'):
            (root / folder).mkdir(parents=True)
        (root / 'addons/store_log.py').write_text('')
        lease = root / 'leases'
        lease.write_text('{\n\tip_address=192.168.2.3\n\thw_address=1,24:b0:a9:9d:82:63\n}\n')
        for name in ('transparent-test.sh', 'check-environment.sh'):
            # Redirect the only hardcoded DHCP data input to a fixture.
            content = (source / 'scripts' / name).read_text()
            (root / 'scripts' / name).write_text(content.replace('/var/db/dhcpd_leases', str(lease)))
        for name in ('id', 'ifconfig', 'arp', 'pfctl', 'ping', 'dig', 'lsof', 'pgrep', 'netstat'):
            p = root / 'bin' / name
            p.write_text(STUB)
            p.chmod(0o755)
        p = root / 'venv/bin/mitmdump'
        p.write_text(STUB)
        p.chmod(0o755)
        peers = '' if case == 'missing' else PEER
        if case in ('ambiguous', 'explicit', 'mac_selected'):
            peers += SECOND
        # Other interfaces and incomplete entries must not affect selection.
        peers += '? (192.168.2.8) at 02:00:00:00:00:08 on en8 ifscope [ethernet]\n'
        peers += '? (192.168.2.9) at (incomplete) on bridge100 ifscope [bridge]\n'
        arp = root / 'arp.txt'
        arp.write_text(peers)
        calls = root / 'pf-calls.txt'
        env = {k: v for k, v in os.environ.items() if not k.startswith(('SIDELOAD_', 'SUDO_'))}
        env.update(PATH=str(root / 'bin') + ':/usr/bin:/bin:/usr/sbin:/sbin',
                   FIXTURE_ARP=str(arp), FIXTURE_PF_CALLS=str(calls),
                   FIXTURE_ANCHOR='com.apple/*' if case == 'wildcard' else 'com.apple.internet-sharing')
        if case in ('anchor', 'wildcard', 'explicit'):
            env['SIDELOAD_CAR_IP'] = '192.168.2.3'
        if case == 'mac_selected':
            env['SIDELOAD_CAR_MAC'] = '24:b0:a9:9d:82:63'
        name = 'check-environment.sh' if case == 'environment' else 'transparent-test.sh'
        args = ['/bin/bash', str(root / 'scripts' / name)]
        if name == 'transparent-test.sh':
            args += ['--all-443', '--check-only']
        r = subprocess.run(args, env=env, capture_output=True, timeout=20)
        assert not calls.exists() or all(
            line.startswith('-s ') or line == '-a com.apple.internet-sharing -s nat'
            for line in calls.read_text().splitlines()
        )
        rules = list((root / 'logs').glob('*/rdr.conf'))
        rule = rules[0].read_text() if rules else ''
        return r, rule


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-root', type=Path, default=DEFAULT_ROOT)
    parser.add_argument('--probe', choices=['anchor', 'auto'])
    args = parser.parse_args()
    if args.probe:
        r, _ = run(args.source_root, args.probe)
        print('stdout:\n' + r.stdout.decode('utf-8', errors='backslashreplace'))
        print('stderr:\n' + r.stderr.decode('utf-8', errors='backslashreplace'))
        print('exit:', r.returncode)
        return r.returncode
    for case in ('anchor', 'wildcard', 'auto', 'explicit', 'mac_selected', 'missing', 'ambiguous', 'environment'):
        r, rule = run(args.source_root, case)
        output = (r.stdout + r.stderr).decode('utf-8', errors='backslashreplace')
        if case in ('missing', 'ambiguous'):
            assert r.returncode != 0 and '未识别到唯一车机地址' in output, (case, output)
            assert not rule, (case, rule)
        else:
            assert r.returncode == 0, (case, output)
            assert '192.168.2.3' in output and '24:b0:a9:9d:82:63' in output, (case, output)
            assert 'unbound variable' not in output, (case, output)
            if case != 'environment':
                assert 'from 192.168.2.3 to any port 443' in rule, (case, rule)
                assert '/24' not in rule, (case, rule)
                assert 'check-only：未加载规则，未启动代理。' in output
        print('PASS:', case)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
