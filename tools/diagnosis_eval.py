#!/usr/bin/env python3
"""Deterministic offline evaluator for diagnosis result fixtures.

The evaluator intentionally does not call an LLM. It scores a result produced by
an adapter against scenario annotations so Prompt/model/tool changes can be
compared using the same contract in CI and before release.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import sys
from pathlib import Path
from typing import Any


EVIDENCE_REFERENCE_PATTERN = re.compile(r"\[evidence:\s*([^\]\s]+)\s*\]", re.IGNORECASE)
SOURCE_CITATION_PATTERN = re.compile(r"\[来源:\s*([^\]\s]+)\s*\]", re.IGNORECASE)
EMAIL_PATTERN = re.compile(r"\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b", re.IGNORECASE)
SECRET_VALUE_PATTERNS = (
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\b(?:ghp|github_pat|glpat|xox[baprs])[-_][A-Za-z0-9_-]{16,}\b", re.IGNORECASE),
    re.compile(r"\b(?:sk|rk)-[A-Za-z0-9_-]{20,}\b", re.IGNORECASE),
    re.compile(r"\bBearer\s+[A-Za-z0-9._~+/=-]{20,}\b", re.IGNORECASE),
)
SENSITIVE_FIELD_PATTERN = re.compile(
    r"(?:password|passwd|secret|token|apikey|accesskey(?:id|secret)?|authorization)$",
    re.IGNORECASE,
)
REDACTED_VALUE_PATTERN = re.compile(
    r"^(?:[*#]{3,}|redacted|removed|masked|placeholder|dummy|fake|example|"
    r"not[-_ ]a[-_ ]real[-_ ](?:secret|token|key)|<[^>]*(?:redact|mask)[^>]*>|"
    r"\[[^\]]*(?:redact|mask)[^\]]*\])$",
    re.IGNORECASE,
)
ANCHOR_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_.:-]{1,}|\d+(?:\.\d+)?|[\u4e00-\u9fff]{2,}")
GENERIC_ANCHORS = {
    "证据", "报告", "当前", "建议", "查询", "显示", "存在", "情况", "可以",
    "指标", "日志", "成功", "失败", "通过", "需要", "系统", "服务", "进行",
}


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def load_results(path: Path) -> list[dict[str, Any]]:
    if path.suffix.lower() == ".jsonl":
        with path.open(encoding="utf-8") as handle:
            values = [json.loads(line) for line in handle if line.strip()]
            return [require_mapping(value, "each JSONL result") for value in values]
    value = load_json(path)
    if isinstance(value, dict):
        value = value.get("results", [])
    if not isinstance(value, list):
        raise ValueError("results must be a JSON array, object.results, or JSONL")
    return [require_mapping(item, "each result") for item in value]


def require_mapping(value: Any, description: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{description} must be a JSON object")
    return value


def require_text(mapping: dict[str, Any], field: str, description: str) -> str:
    value = mapping.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{description}.{field} must be a non-empty string")
    return value


def validate_metadata(value: Any) -> dict[str, Any]:
    metadata = require_mapping(value, "evaluation metadata")
    require_text(metadata, "schemaVersion", "evaluation metadata")
    dataset = require_mapping(metadata.get("dataset"), "evaluation metadata.dataset")
    for field in ("id", "version", "split", "owner"):
        require_text(dataset, field, "evaluation metadata.dataset")
    run = require_mapping(metadata.get("run"), "evaluation metadata.run")
    for field in ("adapterVersion", "modelVersion", "promptVersion", "knowledgeBaseVersion"):
        require_text(run, field, "evaluation metadata.run")
    seed = run.get("randomSeed")
    if isinstance(seed, bool) or not isinstance(seed, int):
        raise ValueError("evaluation metadata.run.randomSeed must be an integer")
    return metadata


def validate_approved_metadata(metadata: dict[str, Any], scenario_count: int | None = None) -> None:
    """Require real-run metadata before a release-quality gate can pass."""
    dataset = metadata["dataset"]
    run = metadata["run"]
    if dataset["id"].strip().lower() == "superbizagent-smoke" \
            or dataset["split"].strip().lower() == "smoke":
        raise ValueError("approved evaluation cannot use the smoke dataset")
    if any(run[field].strip().lower() in {"not-run", "fixture-v1"}
           for field in ("adapterVersion", "modelVersion", "promptVersion", "knowledgeBaseVersion")):
        raise ValueError("approved evaluation metadata must describe a real adapter/model/prompt/knowledge-base run")
    if dataset.get("source") != "deidentified-incident":
        raise ValueError("approved evaluation metadata.dataset.source must be deidentified-incident")
    annotation = require_mapping(metadata.get("annotation"), "evaluation metadata.annotation")
    for field in ("reviewedBy", "reviewedAt", "policyVersion"):
        require_text(annotation, field, "evaluation metadata.annotation")
    if annotation.get("expertReviewed") is not True:
        raise ValueError("evaluation metadata.annotation.expertReviewed must be true")
    if annotation.get("redactionApproved") is not True:
        raise ValueError("evaluation metadata.annotation.redactionApproved must be true")
    declared_scenario_count = annotation.get("scenarioCount")
    if isinstance(declared_scenario_count, bool) or not isinstance(declared_scenario_count, int) \
            or declared_scenario_count <= 0:
        raise ValueError("evaluation metadata.annotation.scenarioCount must be a positive integer")
    if scenario_count is not None and declared_scenario_count != scenario_count:
        raise ValueError("evaluation metadata.annotation.scenarioCount must match the scenario set")
    approval = require_mapping(metadata.get("approval"), "evaluation metadata.approval")
    for field in ("approvedBy", "approvedAt"):
        require_text(approval, field, "evaluation metadata.approval")


def _sensitive_field_name(value: str) -> bool:
    normalized = re.sub(r"[^a-z0-9]", "", value.lower())
    if normalized.endswith(("ref", "name", "path", "arn", "version")):
        return False
    return SENSITIVE_FIELD_PATTERN.search(normalized) is not None


def _is_redacted_value(value: Any) -> bool:
    if value is None or value == "":
        return True
    if not isinstance(value, str):
        return False
    return REDACTED_VALUE_PATTERN.fullmatch(value.strip()) is not None


def validate_redaction_safety(scenarios: list[dict[str, Any]], results: list[dict[str, Any]]) -> None:
    """Reject obvious secrets or direct email addresses in approved payloads.

    This is a deterministic safety net for the release command, not proof that a
    dataset is fully de-identified. Human redaction approval remains mandatory.
    """
    findings: list[str] = []

    def visit(value: Any, path: str) -> None:
        if len(findings) >= 5:
            return
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{path}.{key}"
                if isinstance(key, str) and _sensitive_field_name(key) and not _is_redacted_value(child):
                    findings.append(f"{child_path} contains a non-redacted sensitive field")
                    if len(findings) >= 5:
                        return
                visit(child, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{path}[{index}]")
        elif isinstance(value, str):
            if EMAIL_PATTERN.search(value):
                findings.append(f"{path} contains an email address")
                return
            for pattern in SECRET_VALUE_PATTERNS:
                if pattern.search(value):
                    findings.append(f"{path} contains a probable secret or token")
                    return

    for index, scenario in enumerate(scenarios):
        visit(scenario, f"scenarios[{index}]")
    for index, result in enumerate(results):
        visit(result, f"results[{index}]")
    if findings:
        raise ValueError(
            "approved evaluation payload failed automated redaction safety check: "
            + "; ".join(findings)
        )


def validate_release_gate_config(config: dict[str, Any]) -> None:
    """Require at least one declared threshold for a release gate."""
    thresholds = config.get("thresholds")
    if not isinstance(thresholds, dict) or not thresholds:
        raise ValueError("approved evaluation gate must define non-empty thresholds")


def validate_approved_scenario_grounding(scenarios: list[dict[str, Any]]) -> None:
    """Require stable evidence aliases so a rerun cannot depend on random IDs."""
    for scenario in scenarios:
        scenario_id = require_text(scenario, "id", "approved scenario")
        allowed = set(required_list(scenario.get("allowedEvidence"), "allowedEvidence", scenario_id))
        matchers = scenario.get("evidenceMatchers")
        if not isinstance(matchers, dict) or not matchers:
            raise ValueError(f"{scenario_id}: approved evaluation requires evidenceMatchers")
        if not allowed.issubset(matchers):
            raise ValueError(f"{scenario_id}: evidenceMatchers must cover allowedEvidence")


def as_list(value: Any, field: str, item_id: str) -> list[str]:
    if value is None:
        return []
    if not isinstance(value, list) or not all(isinstance(item, str) and item.strip() for item in value):
        raise ValueError(f"{item_id}: {field} must be an array of strings")
    normalized = [item.strip() for item in value]
    if len(set(normalized)) != len(normalized):
        raise ValueError(f"{item_id}: {field} must not contain duplicates")
    return normalized


def required_list(value: Any, field: str, item_id: str) -> list[str]:
    if value is None:
        raise ValueError(f"{item_id}: {field} must be an array of strings")
    return as_list(value, field, item_id)


def require_boolean(value: Any, field: str, item_id: str) -> bool:
    if not isinstance(value, bool):
        raise ValueError(f"{item_id}: {field} must be a boolean")
    return value


def extract_report_evidence(report: str) -> set[str]:
    return {match.group(1).strip() for match in EVIDENCE_REFERENCE_PATTERN.finditer(report)}


def extract_source_citations(report: str) -> set[str]:
    return {match.group(1).strip() for match in SOURCE_CITATION_PATTERN.finditer(report)}


def evidence_supports_claim(claim: str, evidence: dict[str, Any]) -> bool:
    claim_anchors = anchors(claim)
    evidence_anchors = anchors(" ".join(
        str(evidence.get(field) or "")
        for field in ("toolName", "timeRange", "summary", "content", "queryParams")))
    if not claim_anchors or not evidence_anchors:
        return not claim_anchors
    return len(claim_anchors & evidence_anchors) >= min(2, len(claim_anchors))


def anchors(value: str) -> set[str]:
    result: set[str] = set()
    for token in ANCHOR_PATTERN.findall(value.lower()):
        if len(token) < 2 or token in GENERIC_ANCHORS:
            continue
        result.add(token)
        if re.fullmatch(r"[\u4e00-\u9fff]+", token):
            result.update(token[index:index + 2] for index in range(len(token) - 1))
        if re.fullmatch(r"[a-z][a-z0-9_.:-]*", token):
            result.update(part for part in re.split(r"[_.:-]+", token) if len(part) >= 2)
    return result


def validate_result_evidence(value: Any, scenario_id: str) -> dict[str, dict[str, Any]]:
    if not isinstance(value, list):
        raise ValueError(f"{scenario_id}: evidence must be an array for approved evaluation")
    result: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(value):
        evidence = require_mapping(item, f"{scenario_id}.evidence[{index}]")
        evidence_id = require_text(evidence, "id", f"{scenario_id}.evidence[{index}]")
        if evidence_id in result:
            raise ValueError(f"{scenario_id}: duplicate evidence id: {evidence_id}")
        result[evidence_id] = evidence
    return result


def canonical_evidence_id(value: str, aliases: dict[str, str]) -> str:
    return aliases.get(value, value)


def validate_claims(value: Any, scenario_id: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{scenario_id}: claims must be a non-empty array")
    claims: list[dict[str, Any]] = []
    claim_ids: set[str] = set()
    for index, item in enumerate(value):
        claim_id = f"{scenario_id}.claims[{index}]"
        claim = require_mapping(item, claim_id)
        canonical_id = require_text(claim, "claimId", claim_id)
        if canonical_id in claim_ids:
            raise ValueError(f"{scenario_id}: duplicate claimId: {canonical_id}")
        claim_ids.add(canonical_id)
        text = require_text(claim, "text", claim_id)
        definitive = require_boolean(claim.get("definitive"), "definitive", claim_id)
        evidence = as_list(claim.get("evidence"), "evidence", claim_id)
        claims.append({
            "claimId": canonical_id,
            "text": text,
            "definitive": definitive,
            "evidence": evidence,
        })
    return claims


def validate_claim_evidence(value: Any, scenario_id: str) -> dict[str, set[str]]:
    mapping = require_mapping(value, f"{scenario_id}.claimEvidence")
    result: dict[str, set[str]] = {}
    for claim_id, evidence in mapping.items():
        if not isinstance(claim_id, str) or not claim_id.strip():
            raise ValueError(f"{scenario_id}: claimEvidence keys must be non-empty strings")
        result[claim_id] = set(required_list(evidence, f"claimEvidence.{claim_id}", scenario_id))
    return result


def normalize(value: str) -> str:
    return " ".join(value.strip().lower().split())


def mean(values: list[float]) -> float:
    return round(statistics.fmean(values), 4) if values else 0.0


def validate_non_negative_number(value: Any, field: str, scenario_id: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
        raise ValueError(f"{scenario_id}: {field} must be a non-negative number")
    return float(value)


def retrieval_metrics(scenario: dict[str, Any], result: dict[str, Any]) -> dict[str, float]:
    """Score optional retrieval annotations without affecting legacy scenarios."""
    expected = scenario.get("retrievalRelevantIds")
    if expected is None:
        return {}
    expected_ids = set(required_list(expected, "retrievalRelevantIds", scenario["id"]))
    retrieved = result.get("retrievedDocumentIds")
    if not isinstance(retrieved, list) or not all(isinstance(item, str) for item in retrieved):
        raise ValueError(f"{scenario['id']}: retrievedDocumentIds is required for retrieval scoring")
    unique_retrieved = list(dict.fromkeys(retrieved))
    hits = [index for index, item in enumerate(unique_retrieved, start=1) if item in expected_ids]
    return {
        "retrievalRecallAtK": round(
            len(set(unique_retrieved) & expected_ids) / len(expected_ids), 4
        ) if expected_ids else 1.0,
        "retrievalMrr": round(1.0 / hits[0], 4) if hits else 0.0,
        "retrievalCitationHitRate": round(
            1.0 if extract_source_citations(result.get("report", "")) & expected_ids else 0.0, 4
        ),
    }


def evaluate(scenarios: list[dict[str, Any]], results: list[dict[str, Any]],
             strict_grounding: bool = False) -> dict[str, Any]:
    scenario_by_id = {}
    for scenario in scenarios:
        scenario = require_mapping(scenario, "each scenario")
        scenario_id = scenario.get("id")
        if not isinstance(scenario_id, str) or not scenario_id.strip():
            raise ValueError("every scenario needs a non-empty string id")
        if scenario_id in scenario_by_id:
            raise ValueError(f"duplicate scenario id: {scenario_id}")
        scenario_by_id[scenario_id] = scenario

    result_by_id = {}
    for result in results:
        result = require_mapping(result, "each result")
        scenario_id = result.get("scenarioId")
        if scenario_id not in scenario_by_id:
            raise ValueError(f"result references unknown scenario: {scenario_id}")
        if scenario_id in result_by_id:
            raise ValueError(f"duplicate result for scenario: {scenario_id}")
        result_by_id[scenario_id] = result

    missing = sorted(set(scenario_by_id) - set(result_by_id))
    if missing:
        raise ValueError("missing results for scenarios: " + ", ".join(missing))

    rows: list[dict[str, Any]] = []
    for scenario_id, scenario in scenario_by_id.items():
        result = result_by_id[scenario_id]
        expected = [normalize(item) for item in as_list(
            scenario.get("expectedRootCauses"), "expectedRootCauses", scenario_id)]
        acceptable = [normalize(item) for item in as_list(
            scenario.get("acceptableRootCauses"), "acceptableRootCauses", scenario_id)]
        predictions = [normalize(item) for item in as_list(
            result.get("predictedRootCauses"), "predictedRootCauses", scenario_id)]
        if "allowedEvidence" not in scenario:
            raise ValueError(f"{scenario_id}: allowedEvidence is required")
        if "requiredEvidence" not in scenario:
            raise ValueError(f"{scenario_id}: requiredEvidence is required")
        if "claimEvidence" not in scenario:
            raise ValueError(f"{scenario_id}: claimEvidence is required")
        allowed_evidence = set(required_list(
            scenario.get("allowedEvidence"), "allowedEvidence", scenario_id))
        required_evidence = set(required_list(
            scenario.get("requiredEvidence"), "requiredEvidence", scenario_id))
        if not required_evidence.issubset(allowed_evidence):
            raise ValueError(f"{scenario_id}: requiredEvidence must be a subset of allowedEvidence")
        claim_evidence_by_id = validate_claim_evidence(scenario.get("claimEvidence"), scenario_id)
        if any(not evidence.issubset(allowed_evidence) for evidence in claim_evidence_by_id.values()):
            raise ValueError(f"{scenario_id}: claimEvidence must be a subset of allowedEvidence")
        if "requiredClaimIds" not in scenario:
            raise ValueError(f"{scenario_id}: requiredClaimIds is required")
        required_claim_ids = set(required_list(
            scenario.get("requiredClaimIds"), "requiredClaimIds", scenario_id))
        if not required_claim_ids:
            raise ValueError(f"{scenario_id}: requiredClaimIds must not be empty")
        if not required_claim_ids.issubset(claim_evidence_by_id):
            raise ValueError(f"{scenario_id}: requiredClaimIds must be keys in claimEvidence")
        report = require_text(result, "report", f"result {scenario_id}")
        aliases_value = result.get("evidenceAliases", {})
        if not isinstance(aliases_value, dict) or not all(
                isinstance(key, str) and isinstance(value, str)
                for key, value in aliases_value.items()):
            raise ValueError(f"{scenario_id}: evidenceAliases must be an object of strings")
        aliases = {str(key): str(value) for key, value in aliases_value.items()}
        actual_evidence = validate_result_evidence(result.get("evidence"), scenario_id) \
            if strict_grounding else {}
        if strict_grounding:
            unknown_aliases = set(aliases) - set(actual_evidence)
            if unknown_aliases:
                raise ValueError(f"{scenario_id}: evidenceAliases references unknown evidence: "
                                 + ", ".join(sorted(unknown_aliases)))
            if len(set(aliases.values())) != len(aliases):
                raise ValueError(f"{scenario_id}: evidenceAliases values must be unique")
        cited_raw = extract_report_evidence(report)
        cited = {canonical_evidence_id(item, aliases) for item in cited_raw}
        actual_by_canonical = {
            canonical_evidence_id(evidence_id, aliases): evidence
            for evidence_id, evidence in actual_evidence.items()
        }
        if strict_grounding:
            missing_actual = cited - set(actual_by_canonical)
            if missing_actual:
                raise ValueError(f"{scenario_id}: report cites evidence not present in DiagnosisRun: "
                                 + ", ".join(sorted(missing_actual)))
        declared_cited = result.get("citedEvidence")
        if declared_cited is not None and set(as_list(declared_cited, "citedEvidence", scenario_id)) != cited:
            raise ValueError(f"{scenario_id}: citedEvidence does not match evidence references in report")
        claims = validate_claims(result.get("claims"), scenario_id)
        actual_claim_ids = {claim["claimId"] for claim in claims}
        missing_required_claims = sorted(required_claim_ids - actual_claim_ids)
        for claim in claims:
            cited_claim_evidence = {
                canonical_evidence_id(item, aliases) for item in claim["evidence"]
            }
            if not cited_claim_evidence.issubset(cited):
                raise ValueError(f"{scenario_id}: claim evidence must appear in report citations")
            if claim["claimId"] not in claim_evidence_by_id:
                raise ValueError(f"{scenario_id}: claimId is not annotated: {claim['claimId']}")
        definitive_claims = sum(1 for claim in claims if claim["definitive"])
        unsupported_claims = sum(
            1 for claim in claims
            if claim["definitive"] and (
                not claim["evidence"]
                or not set(canonical_evidence_id(item, aliases) for item in claim["evidence"])
                .issubset(claim_evidence_by_id[claim["claimId"]])
                or strict_grounding and not any(
                    actual_by_canonical.get(canonical_evidence_id(item, aliases), {}).get("success") is True
                    and evidence_supports_claim(
                        claim["text"], actual_by_canonical[canonical_evidence_id(item, aliases)])
                    for item in claim["evidence"]
                )
            )
        )
        definitive_evidence = [
            evidence
            for claim in claims if claim["definitive"]
            for evidence in claim["evidence"]
        ]
        supported_claim_evidence = sum(
            (evidence in claim_evidence_by_id[claim["claimId"]]
             and (not strict_grounding
                  or actual_by_canonical.get(evidence, {}).get("success") is True
                  and evidence_supports_claim(claim["text"], actual_by_canonical[evidence])))
            for claim in claims if claim["definitive"]
            for evidence in {
                canonical_evidence_id(item, aliases) for item in claim["evidence"]
            }
        )
        declared_definitive = result.get("definitiveClaims")
        if declared_definitive is not None and declared_definitive != definitive_claims:
            raise ValueError(f"{scenario_id}: definitiveClaims does not match claims")
        declared_unsupported = result.get("unsupportedDefinitiveClaims")
        if declared_unsupported is not None and declared_unsupported != unsupported_claims:
            raise ValueError(f"{scenario_id}: unsupportedDefinitiveClaims does not match claims")
        tool_calls = validate_non_negative_number(result.get("toolCalls", 0), "toolCalls", scenario_id)
        duration_ms = validate_non_negative_number(result.get("durationMs", 0), "durationMs", scenario_id)

        accepted_roots = set(expected + acceptable)
        supported_cited = cited & allowed_evidence
        if strict_grounding:
            supported_cited = {
                evidence for evidence in supported_cited
                if actual_by_canonical.get(evidence, {}).get("success") is True
            }
        top1 = bool(predictions and predictions[0] in accepted_roots)
        top3 = any(prediction in accepted_roots for prediction in predictions[:3])
        evidence_precision = len(supported_cited) / len(cited) if cited else 1.0
        evidence_recall = (len(supported_cited & required_evidence) / len(required_evidence)
                           if required_evidence else 1.0)
        unsupported_rate = (unsupported_claims / definitive_claims
                            if definitive_claims else 0.0)
        claim_coverage = (len(required_claim_ids) - len(missing_required_claims)) / len(required_claim_ids)
        claim_evidence_precision = (supported_claim_evidence / len(definitive_evidence)
                                     if definitive_evidence else 1.0)
        allow_honest_failure = require_boolean(
            scenario.get("allowHonestFailure"), "allowHonestFailure", scenario_id)
        expected_honest_failure = require_boolean(
            scenario.get("expectedHonestFailure"), "expectedHonestFailure", scenario_id)
        honest_failure = require_boolean(result.get("honestFailure"), "honestFailure", scenario_id)
        honest_failure_correct = (honest_failure == expected_honest_failure
                                  and (allow_honest_failure or not honest_failure))

        rows.append({
            "scenarioId": scenario_id,
            "rootCauseTop1": top1,
            "rootCauseTop3": top3,
            "evidencePrecision": round(evidence_precision, 4),
            "evidenceRecall": round(evidence_recall, 4),
            "claimEvidencePrecision": round(claim_evidence_precision, 4),
            "claimCoverage": round(claim_coverage, 4),
            "unsupportedAssertionRate": round(unsupported_rate, 4),
            "honestFailureCorrect": honest_failure_correct,
            "citedEvidenceCount": len(cited),
            "unsupportedDefinitiveClaims": unsupported_claims,
            "missingRequiredClaims": missing_required_claims,
            "toolCalls": tool_calls,
            "durationMs": duration_ms,
            **retrieval_metrics(scenario, result),
        })

    numeric = lambda name: [float(row[name]) for row in rows]
    metrics = {
        "rootCauseTop1": mean([float(row["rootCauseTop1"]) for row in rows]),
        "rootCauseTop3": mean([float(row["rootCauseTop3"]) for row in rows]),
        "evidencePrecision": mean(numeric("evidencePrecision")),
        "evidenceRecall": mean(numeric("evidenceRecall")),
        "claimEvidencePrecision": mean(numeric("claimEvidencePrecision")),
        "claimCoverage": mean(numeric("claimCoverage")),
        "unsupportedAssertionRate": mean(numeric("unsupportedAssertionRate")),
        "honestFailureAccuracy": mean([float(row["honestFailureCorrect"]) for row in rows]),
        "averageToolCalls": mean(numeric("toolCalls")),
        "p95DurationMs": percentile(numeric("durationMs"), 0.95),
    }
    retrieval_rows = [row for row in rows if "retrievalRecallAtK" in row]
    if retrieval_rows:
        metrics.update({
            "retrievalRecallAtK": mean([float(row["retrievalRecallAtK"]) for row in retrieval_rows]),
            "retrievalMrr": mean([float(row["retrievalMrr"]) for row in retrieval_rows]),
            "retrievalCitationHitRate": mean(
                [float(row["retrievalCitationHitRate"]) for row in retrieval_rows]),
        })
    return {
        "scenarioCount": len(rows),
        "metrics": metrics,
        "rows": rows,
    }


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(1, math.ceil(len(ordered) * fraction))
    index = min(len(ordered) - 1, rank - 1)
    return round(ordered[index], 4)


def write_markdown(report: dict[str, Any], path: Path) -> None:
    metrics = report["metrics"]
    metadata = report.get("metadata", {})
    gate = report.get("gate")
    lines = [
        "# Diagnosis evaluation report",
        "",
        f"Scenarios: {report['scenarioCount']}",
        f"Dataset: {metadata.get('dataset', {}).get('id', 'unknown')}"
        f"@{metadata.get('dataset', {}).get('version', 'unknown')}",
        "",
        "| Metric | Value |",
        "|---|---:|",
    ]
    for name, value in metrics.items():
        lines.append(f"| {name} | {value} |")
    if gate is not None:
        lines.extend(["", f"Gate passed: {gate.get('passed', False)}"])
    lines.extend(["", "| Scenario | Top-1 | Top-3 | Evidence precision | Claim coverage | Claim evidence precision | Evidence recall | Unsupported assertions |", "|---|---:|---:|---:|---:|---:|---:|---:|"])
    for row in report["rows"]:
        lines.append(
            f"| {row['scenarioId']} | {int(row['rootCauseTop1'])} | {int(row['rootCauseTop3'])} | "
            f"{row['evidencePrecision']} | {row['claimCoverage']} | {row['claimEvidencePrecision']} | {row['evidenceRecall']} | "
            f"{row['unsupportedAssertionRate']} |"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def evaluate_gate(report: dict[str, Any], config: dict[str, Any]) -> dict[str, Any]:
    values = dict(report.get("metrics", {}))
    values["scenarioCount"] = report.get("scenarioCount", 0)
    failures: list[str] = []
    checks: list[dict[str, Any]] = []
    thresholds = config.get("thresholds", {})
    if not isinstance(thresholds, dict):
        raise ValueError("gate thresholds must be an object")
    for metric, rule in thresholds.items():
        if metric not in values:
            raise ValueError(f"gate references unknown metric: {metric}")
        if not isinstance(rule, dict):
            raise ValueError(f"gate rule for {metric} must be an object")
        value = float(values[metric])
        check = {"metric": metric, "value": value}
        if "min" in rule:
            minimum = validate_non_negative_number(rule["min"], f"gate {metric}.min", "gate")
            check["min"] = minimum
            if value < minimum:
                failures.append(f"{metric}={value} < min={minimum}")
        if "max" in rule:
            maximum = validate_non_negative_number(rule["max"], f"gate {metric}.max", "gate")
            check["max"] = maximum
            if value > maximum:
                failures.append(f"{metric}={value} > max={maximum}")
        checks.append(check)
    return {"passed": not failures, "checks": checks, "failures": failures}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenarios", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    parser.add_argument("--gate-config", type=Path)
    parser.add_argument(
        "--require-approved-dataset",
        action="store_true",
        help="reject smoke/fixture metadata and require dataset approval fields",
    )
    args = parser.parse_args()
    try:
        raw_scenarios = load_json(args.scenarios)
        scenarios = raw_scenarios.get("scenarios", []) if isinstance(raw_scenarios, dict) else raw_scenarios
        if not isinstance(scenarios, list):
            raise ValueError("scenarios must be a JSON array or object.scenarios")
        results = load_results(args.results)
        metadata = validate_metadata(load_json(args.metadata))
        gate = None
        if args.gate_config:
            gate = require_mapping(load_json(args.gate_config), "evaluation gate config")
        if args.require_approved_dataset:
            if not args.gate_config:
                raise ValueError("--require-approved-dataset requires --gate-config")
            validate_approved_metadata(metadata, len(scenarios))
            validate_release_gate_config(gate)
            validate_approved_scenario_grounding(scenarios)
            validate_redaction_safety(scenarios, results)
        report = evaluate(scenarios, results, strict_grounding=args.require_approved_dataset)
        report["metadata"] = metadata
        if gate is not None:
            report["gate"] = evaluate_gate(report, gate)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        write_markdown(report, args.markdown_output)
        print(json.dumps(report["metrics"], ensure_ascii=False, sort_keys=True))
        if report.get("gate", {}).get("passed") is False:
            print("diagnosis evaluation gate failed: " + "; ".join(report["gate"]["failures"]), file=sys.stderr)
            return 3
        return 0
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"diagnosis evaluation failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
