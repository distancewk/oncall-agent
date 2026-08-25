#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
案例召回评测工具 —— 与 oncall-agent 历史故障召回路径对齐

输入格式（JSON）:
  qrels.json : {"<query_id>": ["<relevant_case_id>", ...], ...}   # 人工标注的相关案例
  run.json   : {"<query_id>": ["<retrieved_case_id>", ...], ...}  # 检索返回的 Top-K（已按相关度排序）

计算指标:
  Recall@K    : 相关案例是否出现在 Top-K（多相关取"任一命中即算"）
  Precision@K : Top-K 中相关占比（micro-average）
  MRR@K       : 第一个相关案例的排名倒数均值

用法:
  python case_recall_eval.py --qrels qrels.json --run run.json --k 3 --target 0.8
  python case_recall_eval.py --self-test        # 零依赖，验证算法正确性
"""
import json
import argparse
import sys


def recall_at_k(qrels, run, k):
    hits = 0
    total = 0
    for qid, rel in qrels.items():
        if not rel:
            continue
        total += 1
        retrieved = run.get(qid, [])[:k]
        if any(r in rel for r in retrieved):
            hits += 1
    return (hits / total) if total else 0.0


def precision_at_k(qrels, run, k):
    num = 0
    den = 0
    for qid, rel in qrels.items():
        if not rel:
            continue
        retrieved = run.get(qid, [])[:k]
        den += len(retrieved)
        num += sum(1 for r in retrieved if r in rel)
    return (num / den) if den else 0.0


def mrr(qrels, run, k):
    s = 0.0
    n = 0
    for qid, rel in qrels.items():
        if not rel:
            continue
        n += 1
        retrieved = run.get(qid, [])[:k]
        for rank, r in enumerate(retrieved, start=1):
            if r in rel:
                s += 1.0 / rank
                break
    return (s / n) if n else 0.0


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def run_self_test():
    qrels = {"q1": ["a", "b"], "q2": ["c"], "q3": ["x"]}
    run = {"q1": ["a", "z", "y"], "q2": ["d", "c", "e"], "q3": ["p", "q"]}
    r3 = recall_at_k(qrels, run, 3)
    assert abs(r3 - 2 / 3) < 1e-9, f"recall@3={r3}"
    m = mrr(qrels, run, 3)
    assert abs(m - 0.5) < 1e-9, f"mrr={m}"
    p3 = precision_at_k(qrels, run, 3)
    # micro: q1 1/3, q2 1/3, q3 0/2 -> (1+1+0)/(3+3+2)=2/8=0.25
    assert abs(p3 - 0.25) < 1e-9, f"precision@3={p3}"
    print("SELF-TEST PASSED: recall@3=%.4f mrr=%.4f precision@3=%.4f" % (r3, m, p3))


def main():
    ap = argparse.ArgumentParser(description="案例召回评测 (Recall@K / MRR / Precision@K)")
    ap.add_argument("--qrels", help="标注集 JSON 路径")
    ap.add_argument("--run", help="检索结果 run 文件 JSON 路径")
    ap.add_argument("--k", type=int, default=3)
    ap.add_argument("--target", type=float, default=0.8, help="Recall@K 达标阈值")
    ap.add_argument("--self-test", action="store_true", help="运行内置自测")
    args = ap.parse_args()

    if args.self_test:
        run_self_test()
        return

    if not args.qrels or not args.run:
        ap.error("需提供 --qrels 与 --run，或加 --self-test")

    qrels = load_json(args.qrels)
    run = load_json(args.run)
    k = args.k

    rec = recall_at_k(qrels, run, k)
    prec = precision_at_k(qrels, run, k)
    score = mrr(qrels, run, k)

    print("=" * 48)
    print("案例召回评测报告 (K=%d)" % k)
    print("=" * 48)
    print("样本量 (有标注 query 数) : %d" % len([q for q in qrels.values() if q]))
    print("Recall@%d                 : %.4f" % (k, rec))
    print("Precision@%d              : %.4f" % (k, prec))
    print("MRR@%d                    : %.4f" % (k, score))
    passed = rec >= args.target
    print("-" * 48)
    print("达标阈值 %.0f%% : %s" % (args.target * 100, "PASS" if passed else "FAIL"))
    print("=" * 48)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
