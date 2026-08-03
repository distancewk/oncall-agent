#!/usr/bin/env python3
"""Export the admin-only read-only Milvus vector inventory as JSON."""

import argparse
import json
from urllib.request import Request, urlopen


def fetch_page(base_url: str, api_token: str, limit: int, offset: int) -> dict:
    url = (base_url.rstrip("/")
           + f"/api/system/milvus-vectors/inventory?limit={limit}&offset={offset}")
    request = Request(url, headers={"X-API-Key": api_token, "Accept": "application/json"})
    with urlopen(request, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), dict):
        raise ValueError("inventory response must contain data object")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:9900")
    parser.add_argument("--api-token", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--max-pages", type=int, default=10_000)
    args = parser.parse_args()
    if args.limit < 1 or args.limit > 1_000:
        parser.error("--limit must be between 1 and 1000")
    if args.offset < 0:
        parser.error("--offset must be non-negative")
    if args.max_pages < 1:
        parser.error("--max-pages must be positive")

    first_offset = args.offset
    next_offset = first_offset
    pages = 0
    entries = []
    tagged = 0
    quarantined = 0
    while pages < args.max_pages:
        payload = fetch_page(args.base_url, args.api_token, args.limit, next_offset)
        page = payload["data"]
        page_entries = page.get("entries", [])
        if not isinstance(page_entries, list):
            raise ValueError("inventory data.entries must be an array")
        entries.extend(item for item in page_entries if isinstance(item, dict))
        tagged += int(page.get("tagged", 0))
        quarantined += int(page.get("quarantined", 0))
        pages += 1
        returned = len(page_entries)
        if returned < args.limit:
            break
        next_offset += returned
    else:
        raise RuntimeError("inventory exceeded --max-pages; use a larger bound or inspect pagination")

    payload = {
        "code": 200,
        "message": "success",
        "data": {
            "offset": first_offset,
            "limit": args.limit,
            "returned": len(entries),
            "tagged": tagged,
            "quarantined": quarantined,
            "pages": pages,
            "entries": entries,
        },
    }
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
