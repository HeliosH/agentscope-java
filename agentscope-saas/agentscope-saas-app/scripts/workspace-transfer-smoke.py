#!/usr/bin/env python3
"""Local stream/range/concurrent transfer gate against real PG/MinIO/OpenSandbox."""
import concurrent.futures
import hashlib
import importlib.util
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location("inputs", Path(__file__).with_name("workspace-input-smoke.py"))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def request(agent, token, path, range_header=None):
    headers = {"Authorization": "Bearer " + token}
    if range_header:
        headers["Range"] = range_header
    url = h.h.BASE + f"/api/agents/{agent}/workspace/file/download?" + urllib.parse.urlencode({"path": path})
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120)


def digest_download(agent, token, path, size, sha):
    with request(agent, token, path) as response:
        assert response.status == 200
        assert int(response.headers["Content-Length"]) == size
        digest, total = hashlib.sha256(), 0
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            total += len(chunk)
        assert total == size and digest.hexdigest() == sha


def main():
    assert urllib.parse.urlparse(h.h.BASE).hostname in {"localhost", "127.0.0.1"}
    suffix = uuid.uuid4().hex
    large = os.environ.get("TRANSFER_SMOKE_LARGE", "false") == "true"
    mib = 33 if large else 31
    auth = h.h.api("/api/auth/register", payload={"email": f"transfer-{suffix}@e2e.test", "password": "test-" + suffix})
    token = auth["token"]
    agent = h.h.api("/api/agents", token, {"name": "StreamingTransferSmoke", "sysPrompt": "Execute the requested command exactly.",
                                        "tools": ["execute"], "maxIters": 8}, expected=201)["id"]
    try:
        data = ("stream-" + suffix + "\n").encode()
        data += b"x" * (mib * 1024 * 1024 - len(data))
        sha = hashlib.sha256(data).hexdigest()
        uploaded = h.upload(agent, token, "stream-" + suffix + ".txt", data)
        assert uploaded["sizeBytes"] == len(data) and uploaded["sha256"] == sha
        print(f"  OK  {mib} MiB stream upload, immutable length and SHA-256")
        if not large:
            try:
                h.upload(agent, token, "rejected-" + suffix + ".txt", b"x" * (33 * 1024 * 1024))
                raise AssertionError("Default 33 MiB upload was accepted")
            except urllib.error.HTTPError as rejected:
                assert rejected.code == 413, rejected.code
                rejected.close()
            print("  OK  default 33 MiB upload is explicitly rejected")
        else:
            print("  OK  configured 64 MiB policy admits a formerly rejected 33 MiB file")
        command = f"mkdir -p outputs && cp {uploaded['path']} outputs/stream-result.txt && sha256sum outputs/stream-result.txt"
        h.completed(agent, token, h.h.stream(agent, token, {"message": command, "attachments": [h.attachment(uploaded)]}))
        print("  OK  frozen input hydrated and generated output archived by the real sandbox")
        digest_download(agent, token, "outputs/stream-result.txt", len(data), sha)
        print("  OK  streamed output download has the exact complete length and SHA-256")
        with request(agent, token, "outputs/stream-result.txt", "bytes=7-26") as response:
            assert response.status == 206
            assert response.headers["Content-Range"] == f"bytes 7-26/{len(data)}"
            assert response.read() == data[7:27]
        try:
            request(agent, token, "outputs/stream-result.txt", f"bytes={len(data)}-")
            raise AssertionError("Unsatisfiable range was accepted")
        except urllib.error.HTTPError as invalid:
            assert invalid.code == 416 and invalid.headers["Content-Range"] == f"bytes */{len(data)}"
            invalid.close()
        print("  OK  authorized Range and 416 responses")
        with request(agent, token, "outputs/stream-result.txt") as response:
            assert response.read(4096) == data[:4096]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
            jobs = [workers.submit(digest_download, agent, token, "outputs/stream-result.txt", len(data), sha) for _ in range(4)]
            for job in jobs:
                job.result(timeout=150)
        print("  OK  cancelled download releases admission; four concurrent downloads finish intact")
        status, preview = h.h.request(f"/api/agents/{agent}/workspace/file?path=outputs%2Fstream-result.txt", token)
        assert status == 200 and b"file too large to display" in preview
        h.h.api(f"/api/agents/{agent}/workspace/file?" + urllib.parse.urlencode({"path": uploaded["path"]}),
                token, {"content": "changed"}, method="PUT")
        h.h.api(f"/api/agents/{agent}/workspace/file/version/{uploaded['versionId']}/restore", token, method="POST")
        digest_download(agent, token, uploaded["path"], len(data), sha)
        versions = h.h.api(f"/api/agents/{agent}/workspace/file/versions?" + urllib.parse.urlencode({"path": uploaded["path"]}), token)
        assert len(versions) == 3 and versions[0]["current"] and versions[0]["sha256"] == sha
        print("  OK  preview declines oversized text; historical version restores and downloads intact")
        print("=== Workspace streaming transfer smoke: PASS=7 FAIL=0 ===")
    finally:
        status, _ = h.h.request(f"/api/agents/{agent}", token, method="DELETE")
        assert status in (200, 204, 404)


if __name__ == "__main__":
    main()
