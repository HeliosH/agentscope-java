#!/usr/bin/env python3
"""Authenticated HTTP adapter for an independently running LlamaFirewall container."""

from __future__ import annotations

import asyncio
import hmac
import json
import logging
import os
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

LOG = logging.getLogger("llama-firewall-service")
HOST = os.getenv("LF_HTTP_HOST", "0.0.0.0")
PORT = int(os.getenv("LF_HTTP_PORT", "8080"))
AUTH_TOKEN = os.getenv("LF_AUTH_TOKEN", "")
MAX_BODY_BYTES = int(os.getenv("LF_MAX_BODY_BYTES", "1048576"))
MAX_CONCURRENT_REQUESTS = max(1, int(os.getenv("LF_MAX_CONCURRENT_REQUESTS", "4")))
SCAN_LOCK = threading.Lock()
REQUEST_SLOTS = threading.BoundedSemaphore(MAX_CONCURRENT_REQUESTS)
SCANNER_INSTANCES: dict[str, Any] = {}

DEFAULT_SCANNERS = {
    "user_input": "hidden_ascii,regex",
    "tool_request": "code_shield,hidden_ascii,regex",
    "tool_result": "hidden_ascii,regex",
    "assistant_output": "code_shield,regex",
}
STAGE_ROLES = {
    "user_input": "user",
    "tool_request": "assistant",
    "tool_result": "tool",
    "assistant_output": "assistant",
}
SCANNER_NAMES = {"code_shield", "hidden_ascii", "regex"}


def configured_scanners(stage: str) -> list[str]:
    env_name = f"LF_{stage.upper()}_SCANNERS"
    configured = os.getenv(env_name, DEFAULT_SCANNERS[stage])
    scanners = [name.strip().lower() for name in configured.split(",") if name.strip()]
    unsupported = set(scanners) - SCANNER_NAMES
    if unsupported:
        raise ValueError(f"Unsupported scanners in {env_name}: {sorted(unsupported)}")
    if not scanners:
        raise ValueError(f"At least one scanner must be configured in {env_name}")
    return scanners


def initialize_scanners() -> None:
    from llamafirewall.llamafirewall import create_scanner
    from llamafirewall.llamafirewall_data_types import ScannerType

    scanner_types = {
        "code_shield": ScannerType.CODE_SHIELD,
        "hidden_ascii": ScannerType.HIDDEN_ASCII,
        "regex": ScannerType.REGEX,
    }
    configured = {
        name for stage in DEFAULT_SCANNERS for name in configured_scanners(stage)
    }
    for name in sorted(configured):
        LOG.info("Initializing scanner: %s", name)
        SCANNER_INSTANCES[name] = create_scanner(scanner_types[name])


def scan(payload: dict[str, Any]) -> dict[str, Any]:
    from llamafirewall.llamafirewall_data_types import Message, Role

    stage = str(payload.get("stage", "")).strip().lower()
    role_name = str(payload.get("role", "")).strip().lower()
    content = payload.get("content")
    if stage not in DEFAULT_SCANNERS:
        raise ValueError(f"Unsupported scan stage: {stage}")
    if STAGE_ROLES[stage] != role_name:
        raise ValueError(f"Role {role_name} is not valid for stage {stage}")
    if not isinstance(content, str):
        raise ValueError("content must be a string")

    roles = {"user": Role.USER, "assistant": Role.ASSISTANT, "tool": Role.TOOL}
    message = Message(role=roles[role_name], content=content)
    scanner_names = configured_scanners(stage)

    async def run_scanners() -> list[tuple[str, Any]]:
        results: list[tuple[str, Any]] = []
        for name in scanner_names:
            results.append((name, await SCANNER_INSTANCES[name].scan(message, None)))
        return results

    with SCAN_LOCK:
        results = asyncio.run(run_scanners())

    decision = "allow"
    score = 0.0
    status = "success"
    deciding_scanners: list[str] = []
    for expected in ("block", "human_in_the_loop_required", "allow"):
        matching = [(name, result) for name, result in results if result.decision.value == expected]
        if matching:
            decision = expected
            score = max(float(result.score) for _, result in matching)
            status = matching[0][1].status.value
            deciding_scanners = [name for name, _ in matching]
            break
    reason = {
        "block": "LlamaFirewall blocked content",
        "human_in_the_loop_required": "LlamaFirewall requires human review",
        "allow": "LlamaFirewall allowed content",
    }[decision]
    return {
        "decision": decision,
        "reason": reason,
        "score": score,
        "scanner": ",".join(deciding_scanners or scanner_names),
        "status": status,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "LlamaFirewallIntegrationService/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._json(HTTPStatus.OK, {"status": "UP"})
            return
        self._json(HTTPStatus.NOT_FOUND, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/scan":
            self._json(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        supplied = self.headers.get("Authorization", "")
        if not hmac.compare_digest(supplied, f"Bearer {AUTH_TOKEN}"):
            self._json(HTTPStatus.UNAUTHORIZED, {"error": "unauthorized"})
            return
        if not REQUEST_SLOTS.acquire(blocking=False):
            self._json(HTTPStatus.TOO_MANY_REQUESTS, {"error": "scanner_busy"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > MAX_BODY_BYTES:
                self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "invalid_size"})
                return
            payload = json.loads(self.rfile.read(length))
            if not isinstance(payload, dict):
                raise ValueError("request body must be a JSON object")
            self._json(HTTPStatus.OK, scan(payload))
        except (ValueError, json.JSONDecodeError) as error:
            self._json(HTTPStatus.BAD_REQUEST, {"error": "invalid_request", "message": str(error)})
        except Exception as error:
            LOG.exception("LlamaFirewall scan failed")
            self._json(
                HTTPStatus.SERVICE_UNAVAILABLE,
                {"error": "scanner_unavailable", "message": type(error).__name__},
            )
        finally:
            REQUEST_SLOTS.release()

    def log_message(self, format: str, *args: Any) -> None:
        LOG.info("%s - %s", self.address_string(), format % args)

    def _json(self, status: HTTPStatus, body: dict[str, Any]) -> None:
        encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def main() -> None:
    logging.basicConfig(
        level=os.getenv("LF_LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    if not AUTH_TOKEN:
        raise RuntimeError("LF_AUTH_TOKEN is required")
    initialize_scanners()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    LOG.info("LlamaFirewall integration service listening on %s:%s", HOST, PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        LOG.info("LlamaFirewall integration service stopping")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
