# Diagnosis evaluation baseline

This directory defines the first deterministic contract for offline diagnosis evaluation.
The evaluator does not call a model. An adapter or manually reviewed run emits one JSON
object per scenario with predicted root causes, the actual diagnosis report, structured
claims, and cost/latency fields. Scenario annotations are reviewed by
an SRE owner before being promoted into the release gate.

Run the smoke baseline with:

```bash
make eval
```

The input files are configurable so a reviewed adapter can run the same scorer
against a de-identified incident set:

```bash
make eval-release \
  EVAL_SCENARIOS=path/to/scenarios.json \
  EVAL_RESULTS=path/to/results.jsonl \
  EVAL_METADATA=path/to/metadata.json \
  EVAL_GATE_CONFIG=path/to/release-gate.json
```

For a running SuperBizAgent instance, `make eval-live` captures the durable report
and evidence instead of accepting a hand-written result fixture:

```bash
make eval-live \
  EVAL_LIVE_SCENARIOS=path/to/approved-scenarios.json \
  EVAL_LIVE_RESULTS=target/real-results.jsonl \
  EVAL_LIVE_BASE_URL=http://localhost:9900
```

Each live scenario must declare the normal evaluation annotations
(`expectedRootCauses`, `acceptableRootCauses`, `allowedEvidence`, `requiredEvidence`,
`claimEvidence`, `requiredClaimIds`, `allowHonestFailure`, and
`expectedHonestFailure`) plus `rootCausePatterns` and `claimMatchers`. The latter
are reviewed parsing rules, not labels inferred by the adapter. A scenario can
point at an existing de-identified `incidentId`, or provide an `alertPayload`; the
latter is submitted through the signed webhook and uses additive `X-Incident-ID`
and `X-Diagnosis-Run-ID` response headers to follow the exact durable run.

Approved live scenarios must also declare `evidenceMatchers`, mapping each stable
evidence alias in `allowedEvidence` to reviewed predicates such as `toolName`,
`queryPattern`, `summaryPattern`, and `timeRange`. Persisted evidence IDs are
randomized per run, so release scoring resolves citations through these aliases.
The adapter carries captured evidence summary/query fields into the result; release
scoring fails closed when a cited evidence record is absent, failed, or does not
share deterministic claim anchors with the cited claim.

`eval-release` fails closed unless metadata describes a non-smoke dataset whose
`dataset.source` is `deidentified-incident`, a real adapter/model/prompt/knowledge-base
run, and an annotation block that records `reviewedBy`, `reviewedAt`,
`policyVersion`, `expertReviewed: true`, `redactionApproved: true`, and a positive
`scenarioCount` matching the scenario file. It also requires non-empty
`approval.approvedBy` and `approval.approvedAt` fields. The approval and annotation
fields record governance evidence; they do not replace the actual expert labels in
each scenario.

When `--require-approved-dataset` is enabled, the evaluator also performs a small
automated redaction safety scan over scenarios and results. It rejects direct email
addresses, common credential/token formats, and non-redacted values under sensitive
field names. This scan is deliberately conservative and incomplete; it is a safety
net, not proof of de-identification, so human `redactionApproved` review remains
required.

An approved metadata file therefore has this minimum shape:

```json
{
  "schemaVersion": "3",
  "dataset": {
    "id": "payments-incidents",
    "version": "2026.08.01",
    "split": "validation",
    "owner": "sre",
    "source": "deidentified-incident"
  },
  "annotation": {
    "reviewedBy": "sre-reviewer",
    "reviewedAt": "2026-08-02T09:00:00Z",
    "policyVersion": "labels-v1",
    "expertReviewed": true,
    "redactionApproved": true,
    "scenarioCount": 42
  },
  "approval": {
    "approvedBy": "sre-owner",
    "approvedAt": "2026-08-02T10:00:00Z"
  }
}
```

The command writes machine-readable JSON and a Markdown summary under `target/`.
Every report also records the dataset, adapter, model, prompt, knowledge-base and
random-seed metadata, and the declared gate result.
The smoke fixtures are only a contract test; they are not evidence that the model is
accurate. Production evaluation must add de-identified incidents, expert root-cause
labels, acceptable alternatives, required evidence, and an adapter that captures the
actual diagnosis report.

Retrieval scenarios may additionally declare `retrievalRelevantIds`. The live adapter can
carry `retrievedDocumentIds`, `candidateDocumentIds`, and `rerankedDocumentIds` from a
durable retrieval trace. The evaluator reports retrieval recall, MRR, and citation hit rate
separately from diagnosis correctness. These optional fields are absent from legacy smoke
scenarios.

Metrics currently reported:

- root-cause Top-1 and Top-3 accuracy;
- evidence precision and recall against scenario `allowedEvidence` / `requiredEvidence`
  annotations and the captured DiagnosisRun evidence;
- claim-level evidence precision against the scenario `claimEvidence` mapping plus
  captured evidence content, which prevents a citation valid for one claim from
  silently supporting another;
- unsupported definitive-assertion rate;
- honest-failure accuracy;
- average tool calls and P95 duration.
- retrieval Recall@K, MRR, and citation hit rate when retrieval annotations are present.

The evaluator extracts `[evidence: id]` from the report, resolves volatile IDs through
the reviewed aliases, checks claim references against those citations, and in release
mode validates them against the persisted DiagnosisRun evidence content. A supplied
`citedEvidence`, `definitiveClaims`, or
`unsupportedDefinitiveClaims` field is treated only as a consistency assertion, never as
the source of truth.

Each scenario must declare `allowedEvidence`; `requiredEvidence` must be a subset of it,
must declare non-empty `requiredClaimIds`, and must declare `claimEvidence`, a mapping from
canonical `claimId` to the evidence IDs that are allowed to support that claim. Each result
must include the actual report and a non-empty `claims` array. A claim must include a unique
`claimId`, mark whether it is definitive, and list the evidence IDs it cites. Missing required
claims are reported as `claimCoverage < 1` instead of being silently treated as a successful
empty answer. This makes the evaluator's precision/recall and unsupported-assertion metrics
auditable from annotations plus the captured output.

The smoke gate only checks the fixture contract (scenario count and zero unsupported
assertions). It is intentionally not a model-quality gate. Replace the metadata,
scenarios, results and gate thresholds with an approved de-identified dataset before
using this command as a release decision.

The online Planner/Executor path uses DashScope's native JSON-object response mode,
while the application still validates the exact control schema and evidence ownership
in code. JSON-object mode is not a substitute for expert labels or release evaluation.
