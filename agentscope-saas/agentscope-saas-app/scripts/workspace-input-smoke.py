#!/usr/bin/env python3
"""Local immutable attachment version recovery and reuse of generated output."""
import importlib.util
import json
from pathlib import Path
import urllib.parse
import urllib.request
import uuid

spec = importlib.util.spec_from_file_location("generation_smoke", Path(__file__).with_name("session-generation-smoke.py"))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def upload(agent, token, name, content):
    boundary = "fixture-" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
            "Content-Type: text/plain\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(h.BASE + f"/api/agents/{agent}/workspace/file/upload", data=body,
                                 headers={"Authorization": "Bearer " + token,
                                          "Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(req, timeout=120) as response:
        assert response.status == 201
        return json.loads(response.read())


def attachment(file):
    return {"path": file["path"], "name": file["path"].split("/")[-1],
            "sizeBytes": file["sizeBytes"], "versionId": file["versionId"]}


def completed(agent, token, events):
    for _ in range(3):
        assert not any(h.is_error(event) for event in events), events[-4:]
        paused = [e for e in events if e.get("name") == "require_user_confirm"]
        if not paused:
            assert any(e.get("type") == "RUN_FINISHED" for e in events), events[-4:]
            return events
        events = h.stream(agent, token, h.confirm(events))
    raise AssertionError("Confirmation did not complete")


def download(agent, token, path):
    status, data = h.request(f"/api/agents/{agent}/workspace/file/download?" + urllib.parse.urlencode({"path": path}), token)
    assert status == 200, (status, data[:100])
    return data


def main():
    assert urllib.parse.urlparse(h.BASE).hostname in {"localhost", "127.0.0.1"}, "Local fixture only"
    suffix = uuid.uuid4().hex
    auth = h.api("/api/auth/register", payload={"email": f"input-{suffix}@e2e.test", "password": "test-" + suffix})
    token = auth["token"]
    agent = h.api("/api/agents", token, {"name": "PinnedInputSmoke", "sysPrompt": "Execute the requested command exactly.",
                                       "tools": ["execute"], "maxIters": 8}, expected=201)["id"]
    try:
        original_marker = ("original-" + suffix).encode()
        original = original_marker + b"\n" + b"x" * (5 * 1024 * 1024 - len(original_marker) - 1)
        file = upload(agent, token, "pinned-" + suffix + ".txt", original)
        print("  OK  real 5 MiB attachment uploaded with an immutable version")
        command = f"mkdir -p outputs && head -c 64 {file['path']} > outputs/pinned-result.txt && cat outputs/pinned-result.txt"
        paused = h.confirm(h.stream(agent, token, {"message": command, "attachments": [attachment(file)]}))
        run = str(uuid.UUID(paused["runId"]))
        session = str(uuid.UUID(paused["sessionId"]))
        assert int(h.pg(f"SELECT count(*) FROM file_attachments a JOIN assistant_runs r ON r.trigger_message_id = a.message_id AND r.org_id = a.org_id WHERE r.id = '{run}' AND a.file_version_id = '{uuid.UUID(file['versionId'])}' AND a.logical_path = '{file['path']}'")) == 1
        newer = "replacement-" + suffix
        h.api(f"/api/agents/{agent}/workspace/file?" + urllib.parse.urlencode({"path": file["path"]}), token,
              {"content": newer}, method="PUT")
        versions = h.api(f"/api/agents/{agent}/workspace/file/versions?" + urllib.parse.urlencode({"path": file["path"]}), token)
        # The confirmation request deliberately carries the current version; it must not rebind the Run.
        if isinstance(versions, list) and versions:
            latest = dict(file, versionId=versions[0]["id"])
            paused["attachments"] = [attachment(latest)]
        completed(agent, token, h.stream(agent, token, paused))
        assert original_marker in download(agent, token, "outputs/pinned-result.txt")
        print("  OK  confirmation still executes against the originally bound 5 MiB version")
        assert download(agent, token, file["path"]) == newer.encode()
        print("  OK  release cannot overwrite the newer employee upload")
        output_version = str(uuid.UUID(h.pg(f"SELECT v.id FROM files f JOIN file_versions v ON v.id = f.current_version_id JOIN assistant_runs r ON r.user_id = f.user_id AND r.org_id = f.org_id WHERE r.id = '{run}' AND f.logical_path = 'outputs/pinned-result.txt'")))
        output = {"path": "outputs/pinned-result.txt", "versionId": output_version, "sizeBytes": 64}
        next_command = "mkdir -p outputs && head -c 64 outputs/pinned-result.txt > outputs/from-prior-output.txt && cat outputs/from-prior-output.txt"
        completed(agent, token, h.stream(agent, token, {"sessionId": session, "message": next_command, "attachments": [attachment(output)]}))
        assert original_marker in download(agent, token, "outputs/from-prior-output.txt")
        assert int(h.pg(f"SELECT count(*) FROM assistant_runs WHERE session_id = '{session}'")) == 2
        print("  OK  generated output becomes an explicit input in a fresh Run")
        assert original_marker in download(agent, token, "outputs/pinned-result.txt")
        print("=== Workspace input smoke: PASS=4 FAIL=0 ===")
    finally:
        status, _ = h.request(f"/api/agents/{agent}", token, method="DELETE")
        assert status in (200, 204, 404), ("Agent cleanup", status)


if __name__ == "__main__":
    main()
