#!/usr/bin/env python3
"""Build a safe, auditable Milvus vector migration manifest.

This tool is intentionally planning-only. It never writes to Milvus and never
guesses a tenant. A separately approved worker may consume the manifest to
rebuild known documents in their tenant context; unknown or conflicting
sources remain manual-review items.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def load_object(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as handle:
        value = json.load(handle)
    if not isinstance(value, dict):
        raise ValueError(f"{path}: expected a JSON object")
    return value


def inventory_entries(payload: dict[str, Any]) -> list[dict[str, Any]]:
    value = payload.get("data", payload)
    if not isinstance(value, dict) or not isinstance(value.get("entries"), list):
        raise ValueError("inventory must contain data.entries")
    return [entry for entry in value["entries"] if isinstance(entry, dict)]


def mapping_by_document(payload: dict[str, Any]) -> dict[str, str]:
    value = payload.get("documents", payload)
    if isinstance(value, dict):
        items = [{"documentId": key, "tenantId": tenant} for key, tenant in value.items()]
    elif isinstance(value, list):
        items = value
    else:
        raise ValueError("mapping must be an object or contain a documents array")
    result: dict[str, str] = {}
    for item in items:
        if not isinstance(item, dict):
            raise ValueError("mapping documents must be objects")
        document_id = str(item.get("documentId", "")).strip()
        tenant_id = str(item.get("tenantId", "")).strip()
        if not document_id or not tenant_id:
            raise ValueError("mapping documents require non-empty documentId and tenantId")
        previous = result.get(document_id)
        if previous and previous != tenant_id:
            raise ValueError(f"conflicting tenant mapping for documentId={document_id}")
        result[document_id] = tenant_id
    return result


def action_id(entry: dict[str, Any], action: str, tenant_id: str | None) -> str:
    stable = "|".join([
        action, str(entry.get("id", "")), str(entry.get("documentId", "")),
        str(tenant_id or ""),
    ])
    return hashlib.sha256(stable.encode("utf-8")).hexdigest()[:24]


def build_plan(entries: list[dict[str, Any]], mapping: dict[str, str],
               batch_size: int, resume_from: int, completed: set[str]) -> dict[str, Any]:
    actions: list[dict[str, Any]] = []
    counts = {"KEEP_TAGGED": 0, "REBUILD": 0, "QUARANTINE": 0, "CONFLICT": 0, "SKIPPED": 0}
    for index, entry in enumerate(entries):
        if index < resume_from:
            counts["SKIPPED"] += 1
            continue
        document_id = str(entry.get("documentId", "")).strip()
        observed_tenant = str(entry.get("tenantId", "")).strip()
        mapped_tenant = mapping.get(document_id) if document_id else None
        if observed_tenant and mapped_tenant and observed_tenant != mapped_tenant:
            action, target_tenant = "CONFLICT", None
        elif observed_tenant:
            action, target_tenant = "KEEP_TAGGED", observed_tenant
        elif mapped_tenant:
            action, target_tenant = "REBUILD", mapped_tenant
        else:
            action, target_tenant = "QUARANTINE", None
        item_id = action_id(entry, action, target_tenant)
        status = "ALREADY_COMPLETED" if item_id in completed else "PENDING"
        counts[action] += 1
        actions.append({
            "actionId": item_id, "action": action, "status": status,
            "vectorId": entry.get("id"), "documentId": document_id or None,
            "source": entry.get("source"), "contentHash": entry.get("contentHash"),
            "observedTenantId": observed_tenant or None,
            "targetTenantId": target_tenant,
            "requiresManualApproval": action in {"QUARANTINE", "CONFLICT"},
        })
    pending = [item for item in actions if item["status"] == "PENDING"]
    next_resume_from = min(resume_from + batch_size, len(entries))
    return {
        "schemaVersion": "1", "mode": "dry-run", "batchSize": batch_size,
        "resumeFrom": resume_from, "counts": counts,
        "nextResumeFrom": next_resume_from,
        "pendingActionIds": [item["actionId"] for item in pending],
        "actions": actions[:batch_size],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True,
                        help="approved documentId -> tenantId mapping JSON")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=100)
    parser.add_argument("--resume-from", type=int, default=0)
    parser.add_argument("--checkpoint", type=Path,
                        help="JSON file containing completedActionIds")
    args = parser.parse_args()
    if args.batch_size < 1 or args.batch_size > 1_000:
        parser.error("--batch-size must be between 1 and 1000")
    if args.resume_from < 0:
        parser.error("--resume-from must be non-negative")
    completed: set[str] = set()
    if args.checkpoint:
        checkpoint = load_object(args.checkpoint)
        values = checkpoint.get("completedActionIds", [])
        if not isinstance(values, list) or not all(isinstance(item, str) for item in values):
            parser.error("checkpoint.completedActionIds must be an array of strings")
        completed = set(values)
    plan = build_plan(inventory_entries(load_object(args.inventory)),
                      mapping_by_document(load_object(args.mapping)),
                      args.batch_size, args.resume_from, completed)
    plan["generatedAt"] = datetime.now(timezone.utc).isoformat()
    plan["audit"] = {
        "operation": "milvus-vector-migration-plan", "writesToMilvus": False,
        "unknownTenantPolicy": "quarantine", "mappingApprovalRequired": True,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        json.dump(plan, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
