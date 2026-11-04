#!/usr/bin/env python3
"""Exercise the macOS addon host gate and update rewrite with a local flow."""
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace

from mitmproxy import http

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "addons"))
import store_log  # noqa: E402

API_HOST = "snc-api-gw.zeekrlife.com"
CDN_HOST = "snc-download-cdn.zeekrlife.com"
SHA = "a" * 64
MD5 = "b" * 32


def main():
    with tempfile.TemporaryDirectory(prefix="zeeload-inject-") as tmp:
        messages = []
        store_log.ctx = SimpleNamespace(
            options=SimpleNamespace(
                out_dir=tmp,
                hosts=store_log.DEFAULT_HOSTS,
                fake_hash=False,
                fake_sign=False,
                override_sha256=SHA,
                override_md5=MD5,
                inject_pkg="ALL",
                replace_apk="",
                body_limit=0,
            ),
            log=SimpleNamespace(warn=messages.append),
        )
        addon = store_log.StoreLog()
        assert addon._host_matches(API_HOST)
        assert addon._host_matches(CDN_HOST)
        assert not addon._host_matches("unrelated.example.com")

        payload = {
            "code": 200,
            "data": {
                "list": [{
                    "apkPackageName": "fixture.app",
                    "apkVersion": 1,
                    "apkUrl": f"https://{CDN_HOST}/app-market/fixture.apk",
                    "sha256": "0" * 64,
                    "apkMd5": "0" * 32,
                }]
            },
        }
        flow = http.HTTPFlow(None, None)
        flow.request = http.Request.make(
            "POST", f"https://{API_HOST}/app-market/openserver/clientApi/v2/app/appList"
        )
        flow.response = http.Response.make(
            200, json.dumps(payload), {"content-type": "application/json;responseformat=1"}
        )
        addon.response(flow)
        result = json.loads(flow.response.get_text())
        entry = result["data"]["list"][0]
        assert entry["apkVersion"] > 1
        assert entry["sha256"] == SHA and entry["apkMd5"] == MD5
        assert "/app-market/fixture.apk" in addon._armed_paths
        assert any("[inject-update]" in message for message in messages)
        addon.done()
    print("PASS: captured store hosts pass gate and appList emits inject-update")


if __name__ == "__main__":
    main()
