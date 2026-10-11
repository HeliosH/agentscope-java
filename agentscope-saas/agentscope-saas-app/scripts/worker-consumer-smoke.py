#!/usr/bin/env python3
"""Two owned local JVMs and a local model HTTP fixture; no external model is contacted."""
import http.server
import importlib.util
import json
import os
import select
from pathlib import Path
import socket
import subprocess
import threading
import time
import urllib.request
import urllib.error
import hashlib
import uuid

scripts = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("owned", scripts / "workspace-crash-smoke.py")
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)
h = owned.h


class ModelFixture(http.server.BaseHTTPRequestHandler):
    calls = {}
    lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        text = json.dumps(payload.get("messages", []), ensure_ascii=False)
        marker = next((key for key in self.calls if key in text), None)
        if marker is None:
            self.send_error(400, "Unknown local model fixture")
            return
        with self.lock:
            config = self.calls[marker]
            config["count"] += 1
            count, mode = config["count"], config["mode"]
        if mode == "unauthorized":
            body = json.dumps({"error": {"message": "fixture denied", "type": "auth", "code": "invalid_key"}}).encode()
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        broken = mode == "always" or count == 1
        chunk = {"id": "fixture-" + marker, "object": "chat.completion.chunk", "model": "local-recovery-fixture",
                 "choices": [{"index": 0, "delta": {"role": "assistant", "content": "partial" if broken else "Recovered " + marker},
                              "finish_reason": None if broken else "stop"}]}
        body = ("data: " + json.dumps(chunk) + "\n\n" + ("" if broken else "data: [DONE]\n\n")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body) + (128 if broken else 0)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.wfile.flush()
        if broken:
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()
        self.close_connection = True


def main():
    assert h.BASE == "http://localhost:18082", "Owns localhost ports 18082 and 18083 only"
    suffix = uuid.uuid4().hex
    deployment = "consumer-smoke-" + suffix
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ModelFixture)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    model_url = f"http://127.0.0.1:{server.server_port}/v1"
    use_sandbox = os.environ.get("WORKER_CONSUMER_SANDBOX", "true") == "true"
    common = {"SAAS_MODEL_TYPE": "gateway", "SAAS_MODEL_BASE_URL": model_url,
              "SAAS_MODEL_API_KEY": "local-fixture-not-a-credential", "SAAS_MODEL_NAME": "local-recovery-fixture",
              "SAAS_MODEL_DEFAULT_ID": "local-recovery-fixture", "SAAS_MODEL_DISPLAY_NAME": "Local recovery fixture",
              "SAAS_AGENT_MEMORY_HOOKS_ENABLED": "false", "SAAS_MODEL_TRAFFIC_ENABLED": "false",
              "SAAS_ORCHESTRATION_CONSUMER_MODE": "EXTERNAL", "SAAS_ORCHESTRATION_DEPLOYMENT_SCOPE": deployment,
              "SAAS_ORCHESTRATION_CONSUMER_HEARTBEAT_TTL_SECONDS": "6",
              "SAAS_ORCHESTRATION_SCHEDULER_RECOVERY_FIXED_DELAY_SECONDS": "2",
              "SAAS_MODEL_STREAM_RECOVERY_INITIAL_BACKOFF_MILLIS": "100",
              "SAAS_MODEL_STREAM_RECOVERY_MAX_BACKOFF_MILLIS": "200",
              "SAAS_SANDBOX_ENABLED": "true" if use_sandbox else "false"}
    web = owned.OwnedApp("consumer-web-" + suffix, overrides={**common, "SAAS_ORCHESTRATION_SCHEDULER_ENABLED": "false"})
    worker = owned.OwnedApp("consumer-worker-" + suffix, port=18083, overrides=common)
    verify_policy = os.environ.get("WORKER_CONSUMER_POLICY", "false") == "true"
    awake = subprocess.Popen(["caffeinate", "-i", "-w", str(os.getpid())])
    agent = token = None
    ui = None
    try:
        web.boot()
        auth = h.api("/api/auth/register", payload={"email": f"consumer-{suffix}@e2e.test", "password": "test-" + suffix})
        token, user = auth["token"], str(uuid.UUID(auth["userId"]))
        org = str(uuid.UUID(auth["orgId"]))
        # Guard against inherited organization-managed routes reaching an external endpoint.
        assert int(h.pg(f"SELECT count(*) FROM model_definitions WHERE org_id = '{org}' AND enabled")) == 0
        agent = h.api("/api/agents", token, {"name": "ConsumerSmoke", "tools": [], "maxIters": 4,
                                            "sysPrompt": "Return the model's answer."}, expected=201)["id"]

        def start(mode, attachments=None):
            marker = "consumer-" + mode + "-" + uuid.uuid4().hex
            ModelFixture.calls[marker] = {"count": 0, "mode": mode}
            events = h.stream(agent, token, {"message": marker, "modelId": "local-recovery-fixture",
                                            **({"attachments": attachments} if attachments else {})})
            event = next(e for e in events if e.get("type") == "RUN_STARTED")
            return marker, str(uuid.UUID(event["runId"])), events

        _, absent, events = start("always")
        assert h.api(f"/api/agents/{agent}/runs/{absent}", token)["status"] == "FAILED"
        assert not any(e.get("name") == "run_recovery_scheduled" for e in events)
        count = int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{absent}' AND purpose = 'REASONING'"))
        assert count == 3, ("Expected model recovery, not an infrastructure failure", count,
                            h.api(f"/api/agents/{agent}/runs/{absent}", token), events[-3:])
        print("  OK  external-consumer flag without registration never promises background recovery", flush=True)

        worker.boot()
        h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM worker_heartbeats WHERE deployment_scope = '{deployment}' AND expires_at > CURRENT_TIMESTAMP")) == 1)
        worker.stop()
        if verify_policy:
            worker.overrides.update({"SAAS_FILE_STORE_MAX_FILE_BYTES": "1048576",
                                     "SAAS_MODEL_STREAM_RECOVERY_MAX_ATTEMPTS": "1",
                                     "SAAS_MODEL_STREAM_RECOVERY_ENABLED": "false"})
        marker, queued, events = start("once")
        scheduled = next(e for e in events if e.get("name") == "run_recovery_scheduled")
        assert scheduled["value"]["state"] == "WAITING_FOR_WORKER"
        state = h.api(f"/api/agents/{agent}/runs/{queued}/recovery", token)
        assert state["state"] == "WAITING_FOR_WORKER" and state["recoveryCount"] == 1
        assert h.api(f"/api/agents/{agent}/runs/{queued}", token)["status"] == "RUNNING"
        assert ModelFixture.calls[marker]["count"] == 1
        print("  OK  registered but stopped worker leaves a durable queue with truthful waiting state", flush=True)

        other = h.api("/api/auth/register", payload={"email": f"consumer-other-{suffix}@e2e.test", "password": "test-" + suffix})["token"]
        assert h.request(f"/api/agents/{agent}/runs/{queued}/recovery", other)[0] == 404
        denied = h.pg("SELECT has_table_privilege('app','worker_heartbeats','SELECT') OR has_table_privilege('app','worker_heartbeats','INSERT')")
        assert denied == "f"
        print("  OK  employee recovery scope is isolated; tenant DB role cannot enumerate or forge consumer heartbeats", flush=True)

        worker.boot()
        h.wait_until(lambda: h.api(f"/api/agents/{agent}/runs/{queued}", token)["status"] == "SUCCEEDED", timeout=150)
        assert int(h.pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{queued}'")) == 2
        assert int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{queued}' AND purpose = 'REASONING'")) == 2
        session = h.api(f"/api/agents/{agent}/runs/{queued}", token)["sessionId"]
        status, history = h.request(f"/api/agents/{agent}/sessions/{session}/turns/window", token)
        assert status == 200 and ("Recovered " + marker).encode() in history
        assert ModelFixture.calls[marker]["count"] == 2
        print("  OK  independent worker resumes original task and final reply is available through web history", flush=True)

        marker, exhausted, events = start("always")
        h.wait_until(lambda: h.api(f"/api/agents/{agent}/runs/{exhausted}", token)["status"] == "FAILED", timeout=120)
        assert int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{exhausted}' AND purpose = 'REASONING'")) == 3
        assert ModelFixture.calls[marker]["count"] == 3
        assert h.api(f"/api/agents/{agent}/runs/{exhausted}", token)["failureCode"] == "MODEL_RECOVERY_EXHAUSTED"
        print("  OK  total model attempts remain three across HTTP and worker boundaries, without hidden SDK retries", flush=True)

        marker, denied_run, events = start("unauthorized")
        assert h.api(f"/api/agents/{agent}/runs/{denied_run}", token)["status"] == "FAILED"
        assert ModelFixture.calls[marker]["count"] == 1
        assert not any(e.get("name") == "run_recovery_scheduled" for e in events)
        print("  OK  permanent provider 401 is terminal and never queues a retry", flush=True)

        worker.stop()
        marker, cancelled, events = start("once")
        cancel = urllib.request.Request(h.BASE + f"/api/agents/{agent}/runs/{cancelled}/cancel", method="POST",
                headers={"Authorization": "Bearer " + token, "Idempotency-Key": "cancel-" + cancelled})
        with urllib.request.urlopen(cancel, timeout=30) as response:
            assert response.status == 200
        worker.boot()
        time.sleep(4)
        assert h.api(f"/api/agents/{agent}/runs/{cancelled}", token)["status"] == "CANCELLED"
        assert ModelFixture.calls[marker]["count"] == 1
        assert h.api(f"/api/agents/{agent}/runs/{cancelled}/recovery", token)["state"] == "CANCELLED"
        print("  OK  cancelled queued recovery cannot be claimed after worker restart", flush=True)
        if os.environ.get("WORKER_CONSUMER_BROWSER", "false") == "true":
            worker.stop()
            marker = "consumer-browser-" + uuid.uuid4().hex
            ModelFixture.calls[marker] = {"count": 0, "mode": "once"}
            env = {**os.environ, "BASE_URL": h.BASE, "RECOVERY_AGENT_ID": agent, "RECOVERY_MARKER": marker,
                   "RECOVERY_EMAIL": f"consumer-{suffix}@e2e.test", "RECOVERY_PASSWORD": "test-" + suffix}
            ui = subprocess.Popen(["node", str(scripts / "worker-recovery-browser-smoke.cjs")], env=env,
                                  stdout=subprocess.PIPE, text=True)
            deadline = time.monotonic() + 150
            ready = None
            while time.monotonic() < deadline and ui.poll() is None:
                if select.select([ui.stdout], [], [], 0.2)[0]:
                    line = ui.stdout.readline().strip()
                    if line.startswith('{'):
                        ready = json.loads(line)
                        break
                    if line:
                        print(line, flush=True)
            assert ready and ready.get("ready"), "Browser did not reach the real recovery queue"
            worker.boot()
            assert ui.wait(timeout=150) == 0
            print(ui.stdout.read().strip(), flush=True)
            browser_run = str(uuid.UUID(ready["runId"]))
            assert h.api(f"/api/agents/{agent}/runs/{browser_run}", token)["status"] == "SUCCEEDED"
            assert ModelFixture.calls[marker]["count"] == 2
            print("  OK  actual browser-to-web-to-PG-to-worker recovery and automatic final delivery", flush=True)
        if verify_policy:
            input_spec = importlib.util.spec_from_file_location("policy_inputs", scripts / "workspace-input-smoke.py")
            inputs = importlib.util.module_from_spec(input_spec)
            input_spec.loader.exec_module(inputs)
            data = b"accepted-policy-file\n" + b"x" * (5 * 1024 * 1024)
            if use_sandbox:
                uploaded = inputs.upload(agent, token, "accepted-policy-" + suffix + ".txt", data)
                worker.stop()
                marker, frozen_run, _ = start("once", [inputs.attachment(uploaded)])
                assert int(h.pg(f"SELECT max_file_bytes FROM run_execution_policies WHERE org_id = '{org}' AND run_id = '{frozen_run}'")) == 33554432
                assert int(h.pg(f"SELECT recovery_max_attempts FROM run_execution_policies WHERE org_id = '{org}' AND run_id = '{frozen_run}'")) == 3
                worker.boot()
                h.wait_until(lambda: h.api(f"/api/agents/{agent}/runs/{frozen_run}", token)["status"] == "SUCCEEDED", timeout=180)
                assert ModelFixture.calls[marker]["count"] == 2
                assert hashlib.sha256(inputs.download(agent, token, uploaded["path"])).hexdigest() == hashlib.sha256(data).hexdigest()
                print("  OK  old task restores a 5 MiB pinned input on a worker with a new 1 MiB admission limit", flush=True)
            web.stop()
            web.overrides.update({"SAAS_FILE_STORE_MAX_FILE_BYTES": "1048576",
                                  "SAAS_MODEL_STREAM_RECOVERY_MAX_ATTEMPTS": "1",
                                  "SAAS_MODEL_STREAM_RECOVERY_ENABLED": "false"})
            web.boot()
            if use_sandbox:
                assert hashlib.sha256(inputs.download(agent, token, uploaded["path"])).hexdigest() == hashlib.sha256(data).hexdigest()
            marker, new_run, events = start("always")
            assert h.api(f"/api/agents/{agent}/runs/{new_run}", token)["status"] == "FAILED"
            assert ModelFixture.calls[marker]["count"] == 1
            assert h.pg(f"SELECT recovery_enabled FROM run_execution_policies WHERE org_id = '{org}' AND run_id = '{new_run}'") == "f"
            try:
                inputs.upload(agent, token, "new-policy-" + suffix + ".txt", data[:1048577])
                raise AssertionError("New 1 MiB policy accepted a file one byte over its limit")
            except urllib.error.HTTPError as error:
                assert error.code == 413
                error.close()
            assert h.pg("SELECT has_table_privilege('app','run_execution_policies','UPDATE') OR has_table_privilege('app','run_execution_policies','DELETE')") == "f"
            isolated = h.pg(f"BEGIN; SET LOCAL ROLE app; SET LOCAL app.current_org = '{uuid.uuid4()}'; "
                            f"SELECT count(*) FROM run_execution_policies WHERE run_id = '{new_run}'; ROLLBACK;")
            assert "0" in isolated.splitlines(), isolated
            print("  OK  newly accepted tasks use the new file/retry policy; tenant role cannot change old policy records", flush=True)
        print(f"=== Worker consumer smoke ({'OpenSandbox' if use_sandbox else 'no sandbox'}): "
              f"PASS={(8 if ui else 7) + ((2 if use_sandbox else 1) if verify_policy else 0)} FAIL=0 ===", flush=True)
    finally:
        if ui is not None and ui.poll() is None:
            ui.terminate()
            ui.wait(timeout=15)
        if ui is not None:
            ui.stdout.close()
        worker.stop()
        if agent is not None and web.process is not None and web.process.poll() is None:
            assert h.request(f"/api/agents/{agent}", token, method="DELETE")[0] in (200, 204, 404)
        web.stop()
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        awake.terminate()
        awake.wait(timeout=10)


if __name__ == "__main__":
    main()
