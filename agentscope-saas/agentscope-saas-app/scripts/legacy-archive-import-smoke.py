#!/usr/bin/env python3
"""Owned local HTTP + PG/MinIO import/restart probe; never calls an external model/provider."""
import concurrent.futures
import hashlib
import importlib.util
import json
from pathlib import Path
import urllib.parse
import uuid

scripts = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("owned", scripts / "workspace-crash-smoke.py")
owned = importlib.util.module_from_spec(spec)
spec.loader.exec_module(owned)
h = owned.h
spec = importlib.util.spec_from_file_location("inputs", scripts / "workspace-input-smoke.py")
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)


def main():
    assert h.BASE == "http://localhost:18082", "Owns localhost port 18082 only"
    suffix = uuid.uuid4().hex
    app = owned.OwnedApp("legacy-" + suffix, overrides={
        "SAAS_SANDBOX_ENABLED": "false", "SAAS_MODEL_TYPE": "stub", "SAAS_MODEL_DEFAULT_ID": "local-legacy-stub",
        "SAAS_MODEL_NAME": "local-legacy-stub", "SAAS_AGENT_MEMORY_HOOKS_ENABLED": "false",
        "SAAS_FILE_STORE_GC_ENABLED": "false", "SAAS_RUNTIME_ARCHIVE_BODY_GC_ENABLED": "false",
        "SAAS_ORCHESTRATION_SCHEDULER_ENABLED": "false", "SAAS_MODEL_STREAM_RECOVERY_MODE": "REQUEST_ONLY",
        "SAAS_RUNTIME_ARCHIVE_LEGACY_IMPORT_ENABLED": "true",
        "SAAS_RUNTIME_ARCHIVE_LEGACY_IMPORT_MAX_BATCH_LINES": "2"})
    fixture = "legacy_crash_" + suffix
    installed = False
    agent = token = user = None

    def wait_for_owned_database_disconnect():
        # PG may finish the artificial sleep before noticing the killed client's socket closed.
        h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM pg_stat_activity WHERE application_name = '{app.name}'")) == 0, timeout=60)

    try:
        app.boot()
        email, password = f"legacy-{suffix}@e2e.test", "test-" + suffix
        auth = h.api("/api/auth/register", payload={"email": email, "password": password})
        token, user = auth["token"], str(uuid.UUID(auth["userId"]))
        org = str(uuid.UUID(auth["orgId"]))
        agent = str(uuid.UUID(h.api("/api/agents", token, {"name": "LegacyImportSmoke", "tools": [], "maxIters": 2}, expected=201)["id"]))
        assert int(h.pg(f"SELECT count(*) FROM model_definitions WHERE org_id = '{org}' AND enabled")) == 0
        events = h.stream(agent, token, {"message": "legacy smoke session", "modelId": "local-legacy-stub"})
        assert not any(h.is_error(e) for e in events), events[-3:]
        session = str(uuid.UUID(next(e["threadId"] for e in events if e.get("type") == "RUN_STARTED")))
        online_before = int(h.pg(f"SELECT count(*) FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id WHERE s.session_id = '{session}'"))
        source_entries = [{"type": "message", "id": "a", "role": "USER", "content": "same"},
                          {"type": "message", "id": "b", "parentId": "a", "role": "ASSISTANT", "content": "same"},
                          {"type": "message", "role": "TOOL", "toolCallId": "old-call", "content": "rendered old result"},
                          {"type": "summary", "id": "summary", "summary": "old compacted projection"}]
        content = ("\n".join(json.dumps(e) for e in source_entries) + "\n").encode()
        file = inputs.upload(agent, token, "legacy-" + suffix + ".log.jsonl", content)
        version = str(uuid.UUID(file["versionId"]))
        assert h.pg(f"SELECT storage_backend FROM file_versions WHERE id = '{version}'") == "minio"
        base = f"/api/admin/agents/{agent}/sessions/{session}/legacy-archive-imports"
        create = {"userId": user, "sourceVersionId": version}
        h.api(base, token, create, expected=403)
        h.pg(f"UPDATE users SET role = 'admin' WHERE id = '{user}' AND org_id = '{org}'")
        admin = h.api("/api/auth/login", payload={"email": email, "password": password})["token"]
        job = h.api(base, admin, create)
        job_id = str(uuid.UUID(job["id"]))
        assert h.api(base, admin, create)["id"] == job_id
        assert job["sourceSha256"] == hashlib.sha256(content).hexdigest()
        query = urllib.parse.urlencode({"userId": user})
        path = base + "/" + job_id
        h.api(path + "?" + urllib.parse.urlencode({"userId": str(uuid.uuid4())}), admin, expected=404)
        print("  OK  authenticated admin import pins owned MinIO version; repeat create is idempotent", flush=True)

        h.pg(f"""CREATE FUNCTION {fixture}() RETURNS trigger LANGUAGE plpgsql AS $$
          BEGIN IF NEW.id = '{job_id}'::uuid THEN PERFORM pg_sleep(45); END IF; RETURN NEW; END $$;
          CREATE TRIGGER {fixture} BEFORE UPDATE ON legacy_archive_imports FOR EACH ROW EXECUTE FUNCTION {fixture}();""")
        installed = True
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(h.request, path + "/advance?" + query, admin, {"expectedOffset": 0})
            h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM pg_stat_activity WHERE application_name = '{app.name}' AND wait_event = 'PgSleep'")) == 1, timeout=60)
            assert int(h.pg(f"SELECT count(*) FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id WHERE s.session_key = 'legacy:{job_id}'")) == 2
            assert int(h.pg(f"SELECT byte_offset FROM legacy_archive_imports WHERE id = '{job_id}'")) == 0
            app.stop(crash=True)
            try:
                pending.result(timeout=30)
            except (OSError, TimeoutError):
                pass
        wait_for_owned_database_disconnect()
        h.pg(f"DROP TRIGGER {fixture} ON legacy_archive_imports; DROP FUNCTION {fixture}()")
        installed = False
        assert int(h.pg(f"SELECT count(*) FROM legacy_archive_entries WHERE job_id = '{job_id}'")) == 0
        app.boot()
        first = h.api(path + "/advance?" + query, admin, {"expectedOffset": 0})
        assert first["lineNo"] == 2 and first["status"] == "READY"
        h.api(path + "/advance?" + query, admin, {"expectedOffset": 0}, expected=409)
        final = h.api(path + "/advance?" + query, admin, {"expectedOffset": first["byteOffset"]})
        assert final["status"] == "COMPLETE" and final["importedMessages"] == 3 and final["skippedEntries"] == 1
        assert final["byteOffset"] == len(content)
        assert h.api(path + "/advance?" + query, admin, {"expectedOffset": final["byteOffset"]})["lineNo"] == 4
        entries = h.api(path + "/entries?" + query + "&afterLine=2&limit=2", admin)
        assert [e["lineNo"] for e in entries] == [3, 4] and entries[0]["syntheticIdentity"] and entries[0]["toolCallId"] == "old-call"
        print("  OK  SIGKILL after source commit resumes cursor without duplicate messages; provenance paging exact", flush=True)

        assert int(h.pg(f"SELECT count(*) FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id WHERE s.session_key = 'legacy:{job_id}'")) == 3
        assert int(h.pg(f"SELECT count(*) FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id WHERE s.session_id = '{session}' AND s.agent_label <> 'legacy'")) == online_before
        assert int(h.pg(f"SELECT count(*) FROM tool_operations t JOIN assistant_runs r ON r.id = t.run_id WHERE r.session_id = '{session}'")) == 0
        assert inputs.download(agent, token, file["path"]) == content
        page = h.api(f"/api/agents/{agent}/sessions/{session}/runtime-messages?agentLabel=legacy&sessionKey=legacy:{job_id}&afterSeq=0&limit=2", token)
        assert len(page["items"]) == 2 and page["hasMore"]
        assert int(h.pg(f"SELECT count(*) FROM audit_logs WHERE org_id = '{org}' AND actor = '{user}' AND action = 'legacy_archive_import.advance' AND resource = '{job_id}'")) >= 1
        print("  OK  online stream untouched, no tools replayed, source bytes retained and admin audit recorded", flush=True)

        foreign = str(uuid.uuid4())
        assert h.pg(f"BEGIN; SET LOCAL ROLE app; SELECT set_config('app.current_org', '{foreign}', true); SELECT count(*) FROM legacy_archive_imports WHERE id = '{job_id}'; SELECT count(*) FROM legacy_archive_entries WHERE job_id = '{job_id}'; ROLLBACK;").splitlines()[-3:-1] == ["0", "0"]
        assert h.pg("SELECT count(*) FROM pg_class WHERE relname IN ('legacy_archive_imports','legacy_archive_entries') AND relrowsecurity AND relforcerowsecurity") == "2"
        h.api(f"/api/agents/{agent}/sessions/{session}", token, method="DELETE", expected=204)
        assert h.pg(f"SELECT count(*) FROM legacy_archive_imports WHERE id = '{job_id}'") == "0"
        assert h.pg(f"SELECT count(*) FROM legacy_archive_entries WHERE job_id = '{job_id}'") == "0"
        print("  OK  FORCE RLS isolation and session deletion release import/evidence pin", flush=True)

        app.stop()
        app.overrides["SAAS_RUNTIME_ARCHIVE_LEGACY_IMPORT_ENABLED"] = "false"
        app.boot()
        h.api(base, admin, create, expected=404)
        assert h.request("/actuator/health")[0] == 200
        print("  OK  disabled deployment remains healthy and refuses import", flush=True)
        print("=== Legacy archive import smoke: PASS=5 FAIL=0 ===", flush=True)
    finally:
        try:
            if installed:
                app.stop()
                wait_for_owned_database_disconnect()
                h.pg(f"DROP TRIGGER IF EXISTS {fixture} ON legacy_archive_imports; DROP FUNCTION IF EXISTS {fixture}()")
            if agent and token and app.process and app.process.poll() is None:
                status, _ = h.request(f"/api/agents/{agent}", token, method="DELETE")
                assert status in (200, 204, 404), ("Agent cleanup", status)
        finally:
            try:
                if user:
                    h.pg(f"UPDATE users SET role = 'member' WHERE id = '{user}'")
            finally:
                app.stop()


if __name__ == "__main__":
    main()
