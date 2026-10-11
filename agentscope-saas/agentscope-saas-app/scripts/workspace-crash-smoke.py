#!/usr/bin/env python3
"""Owned local JVM SIGKILL probes; no external model or sandbox deployment is contacted."""
import concurrent.futures
import http.client
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import uuid

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parents[2]
spec = importlib.util.spec_from_file_location("generation_smoke", SCRIPT_DIR / "session-generation-smoke.py")
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True, timeout=60).stdout.strip()


class OwnedApp:
    def __init__(self, suffix, port=18082, overrides=None):
        self.name = "chugou-crash-" + suffix
        self.process = None
        self.log = None
        self.boots = 0
        self.port = port
        self.overrides = dict(overrides or {})

    def boot(self):
        occupied = subprocess.run(["lsof", f"-tiTCP:{self.port}", "-sTCP:LISTEN"], capture_output=True, text=True)
        assert not occupied.stdout.strip(), "Refusing to replace an existing app listener"
        self.boots += 1
        path = Path("/private/tmp") / f"{self.name}-boot-{self.boots}.log"
        self.log = path.open("w")
        env = dict(os.environ)
        env.update({"APP_PORT": str(self.port), "SAAS_MODEL_TYPE": "scripted", "START_DOCKER_DEPS": "false",
                    "START_DOCKER_DAEMON": "false", "SAAS_RUNTIME_ARCHIVE_INLINE_MAX_BYTES": "256",
                    "SAAS_SANDBOX_ENABLED": "true", "SAAS_SANDBOX_TYPE": "opensandbox",
                    "SAAS_SANDBOX_OPENSANDBOX_API_BASE_URL": "http://127.0.0.1:18081",
                    "SAAS_SANDBOX_SNAPSHOT_BACKEND": "minio", "SAAS_SANDBOX_MINIO_ENDPOINT": "http://localhost:9000",
                    "SAAS_FILE_STORE_ENABLED": "true", "SAAS_FILE_STORE_BACKEND": "minio",
                    "SAAS_FILE_STORE_MINIO_ENDPOINT": "http://localhost:9000",
                    "SAAS_WORKSPACE_SOURCE_ENABLED": "true", "SAAS_WORKSPACE_ARCHIVE_ENABLED": "true",
                    "SAAS_FILE_STORE_PUBLICATION_RECOVERY_ENABLED": "true", "SAAS_WORKSPACE_PROVIDER_RETENTION_ENABLED": "true",
                    "SAAS_REDIS_ENABLED": "true", "SAAS_REDIS_URI": "redis://localhost:6379",
                    "SAAS_WORKSPACE_COMPLETION_HANDOVER_ENABLED": "true",
                    "SAAS_ORCHESTRATION_SCHEDULER_LEASE_SECONDS": "10",
                    "SAAS_ORCHESTRATION_SCHEDULER_HEARTBEAT_SECONDS": "2",
                    "SAAS_MODEL_STREAM_RECOVERY_MODE": "DURABLE",
                    "SAAS_ORCHESTRATION_ENABLED": "true", "SAAS_ORCHESTRATION_SCHEDULER_ENABLED": "true",
                    "SAAS_WORKSPACE_ARCHIVE_LEASE_SECONDS": "10",
                    "SAAS_WORKSPACE_ARCHIVE_RETRY_SECONDS": "1",
                    "SAAS_DB_URL": "jdbc:postgresql://localhost:5432/agentscope_saas_e2e?ApplicationName=" + self.name})
        env["SAAS_DB_ADMIN_URL"] = env["SAAS_DB_URL"]
        env.update(self.overrides)
        self.process = subprocess.Popen(["bash", str(SCRIPT_DIR / "start-opensandbox-local.sh")], cwd=ROOT,
                                        env=env, stdout=self.log, stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            assert self.process.poll() is None, ("App startup failed", str(path))
            try:
                connection = http.client.HTTPConnection("localhost", self.port, timeout=2)
                try:
                    connection.request("GET", "/actuator/health")
                    response = connection.getresponse()
                    status = response.status
                    response.read()
                finally:
                    connection.close()
                if status == 200:
                    return
            except (OSError, TimeoutError, http.client.IncompleteRead):
                pass
            time.sleep(0.5)
        raise AssertionError(("App startup timed out", str(path)))

    def stop(self, crash=False):
        if self.process is None:
            return
        if self.process.poll() is None:
            # This process group was created by this probe; never signal an ambient app PID.
            assert os.getpgid(self.process.pid) == self.process.pid
            os.killpg(self.process.pid, signal.SIGKILL if crash else signal.SIGTERM)
            try:
                self.process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=10)
        self.log.close()
        self.process = None
        h.wait_until(lambda: not subprocess.run(["lsof", f"-tiTCP:{self.port}", "-sTCP:LISTEN"], capture_output=True, text=True).stdout.strip(), timeout=30)

    def cancel_own_queries(self):
        h.pg(f"SELECT pg_cancel_backend(pid) FROM pg_stat_activity WHERE application_name = '{self.name}' AND pid <> pg_backend_pid()")


def scenario(archiving):
    suffix = uuid.uuid4().hex
    app = OwnedApp(suffix)
    fixture = "computed_crash_" + suffix
    installed = False
    stopped_minio = False
    agent = token = None
    try:
        app.boot()
        token = h.api("/api/auth/register", payload={"email": f"crash-{suffix}@e2e.test", "password": "test-" + suffix})["token"]
        agent = h.api("/api/agents", token, {"name": "CrashSmoke", "tools": ["execute"], "maxIters": 8,
                     "sysPrompt": "Execute exactly the requested command."}, expected=201)["id"]
        agent = str(uuid.UUID(agent))
        marker = "computed-crash-" + suffix
        command = f"sleep 8 && mkdir -p outputs && printf '%s\\n' {marker} > outputs/crash-result.txt && cat outputs/crash-result.txt && printf '%8192s' '' | tr ' ' x"
        paused = h.confirm(h.stream(agent, token, {"message": command}))
        run = str(uuid.UUID(paused["runId"]))
        session = str(uuid.UUID(paused["sessionId"]))
        org = str(uuid.UUID(h.pg(f"SELECT org_id FROM assistant_runs WHERE id = '{run}'")))
        phase = "AND EXISTS (SELECT 1 FROM run_attempts a WHERE a.run_id = NEW.run_id AND a.org_id = NEW.org_id AND a.status = 'ARCHIVING')" if archiving else ""
        h.pg(f"""CREATE FUNCTION {fixture}() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN IF NEW.org_id = '{org}'::uuid AND NEW.run_id = '{run}'::uuid {phase} THEN
            PERFORM pg_sleep(45); END IF; RETURN NEW; END $$;
          CREATE TRIGGER {fixture} BEFORE INSERT ON file_publications FOR EACH ROW EXECUTE FUNCTION {fixture}();""")
        installed = True
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            request = pool.submit(h.request, f"/api/agents/{agent}/chat/stream", token, paused, timeout=300)
            if archiving:
                h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND status = 'RUNNING' AND tool_name = 'execute'")) == 1)
                docker("stop", "saas-minio")
                stopped_minio = True
            target = "status = 'RUNNING' AND NOT prepared AND token IS NOT NULL" if archiving else "status = 'PENDING' AND prepared"
            h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM workspace_archive_jobs WHERE run_id = '{run}' AND {target}")) == 1, timeout=150)
            job = str(uuid.UUID(h.pg(f"SELECT id FROM workspace_archive_jobs WHERE run_id = '{run}'")))
            assert h.pg(f"SELECT body_id IS NULL AND result_json IS NOT NULL FROM workspace_archive_jobs WHERE id = '{job}'") == "t"
            saved = json.loads(h.pg(f"SELECT result_json FROM workspace_archive_jobs WHERE id = '{job}'"))
            expected_answer = "".join(block.get("text", "") for block in saved["answer"] if block.get("type") == "text")
            assert marker in expected_answer and "x" * 2048 in expected_answer
            assert int(h.pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND status = 'SUCCEEDED' AND tool_name = 'execute'")) == 1
            model_calls = int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'"))
            sandbox_leases = int(h.pg(f"SELECT count(*) FROM sandbox_leases WHERE run_id = '{run}'"))
            state = h.pg(f"SELECT status FROM run_attempts WHERE run_id = '{run}' ORDER BY attempt_no DESC LIMIT 1")
            assert state == ("ARCHIVING" if archiving else "RUNNING"), state
            if archiving:
                assert str(uuid.UUID(h.pg(f"SELECT token FROM workspace_archive_jobs WHERE id = '{job}'")))
                assert int(h.pg(f"SELECT count(*) FROM workspace_provider_retentions p JOIN workspace_archive_jobs j ON j.manifest_id = p.manifest_id WHERE j.id = '{job}' AND p.status = 'RETAINED'")) == 1
            app.stop(crash=True)
            try:
                status, raw = request.result(timeout=15)
                assert status != 200 or not any(e.get("type") == "RUN_FINISHED" for e in h.parse_events(raw)), raw[-200:]
            except (OSError, TimeoutError, http.client.IncompleteRead):
                pass
        print("  OK  owned JVM killed with durable " + ("claimed archive lease" if archiving else "pre-handover computed result"))
        app.cancel_own_queries()
        h.pg(f"DROP TRIGGER {fixture} ON file_publications; DROP FUNCTION {fixture}()")
        installed = False
        if stopped_minio:
            docker("start", "saas-minio")
            stopped_minio = False
        app.boot()
        h.wait_until(lambda: h.api(f"/api/agents/{agent}/runs/{run}", token)["status"] == "SUCCEEDED", timeout=180)
        assert h.pg(f"SELECT status FROM workspace_archive_jobs WHERE id = '{job}'") == "COMPLETED"
        assert h.pg(f"SELECT prepared FROM workspace_archive_jobs WHERE id = '{job}'") == "f"
        assert int(h.pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}'")) == 1
        assert int(h.pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND status = 'SUCCEEDED' AND tool_name = 'execute'")) == 1
        assert int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'")) == model_calls
        assert int(h.pg(f"SELECT count(*) FROM sandbox_leases WHERE run_id = '{run}'")) == sandbox_leases
        print("  OK  restart completes original attempt without model, tool or sandbox recreation")
        status, body = h.request(f"/api/agents/{agent}/workspace/file/download?path=outputs%2Fcrash-result.txt", token)
        assert status == 200 and marker.encode() in body, (status, body[:200])
        assert int(h.pg(f"SELECT count(*) FROM chat_messages WHERE session_id = '{session}' AND role = 'assistant'")) == 1
        status, history = h.request(f"/api/agents/{agent}/sessions/{session}/turns/window", token)
        assert status == 200, (status, history[:200])
        answers = [item["content"] for item in json.loads(history)["items"] if item["role"] == "ASSISTANT"]
        assert answers == [expected_answer], ("Frozen answer mismatch", [len(value) for value in answers], len(expected_answer))
        assert int(h.pg(f"SELECT count(*) FROM file_publications WHERE run_id = '{run}' AND status = 'PUBLISHED'")) == 1
        print("  OK  exact output and full reply readable through user APIs, with one publication receipt")
        h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM runtime_body_fallbacks WHERE agent_id = '{agent}'")) == 0, timeout=120)
        h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM workspace_provider_retentions p JOIN workspace_archive_jobs j ON j.manifest_id = p.manifest_id WHERE j.id = '{job}' AND p.status <> 'CLEANED'")) == 0, timeout=120)
        print("  OK  source retention and outage body fallback drain after restart")
        assert h.pg("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'workspace_archive_jobs'::regclass") == "t"
        assert h.pg(f"SET ROLE app; SET app.current_org = '{uuid.uuid4()}'; SELECT count(*) FROM workspace_archive_jobs WHERE id = '{job}'").splitlines()[-1] == "0"
        print("  OK  prepared/claimed result stays PostgreSQL FORCE RLS isolated")
    finally:
        if app.process is None:
            app.cancel_own_queries()
        if installed:
            app.cancel_own_queries()
            h.pg(f"DROP TRIGGER IF EXISTS {fixture} ON file_publications; DROP FUNCTION IF EXISTS {fixture}()")
        if stopped_minio:
            docker("start", "saas-minio")
        if agent is not None and app.process is not None and app.process.poll() is None:
            status, _ = h.request(f"/api/agents/{agent}", token, method="DELETE")
            assert status in (200, 204, 404), ("Agent cleanup", status)
        app.stop()


def main():
    assert h.BASE == "http://localhost:18082", "This crash probe owns localhost:18082 only"
    assert docker("inspect", "--format", "{{.State.Running}}", "saas-minio") == "true"
    scenario(False)
    scenario(True)
    print("=== Workspace crash smoke: PASS=10 FAIL=0 ===")


if __name__ == "__main__":
    main()
