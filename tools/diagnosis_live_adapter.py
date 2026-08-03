#!/usr/bin/env python3
"""Capture real SuperBizAgent diagnosis runs as evaluator JSONL results.

This adapter deliberately keeps the scoring logic in ``diagnosis_eval.py``. It
only exercises the running application, captures the persisted report/evidence,
and converts the result into the evaluator contract. Scenario annotations must
provide deterministic ``rootCausePatterns`` and ``claimMatchers`` so the
adapter never invents a correctness label from the report itself.

Examples::

    python3 tools/diagnosis_live_adapter.py \
      --base-url http://localhost:9900 \
      --scenarios path/to/approved-scenarios.json \
      --output target/live-results.jsonl \
      --api-token "$APP_API_TOKEN" \
      --webhook-secret "$APP_WEBHOOK_SIGNING_SECRET"

An approved scenario may either contain ``incidentId`` for an already loaded
de-identified Incident, or ``alertPayload`` to submit a signed webhook and
capture the returned ``X-Incident-ID`` / ``X-Diagnosis-Run-ID`` headers.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import sys
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


EVIDENCE_REFERENCE_PATTERN = re.compile(r"\[evidence:\s*([^\]\s]+)\s*\]", re.IGNORECASE)
SOURCE_CITATION_PATTERN = re.compile(r"\[来源:\s*([^\]\s]+)\s*\]", re.IGNORECASE)
UNCERTAINTY_PATTERN = re.compile(
    r"证据不足|无法确认|暂不确认|暂不下结论|等待人工复核|待补充证据|未能确认|人工复核",
    re.IGNORECASE,
)
TERMINAL_STATUSES = {"COMPLETED", "COMPLETED_WITH_GAPS", "FAILED", "CANCELLED"}


def require_mapping(value: Any, description: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} must be an object")
    return value


def load_scenarios(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    scenarios = value.get("scenarios", []) if isinstance(value, dict) else value
    if not isinstance(scenarios, list) or not scenarios:
        raise ValueError("scenarios must be a non-empty JSON array or object.scenarios")
    result: list[dict[str, Any]] = []
    seen: set[str] = set()
    for item in scenarios:
        scenario = require_mapping(item, "each scenario")
        scenario_id = scenario.get("id")
        if not isinstance(scenario_id, str) or not scenario_id.strip():
            raise ValueError("each scenario needs a non-empty id")
        if scenario_id in seen:
            raise ValueError(f"duplicate scenario id: {scenario_id}")
        seen.add(scenario_id)
        if "incidentId" not in scenario and "alertPayload" not in scenario:
            raise ValueError(f"{scenario_id}: incidentId or alertPayload is required")
        if "rootCausePatterns" not in scenario:
            raise ValueError(f"{scenario_id}: rootCausePatterns is required")
        if "claimMatchers" not in scenario:
            raise ValueError(f"{scenario_id}: claimMatchers is required")
        for field in ("expectedRootCauses", "acceptableRootCauses", "allowedEvidence",
                      "requiredEvidence", "claimEvidence", "allowHonestFailure",
                      "expectedHonestFailure"):
            if field not in scenario:
                raise ValueError(f"{scenario_id}: {field} is required for live evaluation")
        required_claim_ids = scenario.get("requiredClaimIds")
        if (not isinstance(required_claim_ids, list)
                or not required_claim_ids
                or not all(isinstance(item, str) and item.strip() for item in required_claim_ids)):
            raise ValueError(f"{scenario_id}: requiredClaimIds must be a non-empty string array")
        result.append(scenario)
    return result


def json_body(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def request_raw(base_url: str, path: str, method: str, body: bytes | None,
                headers: dict[str, str], timeout: float) -> tuple[int, Any, bytes]:
    url = base_url.rstrip("/") + path
    request = Request(url, data=body, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.status, response.headers, response.read()
    except HTTPError as error:
        return error.code, error.headers, error.read()
    except URLError as error:
        raise RuntimeError(f"request failed for {path}: {error.reason}") from error


def request_json(base_url: str, path: str, method: str, headers: dict[str, str],
                 timeout: float, body: bytes | None = None) -> tuple[int, Any, dict[str, Any]]:
    status, response_headers, raw = request_raw(base_url, path, method, body, headers, timeout)
    try:
        value = json.loads(raw.decode("utf-8")) if raw else {}
    except json.JSONDecodeError as error:
        raise RuntimeError(f"non-JSON response from {path}, status={status}") from error
    if not isinstance(value, dict):
        raise RuntimeError(f"response from {path} must be an object")
    return status, response_headers, value


def api_headers(api_token: str | None) -> dict[str, str]:
    headers = {"Accept": "application/json"}
    if api_token:
        headers["X-API-Key"] = api_token
    return headers


def submit_webhook(base_url: str, payload: dict[str, Any], secret: str | None,
                   timeout: float) -> tuple[str, str | None]:
    raw = json_body(payload)
    headers = {"Accept": "text/plain", "Content-Type": "application/json"}
    if secret:
        timestamp = str(int(time.time()))
        nonce = "eval-" + uuid.uuid4().hex
        signed = f"{timestamp}.{nonce}.".encode("utf-8") + raw
        signature = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
        headers.update({
            "X-Webhook-Timestamp": timestamp,
            "X-Webhook-Nonce": nonce,
            "X-Webhook-Signature": "sha256=" + signature,
        })
    status, response_headers, _ = request_raw(
        base_url, "/api/webhook/alert", "POST", raw, headers, timeout)
    if status >= 300:
        raise RuntimeError(f"webhook submission failed with HTTP {status}")
    incident_id = response_headers.get("X-Incident-ID")
    run_id = response_headers.get("X-Diagnosis-Run-ID")
    if not incident_id:
        raise RuntimeError("webhook response did not include X-Incident-ID")
    return incident_id, run_id


def start_existing_incident(base_url: str, incident_id: str, api_token: str | None,
                            timeout: float) -> str | None:
    status, _, value = request_json(
        base_url,
        f"/api/incidents/{quote(incident_id, safe='')}/diagnose?force=true",
        "POST",
        api_headers(api_token),
        timeout,
    )
    if status >= 300 or value.get("code") not in (None, 200):
        raise RuntimeError(f"diagnosis start failed for {incident_id}: HTTP {status}")
    data = value.get("data")
    return data.get("runId") if isinstance(data, dict) else None


def get_incident(base_url: str, incident_id: str, api_token: str | None,
                 timeout: float) -> dict[str, Any]:
    status, _, value = request_json(
        base_url,
        f"/api/incidents/{quote(incident_id, safe='')}",
        "GET",
        api_headers(api_token),
        timeout,
    )
    if status >= 300:
        raise RuntimeError(f"incident lookup failed for {incident_id}: HTTP {status}")
    data = value.get("data")
    return require_mapping(data, f"incident response {incident_id}.data")


def wait_for_run(base_url: str, incident_id: str, run_id: str | None,
                 api_token: str | None, timeout: float, poll_seconds: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        incident = get_incident(base_url, incident_id, api_token, min(timeout, 20.0))
        runs = incident.get("diagnosisRuns")
        if isinstance(runs, list):
            candidates = [item for item in runs if isinstance(item, dict)]
            selected = next((item for item in candidates if item.get("runId") == run_id), None)
            if selected is None and run_id is None and candidates:
                selected = max(candidates, key=lambda item: item.get("createdAt", 0))
            if selected is not None and selected.get("status") in TERMINAL_STATUSES:
                return selected
        time.sleep(poll_seconds)
    raise TimeoutError(f"diagnosis did not reach a terminal state: incident={incident_id}, run={run_id}")


def root_section(report: str) -> str:
    match = re.search(r"(?ms)^## 告警根因分析\s*$.*?(?=^## |\Z)", report)
    return match.group(0) if match else report


def patterns_for(value: Any, description: str) -> list[str]:
    if isinstance(value, str) and value.strip():
        return [value]
    if isinstance(value, list) and all(isinstance(item, str) and item.strip() for item in value):
        return value
    raise ValueError(f"{description} must be a non-empty string or string array")


def matched_roots(scenario: dict[str, Any], report: str) -> list[str]:
    patterns = require_mapping(scenario["rootCausePatterns"], "rootCausePatterns")
    section = root_section(report)
    matches: list[tuple[int, str]] = []
    for root, value in patterns.items():
        if not isinstance(root, str) or not root.strip():
            raise ValueError("rootCausePatterns keys must be non-empty strings")
        found = []
        for pattern in patterns_for(value, f"rootCausePatterns.{root}"):
            match = re.search(pattern, section, re.IGNORECASE | re.MULTILINE)
            if match:
                found.append(match.start())
        if found:
            matches.append((min(found), root))
    return [root for _, root in sorted(matches)]


def matched_claims(scenario: dict[str, Any], report: str) -> list[dict[str, Any]]:
    matchers = require_mapping(scenario["claimMatchers"], "claimMatchers")
    section = root_section(report)
    claims: list[tuple[int, dict[str, Any]]] = []
    for claim_id, value in matchers.items():
        if not isinstance(claim_id, str) or not claim_id.strip():
            raise ValueError("claimMatchers keys must be non-empty strings")
        patterns = patterns_for(value, f"claimMatchers.{claim_id}")
        candidates = []
        for pattern in patterns:
            candidates.extend(re.finditer(pattern, section, re.IGNORECASE | re.MULTILINE))
        if not candidates:
            continue
        match = min(candidates, key=lambda item: item.start())
        line_start = section.rfind("\n", 0, match.start()) + 1
        line_end = section.find("\n", match.end())
        line_end = len(section) if line_end < 0 else line_end
        line = section[line_start:line_end].strip()
        evidence = sorted(set(EVIDENCE_REFERENCE_PATTERN.findall(line)))
        claims.append((match.start(), {
            "claimId": claim_id,
            "text": line,
            "definitive": not bool(UNCERTAINTY_PATTERN.search(line)),
            "evidence": evidence,
        }))
    return [claim for _, claim in sorted(claims)]


def evidence_aliases(scenario: dict[str, Any], evidence: list[dict[str, Any]]) -> dict[str, str]:
    """Map volatile persisted evidence IDs to reviewed scenario aliases."""
    matchers = scenario.get("evidenceMatchers", {})
    if not isinstance(matchers, dict):
        raise ValueError(f"{scenario['id']}: evidenceMatchers must be an object")
    aliases: dict[str, str] = {}
    for alias, matcher in matchers.items():
        if not isinstance(alias, str) or not alias.strip() or not isinstance(matcher, dict):
            raise ValueError(f"{scenario['id']}: evidenceMatchers entries must be objects")
        selectors = ("toolName", "queryPattern", "summaryPattern", "timeRange")
        if not any(matcher.get(selector) not in (None, "") for selector in selectors):
            raise ValueError(f"{scenario['id']}: evidence matcher must declare a selector: {alias}")
        matches = []
        for item in evidence:
            evidence_id = item.get("id")
            if not isinstance(evidence_id, str) or evidence_id in aliases:
                continue
            if matcher.get("toolName") and item.get("toolName") != matcher["toolName"]:
                continue
            try:
                if matcher.get("queryPattern") and not re.search(
                        str(matcher["queryPattern"]), str(item.get("queryParams", "")), re.IGNORECASE):
                    continue
                if matcher.get("summaryPattern") and not re.search(
                        str(matcher["summaryPattern"]), str(item.get("summary", "")), re.IGNORECASE):
                    continue
            except re.error as error:
                raise ValueError(f"{scenario['id']}: invalid evidence matcher regex: {alias}") from error
            if matcher.get("timeRange") and item.get("timeRange") != matcher["timeRange"]:
                continue
            matches.append(item)
        if len(matches) > 1:
            raise ValueError(f"{scenario['id']}: evidence alias matched more than once: {alias}")
        if matches:
            evidence_id = matches[0]["id"]
            aliases[evidence_id] = alias
    return aliases


def honest_failure(report: str, run: dict[str, Any], scenario: dict[str, Any]) -> bool:
    patterns = scenario.get("honestFailurePatterns", [UNCERTAINTY_PATTERN.pattern])
    for pattern in patterns_for(patterns, "honestFailurePatterns"):
        if re.search(pattern, report, re.IGNORECASE | re.MULTILINE):
            return True
    return run.get("status") in {"FAILED", "COMPLETED_WITH_GAPS"}


def to_result(scenario: dict[str, Any], run: dict[str, Any], elapsed_ms: float) -> dict[str, Any]:
    report = run.get("report") or ""
    if not isinstance(report, str):
        report = str(report)
    evidence = run.get("evidence")
    evidence_items = [item for item in evidence if isinstance(item, dict)] if isinstance(evidence, list) else []
    tool_calls = sum(1 for item in evidence_items
                     if item.get("type") == "tool_call" or item.get("toolName"))
    started = run.get("startedAt")
    completed = run.get("completedAt")
    duration = ((completed - started)
                if isinstance(started, (int, float))
                and isinstance(completed, (int, float))
                and completed >= started else elapsed_ms)
    result = {
        "scenarioId": scenario["id"],
        "predictedRootCauses": matched_roots(scenario, report),
        "report": report,
        "claims": matched_claims(scenario, report),
        "honestFailure": honest_failure(report, run, scenario),
        "toolCalls": tool_calls,
        "durationMs": round(float(duration), 4),
        "evidence": [
            {
                "id": item.get("id"),
                "type": item.get("type"),
                "toolName": item.get("toolName"),
                "queryParams": item.get("queryParams"),
                "timeRange": item.get("timeRange"),
                "summary": item.get("summary") or item.get("content"),
                "content": item.get("content"),
                "success": item.get("success"),
                "errorCode": item.get("errorCode"),
            }
            for item in evidence_items
            if isinstance(item.get("id"), str) and item.get("id").strip()
        ],
    }
    aliases = evidence_aliases(scenario, result["evidence"])
    if aliases:
        result["evidenceAliases"] = aliases
    result["citedDocumentIds"] = sorted({
        match.group(1).strip() for match in SOURCE_CITATION_PATTERN.finditer(report)
    })
    retrieval = run.get("retrieval")
    if isinstance(retrieval, dict):
        for field in ("retrievedDocumentIds", "candidateDocumentIds", "rerankedDocumentIds"):
            value = retrieval.get(field)
            if isinstance(value, list) and all(isinstance(item, str) for item in value):
                result[field] = value
    return result


def run(args: argparse.Namespace) -> int:
    scenarios = load_scenarios(Path(args.scenarios))
    api_token = args.api_token
    webhook_secret = args.webhook_secret
    results: list[dict[str, Any]] = []
    for scenario in scenarios:
        started = time.monotonic()
        incident_id = scenario.get("incidentId")
        run_id = scenario.get("runId")
        if incident_id is None:
            payload = require_mapping(scenario.get("alertPayload"), f"{scenario['id']}.alertPayload")
            incident_id, run_id = submit_webhook(args.base_url, payload, webhook_secret, args.request_timeout)
        elif scenario.get("startDiagnosis", True):
            run_id = start_existing_incident(args.base_url, str(incident_id), api_token, args.request_timeout)
        final_run = wait_for_run(args.base_url, str(incident_id), run_id, api_token,
                                 args.timeout_seconds, args.poll_seconds)
        results.append(to_result(scenario, final_run, (time.monotonic() - started) * 1000.0))
        print(f"captured scenario={scenario['id']} incident={incident_id} run={final_run.get('runId')} status={final_run.get('status')}",
              file=sys.stderr)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as handle:
        for result in results:
            handle.write(json.dumps(result, ensure_ascii=False) + "\n")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:9900")
    parser.add_argument("--scenarios", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--api-token")
    parser.add_argument("--webhook-secret")
    parser.add_argument("--request-timeout", type=float, default=20.0)
    parser.add_argument("--timeout-seconds", type=float, default=900.0)
    parser.add_argument("--poll-seconds", type=float, default=2.0)
    args = parser.parse_args()
    try:
        args.api_token = args.api_token or os.environ.get("APP_API_TOKEN")
        args.webhook_secret = args.webhook_secret or os.environ.get(
            "APP_WEBHOOK_SIGNING_SECRET") or os.environ.get("APP_WEBHOOK_SECRET")
        return run(args)
    except (OSError, TypeError, ValueError, RuntimeError, TimeoutError, json.JSONDecodeError) as error:
        print(f"live diagnosis adapter failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
