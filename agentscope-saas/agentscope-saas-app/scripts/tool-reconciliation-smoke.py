#!/usr/bin/env python3
"""Real PG/OpenSandbox/HTTP/browser gate with explicitly seeded unknown-outcome facts."""
import importlib.util
import json
import os
import signal
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

scripts = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("owned", scripts / "workspace-crash-smoke.py")
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)
h = owned.h


def main():
    suffix = uuid.uuid4().hex
    database = "chugou_reconciliation_ui_" + suffix
    bucket = "chugou-reconciliation-" + suffix
    mc_config = "/tmp/chugou-reconciliation-" + suffix
    assert h.BASE == "http://localhost:18082"
    url = f"jdbc:postgresql://localhost:5432/{database}?ApplicationName=reconcile-{suffix}"
    app = owned.OwnedApp("reconciliation-" + suffix, overrides={
        "SAAS_DB_URL": url, "SAAS_DB_ADMIN_URL": url,
        "SAAS_AGENT_MEMORY_HOOKS_ENABLED": "false", "SAAS_ORCHESTRATION_SCHEDULER_ENABLED": "false",
        "SAAS_MODEL_STREAM_RECOVERY_MODE": "REQUEST_ONLY",
        "SAAS_FILE_STORE_MINIO_BUCKET": bucket, "SAAS_SANDBOX_MINIO_BUCKET": bucket,
    })

    def pg(sql):
        return subprocess.run(["docker", "exec", "saas-pg", "psql", "-U", "agentscope", "-d", database,
                               "-At", "-v", "ON_ERROR_STOP=1", "-c", sql],
                              check=True, capture_output=True, text=True, timeout=20).stdout.strip()

    h.pg = pg
    created = False
    owned_bucket = False
    token = agent = None
    try:
        owned.docker("exec", "saas-minio", "mc", "--config-dir", mc_config, "alias", "set", "owned",
                     "http://127.0.0.1:9000", "minioadmin", "minioadmin")
        existing = subprocess.run(["docker", "exec", "saas-minio", "mc", "--config-dir", mc_config,
                                   "stat", "owned/" + bucket], capture_output=True, timeout=20)
        assert existing.returncode != 0, "Refusing to reuse an existing verification bucket"
        owned_bucket = True
        owned.docker("exec", "saas-pg", "createdb", "-U", "agentscope", database)
        created = True
        app.boot()
        password = "test-" + suffix
        email = f"reconciliation-{suffix}@e2e.test"
        admin_email = f"reconciliation-admin-{suffix}@e2e.test"
        member = h.api("/api/auth/register", payload={"email": email, "password": password})
        token, org = member["token"], str(uuid.UUID(member["orgId"]))
        admin = h.api("/api/auth/register", payload={"email": admin_email, "password": password})
        actor = str(uuid.UUID(admin["userId"]))
        pg(f"UPDATE users SET role = 'admin' WHERE id = '{actor}' AND org_id = '{org}'")
        admin_token = h.api("/api/auth/login", payload={"email": admin_email, "password": password})["token"]
        other = h.api("/api/auth/register", payload={"email": f"reconciliation-other-{suffix}@e2e.test", "password": password})["token"]
        assert int(pg(f"SELECT count(*) FROM model_definitions WHERE org_id = '{org}' AND enabled")) == 0
        agent = str(uuid.UUID(h.api("/api/agents", token, {
            "name": "ReconciliationSmoke", "tools": ["execute"], "maxIters": 8,
            "sysPrompt": "Execute exactly the requested command.",
        }, expected=201)["id"]))
        marker = "real-output-" + suffix
        command = f"mkdir -p outputs && printf '%s\\n' {marker} > outputs/reconciliation-proof.txt && cat outputs/reconciliation-proof.txt"
        confirmed = h.confirm(h.stream(agent, token, {"message": command}))
        events = h.stream(agent, token, confirmed)
        run = str(uuid.UUID(confirmed["runId"]))
        assert marker in json.dumps(events)
        h.wait_until(lambda: h.api(f"/api/agents/{agent}/runs/{run}", token)["status"] == "SUCCEEDED")
        status, body = h.request(f"/api/agents/{agent}/workspace/file/download?" + urllib.parse.urlencode({"path": "outputs/reconciliation-proof.txt"}), token)
        assert status == 200 and marker.encode() in body
        print("  OK  real task executes in OpenSandbox and publishes an authorized downloadable result", flush=True)

        paused = h.confirm(h.stream(agent, token, {"message": "printf '%s\\n' pending-reconciliation"}))
        run, session = str(uuid.UUID(paused["runId"])), str(uuid.UUID(paused["sessionId"]))
        # Only owned fault-fixture facts are inserted; provider response loss is tested separately.
        for index in range(28):
            operation, invocation = str(uuid.uuid4()), str(uuid.uuid4())
            call = "fixture-" + str(index)
            pg(f"""INSERT INTO tool_operations(id,org_id,run_id,task_id,agent_run_id,attempt_id,operation_key,
                 invocation_id,step_id,tool_call_id,tool_name,input_hash,input_json,retry_safety,status,
                 error_type,error_message,version)
                 SELECT '{operation}',r.org_id,r.id,t.id,a.id,p.id,r.id::text || ':{call}',
                   '{invocation}','fault-fixture','{call}','external_fixture','{'a' * 64}','{{}}','NEVER',
                   'OUTCOME_UNKNOWN','fixture.ResponseLost','Owned synthetic unknown-outcome fact',2
                 FROM assistant_runs r JOIN task_nodes t ON t.run_id = r.id AND t.parent_id IS NULL
                 JOIN agent_runs a ON a.run_id = r.id AND a.task_id = t.id AND a.parent_agent_run_id IS NULL
                 JOIN run_attempts p ON p.run_id = r.id AND p.task_id = t.id AND p.agent_run_id = a.id
                 WHERE r.id = '{run}' AND r.org_id = '{org}' AND p.status = 'RUNNING'""")
        path = f"/api/agents/{agent}/runs/{run}/operations"
        page = h.api(path + "?limit=25", token)
        assert len(page["items"]) == 25 and page["nextCursor"]
        next_page = h.api(path + "?limit=25&after=" + page["nextCursor"], token)
        assert len(next_page["items"]) == 3 and not next_page["nextCursor"]
        assert not set(row["id"] for row in page["items"]) & set(row["id"] for row in next_page["items"])
        operation = page["items"][0]["id"]
        assert h.request(path, other)[0] == 404
        assert h.request("/api/admin/tool-operations", token)[0] == 403
        assert h.request("/api/admin/tool-operations")[0] == 401
        assert pg("SELECT has_table_privilege('app','tool_operation_resolutions','UPDATE') OR "
                  "has_table_privilege('app','tool_operation_resolutions','DELETE') OR "
                  "has_table_privilege('app','tool_operation_replay_permits','UPDATE')") == "f"
        assert pg("SELECT bool_and(relrowsecurity AND relforcerowsecurity) FROM pg_class WHERE relname IN "
                  "('tool_operation_resolutions','tool_operation_replay_permits')") == "t"
        print("  OK  real HTTP cursor pagination, employee isolation, admin authorization and immutable RLS tables", flush=True)
        env = {**os.environ, "BASE_URL": h.BASE, "RECONCILIATION_AGENT_ID": agent,
               "RECONCILIATION_RUN_ID": run, "RECONCILIATION_SESSION_ID": session,
               "RECONCILIATION_OPERATION_ID": operation, "RECONCILIATION_EMAIL": email,
               "RECONCILIATION_ADMIN_EMAIL": admin_email, "RECONCILIATION_PASSWORD": password}
        browser = subprocess.Popen(["node", str(scripts / "tool-reconciliation-browser-smoke.cjs")],
                                   env=env, start_new_session=True)
        try:
            assert browser.wait(timeout=240) == 0, "Real browser verification failed"
        finally:
            if browser.poll() is None:
                assert os.getpgid(browser.pid) == browser.pid
                os.killpg(browser.pid, signal.SIGTERM)
                try:
                    browser.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(browser.pid, signal.SIGKILL)
                    browser.wait(timeout=10)
        assert int(pg(f"SELECT count(*) FROM tool_operation_resolutions WHERE operation_id = '{operation}'")) == 2
        assert pg(f"SELECT status || ':' || version FROM tool_operations WHERE id = '{operation}'") == "OUTCOME_UNKNOWN:2"
        assert pg(f"SELECT decision FROM tool_operation_resolutions WHERE operation_id = '{operation}' ORDER BY sequence_no DESC LIMIT 1") == "CONFIRMED_SUCCESS"
        assert int(pg(f"SELECT count(*) FROM tool_operation_replay_permits WHERE operation_id = '{operation}'")) == 0
        print("  OK  browser ambiguous-response retry appended once; success preserved the original unknown operation", flush=True)
        pg(f"UPDATE users SET role = 'member' WHERE id = '{actor}' AND org_id = '{org}'")
        assert h.request("/api/admin/tool-operations", admin_token)[0] == 403
        print("  OK  stale administrator JWT is denied after live DB role revocation", flush=True)
        print("=== Tool reconciliation PG/OpenSandbox/HTTP/browser smoke: PASS=5 FAIL=0 ===", flush=True)
    finally:
        try:
            if token and agent and app.process and app.process.poll() is None:
                status, _ = h.request(f"/api/agents/{agent}", token, method="DELETE")
                assert status in (200, 204, 404), ("Owned agent cleanup", status)
                h.wait_until(lambda: int(pg(f"SELECT count(*) FROM sandbox_runtime_resources WHERE agent_id = '{agent}' AND status <> 'RELEASED'")) == 0, timeout=60)
                assert int(pg(f"SELECT count(*) FROM assistant_runs WHERE agent_id = '{agent}'")) == 0
        finally:
            app.stop()
            if owned_bucket:
                exists = subprocess.run(["docker", "exec", "saas-minio", "mc", "--config-dir", mc_config,
                                         "stat", "owned/" + bucket], capture_output=True, timeout=20)
                if exists.returncode == 0:
                    owned.docker("exec", "saas-minio", "mc", "--config-dir", mc_config, "rb", "--force", "owned/" + bucket)
                owned.docker("exec", "saas-minio", "rm", "-r", mc_config)
            if created:
                h.wait_until(lambda: int(owned.docker("exec", "saas-pg", "psql", "-U", "agentscope", "-d", "postgres", "-At", "-c",
                    f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{database}'")) == 0, timeout=30)
                owned.docker("exec", "saas-pg", "dropdb", "-U", "agentscope", database)


if __name__ == "__main__":
    main()
