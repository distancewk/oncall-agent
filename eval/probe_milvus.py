#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探测 Milvus：列出集合、各集合行数，并统计 incident_case 数量。"""
import sys


def main():
    try:
        from pymilvus import MilvusClient
    except Exception as e:
        print("IMPORT_FAIL", e)
        sys.exit(2)
    try:
        client = MilvusClient(uri="http://localhost:19530", token="")
    except Exception as e:
        print("CONNECT_FAIL", e)
        sys.exit(2)

    cols = client.list_collections()
    print("COLLECTIONS:", cols)
    for c in cols:
        try:
            stats = client.get_collection_stats(collection_name=c)
            print(f"  {c} stats: {stats}")
        except Exception as e:
            print(f"  {c} STATS_ERR {e}")

    # 若集合存在标量字段 doc_type，统计 incident_case 行数
    for c in cols:
        try:
            res = client.query(
                collection_name=c,
                filter='doc_type == "incident_case"',
                output_fields=["id"],
                limit=1,
            )
            # pymilvus 新版支持 count 参数，这里用 query 拿不到总数，仅探字段是否可用
            print(f"  {c} query doc_type=incident_case OK (sample={len(res)} rows returned, limit=1)")
        except Exception as e:
            print(f"  {c} query incident_case ERR: {e}")


if __name__ == "__main__":
    main()
