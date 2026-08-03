import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("diagnosis_eval.py")
SPEC = importlib.util.spec_from_file_location("diagnosis_eval_under_test", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load evaluator from {MODULE_PATH}")
diagnosis_eval = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnosis_eval)


class DiagnosisEvalContractTest(unittest.TestCase):
    def metadata(self, **overrides):
        value = {
            "schemaVersion": "3",
            "dataset": {
                "id": "superbizagent-smoke",
                "version": "0.1.0",
                "split": "smoke",
                "owner": "platform",
            },
            "run": {
                "adapterVersion": "fixture-v1",
                "modelVersion": "not-run",
                "promptVersion": "not-run",
                "knowledgeBaseVersion": "not-run",
                "randomSeed": 0,
            },
        }
        value.update(overrides)
        return value

    def scenario(self, **overrides):
        value = {
            "id": "scenario-1",
            "expectedRootCauses": ["cpu_saturation"],
            "acceptableRootCauses": [],
            "allowedEvidence": ["metric_trend"],
            "requiredEvidence": ["metric_trend"],
            "requiredClaimIds": ["cpu_saturation"],
            "claimEvidence": {"cpu_saturation": ["metric_trend"]},
            "allowHonestFailure": False,
            "expectedHonestFailure": False,
        }
        value.update(overrides)
        return value

    def result(self, **overrides):
        value = {
            "scenarioId": "scenario-1",
            "predictedRootCauses": ["cpu_saturation"],
            "report": "CPU 饱和 [evidence: metric_trend]",
            "claims": [{
                "claimId": "cpu_saturation",
                "text": "CPU 饱和",
                "definitive": True,
                "evidence": ["metric_trend"],
            }],
            "honestFailure": False,
            "toolCalls": 1,
            "durationMs": 100,
        }
        value.update(overrides)
        return value

    def test_evidence_support_is_derived_from_annotation(self):
        report = "CPU 饱和 [evidence: metric_trend] 错误证据 [evidence: not-allowed]"
        result = self.result(
            report=report,
            supportedEvidence=["metric_trend", "not-allowed"],
            claims=[{
                "claimId": "cpu_saturation",
                "text": "CPU 饱和",
                "definitive": True,
                "evidence": ["not-allowed"],
            }],
        )

        evaluated = diagnosis_eval.evaluate([self.scenario()], [result])

        self.assertEqual(evaluated["metrics"]["evidencePrecision"], 0.5)
        self.assertEqual(evaluated["metrics"]["unsupportedAssertionRate"], 1.0)

    def test_report_citations_must_match_optional_declaration(self):
        result = self.result(citedEvidence=[])

        with self.assertRaisesRegex(ValueError, "citedEvidence does not match"):
            diagnosis_eval.evaluate([self.scenario()], [result])

    def test_required_evidence_must_be_allowed(self):
        scenario = self.scenario(requiredEvidence=["log_search"])

        with self.assertRaisesRegex(ValueError, "requiredEvidence must be a subset"):
            diagnosis_eval.evaluate([scenario], [self.result()])

    def test_allowed_evidence_is_required_in_scenario_annotations(self):
        scenario = self.scenario()
        del scenario["allowedEvidence"]

        with self.assertRaisesRegex(ValueError, "allowedEvidence is required"):
            diagnosis_eval.evaluate([scenario], [self.result()])

    def test_required_evidence_and_boolean_flags_are_strict(self):
        scenario = self.scenario()
        del scenario["requiredEvidence"]
        with self.assertRaisesRegex(ValueError, "requiredEvidence is required"):
            diagnosis_eval.evaluate([scenario], [self.result()])

        scenario = self.scenario()
        del scenario["claimEvidence"]
        with self.assertRaisesRegex(ValueError, "claimEvidence is required"):
            diagnosis_eval.evaluate([scenario], [self.result()])

        scenario = self.scenario(requiredEvidence=None)
        with self.assertRaisesRegex(ValueError, "requiredEvidence must be an array"):
            diagnosis_eval.evaluate([scenario], [self.result()])

        result = self.result(honestFailure="false")
        with self.assertRaisesRegex(ValueError, "honestFailure must be a boolean"):
            diagnosis_eval.evaluate([self.scenario()], [result])

    def test_required_claim_coverage_is_reported_when_result_omits_a_claim(self):
        scenario = self.scenario(
            requiredClaimIds=["cpu_saturation", "service_degraded"],
            claimEvidence={
                "cpu_saturation": ["metric_trend"],
                "service_degraded": ["metric_trend"],
            },
        )
        evaluated = diagnosis_eval.evaluate([scenario], [self.result()])
        self.assertEqual(0.5, evaluated["metrics"]["claimCoverage"])
        self.assertEqual(["service_degraded"], evaluated["rows"][0]["missingRequiredClaims"])

    def test_required_claim_ids_must_not_be_empty(self):
        scenario = self.scenario()
        scenario["requiredClaimIds"] = []
        with self.assertRaisesRegex(ValueError, "requiredClaimIds must not be empty"):
            diagnosis_eval.evaluate([scenario], [self.result()])

    def test_claim_evidence_support_is_not_just_allowed_evidence(self):
        scenario = self.scenario(claimEvidence={"cpu_saturation": ["other-evidence"]})
        scenario["allowedEvidence"] = ["metric_trend", "other-evidence"]
        scenario["requiredEvidence"] = ["metric_trend"]
        evaluated = diagnosis_eval.evaluate([scenario], [self.result()])
        self.assertEqual(evaluated["metrics"]["claimEvidencePrecision"], 0.0)
        self.assertEqual(evaluated["metrics"]["unsupportedAssertionRate"], 1.0)

    def test_strict_grounding_requires_persisted_evidence_and_checks_content(self):
        scenario = self.scenario()
        result = self.result(
            report="CPU 使用率持续上升 [evidence: ev-1]",
            claims=[{
                "claimId": "cpu_saturation",
                "text": "CPU 使用率持续上升",
                "definitive": True,
                "evidence": ["ev-1"],
            }],
            evidence=[{
                "id": "ev-1",
                "toolName": "queryMetricTrend",
                "queryParams": '{"metric":"cpu_usage"}',
                "timeRange": "15m",
                "summary": "cpu_usage 最近 15m 持续上升，latest=94.00",
                "success": True,
            }],
            evidenceAliases={"ev-1": "metric_trend"},
        )

        evaluated = diagnosis_eval.evaluate([scenario], [result], strict_grounding=True)

        self.assertEqual(evaluated["metrics"]["claimEvidencePrecision"], 1.0)
        self.assertEqual(evaluated["metrics"]["unsupportedAssertionRate"], 0.0)

    def test_strict_grounding_rejects_citation_not_in_diagnosis_run(self):
        result = self.result(
            report="CPU 饱和 [evidence: ev-missing]",
            evidence=[],
        )

        with self.assertRaisesRegex(ValueError, "not present in DiagnosisRun"):
            diagnosis_eval.evaluate([self.scenario()], [result], strict_grounding=True)

    def test_retrieval_metrics_use_rank_and_source_citations(self):
        scenario = self.scenario(retrievalRelevantIds=["doc-1"])
        result = self.result(
            report="CPU 饱和 [evidence: metric_trend] [来源: doc-1]",
            retrievedDocumentIds=["doc-2", "doc-1"],
        )

        evaluated = diagnosis_eval.evaluate([scenario], [result])

        self.assertEqual(evaluated["rows"][0]["retrievalRecallAtK"], 1.0)
        self.assertEqual(evaluated["rows"][0]["retrievalMrr"], 0.5)
        self.assertEqual(evaluated["rows"][0]["retrievalCitationHitRate"], 1.0)

    def test_p95_uses_nearest_rank(self):
        self.assertEqual(diagnosis_eval.percentile([1200, 1500, 1800], 0.95), 1800)

    def test_approved_metadata_rejects_smoke_fixture(self):
        with self.assertRaisesRegex(ValueError, "smoke dataset"):
            diagnosis_eval.validate_approved_metadata(self.metadata())

    def test_approved_metadata_requires_real_run_and_approval(self):
        metadata = self.metadata(
            dataset={"id": "superbizagent-prod", "version": "1.0", "split": "validation",
                     "owner": "sre", "source": "deidentified-incident"},
            run={
                "adapterVersion": "incident-adapter-1",
                "modelVersion": "qwen3-max-2026-01",
                "promptVersion": "prompt-42",
                "knowledgeBaseVersion": "kb-2026-07",
                "randomSeed": 42,
            },
        )
        with self.assertRaisesRegex(ValueError, "evaluation metadata.annotation"):
            diagnosis_eval.validate_approved_metadata(metadata)
        metadata["annotation"] = {
            "reviewedBy": "sre-reviewer",
            "reviewedAt": "2026-07-29T09:00:00Z",
            "policyVersion": "labels-v1",
            "expertReviewed": True,
            "redactionApproved": True,
            "scenarioCount": 1,
        }
        with self.assertRaisesRegex(ValueError, "evaluation metadata.approval"):
            diagnosis_eval.validate_approved_metadata(metadata)
        metadata["approval"] = {"approvedBy": "sre-owner", "approvedAt": "2026-07-29T10:00:00Z"}
        diagnosis_eval.validate_approved_metadata(metadata, 1)

    def test_approved_metadata_requires_matching_annotation_count(self):
        metadata = self.metadata(
            dataset={"id": "superbizagent-prod", "version": "1.0", "split": "validation",
                     "owner": "sre", "source": "deidentified-incident"},
            run={
                "adapterVersion": "incident-adapter-1",
                "modelVersion": "qwen3-max-2026-01",
                "promptVersion": "prompt-42",
                "knowledgeBaseVersion": "kb-2026-07",
                "randomSeed": 42,
            },
            annotation={
                "reviewedBy": "sre-reviewer",
                "reviewedAt": "2026-07-29T09:00:00Z",
                "policyVersion": "labels-v1",
                "expertReviewed": True,
                "redactionApproved": True,
                "scenarioCount": 2,
            },
            approval={"approvedBy": "sre-owner", "approvedAt": "2026-07-29T10:00:00Z"},
        )
        with self.assertRaisesRegex(ValueError, "scenarioCount must match"):
            diagnosis_eval.validate_approved_metadata(metadata, 1)

    def test_release_gate_requires_non_empty_thresholds(self):
        with self.assertRaisesRegex(ValueError, "non-empty thresholds"):
            diagnosis_eval.validate_release_gate_config({})
        diagnosis_eval.validate_release_gate_config({"thresholds": {"scenarioCount": {"min": 1}}})

    def test_approved_scenarios_require_stable_evidence_matchers(self):
        with self.assertRaisesRegex(ValueError, "requires evidenceMatchers"):
            diagnosis_eval.validate_approved_scenario_grounding([self.scenario()])

        scenario = self.scenario(evidenceMatchers={"metric_trend": {"toolName": "queryMetricTrend"}})
        diagnosis_eval.validate_approved_scenario_grounding([scenario])

    def test_approved_payload_rejects_email_addresses(self):
        scenario = self.scenario(description="Contact sre@example.com for escalation")

        with self.assertRaisesRegex(ValueError, "email address"):
            diagnosis_eval.validate_redaction_safety([scenario], [self.result()])

    def test_approved_payload_rejects_sensitive_fields_and_common_tokens(self):
        result = self.result(credentials={"apiKey": "live-api-key-value"})

        with self.assertRaisesRegex(ValueError, "sensitive field"):
            diagnosis_eval.validate_redaction_safety([self.scenario()], [result])

        result = self.result(credentials={"tokenRef": "vault/diagnosis-token"},
                             report="Token observed ghp_12345678901234567890")
        with self.assertRaisesRegex(ValueError, "probable secret or token"):
            diagnosis_eval.validate_redaction_safety([self.scenario()], [result])

    def test_approved_payload_accepts_redacted_values_and_references(self):
        scenario = self.scenario(
            credentials={"api_key": "[REDACTED]", "secretRef": "vault/diagnosis-token"},
            description="Use the approved escalation path.",
        )
        result = self.result(credentials={"password": "not-a-real-secret"})

        diagnosis_eval.validate_redaction_safety([scenario], [result])


if __name__ == "__main__":
    unittest.main()
