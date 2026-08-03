import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("diagnosis_live_adapter.py")
SPEC = importlib.util.spec_from_file_location("diagnosis_live_adapter_under_test", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load adapter from {MODULE_PATH}")
adapter = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adapter)


class DiagnosisLiveAdapterTest(unittest.TestCase):

    def scenario(self):
        return {
            "id": "cpu-1",
            "incidentId": "incident-1",
            "expectedRootCauses": ["cpu_saturation"],
            "acceptableRootCauses": [],
            "allowedEvidence": ["metric_trend"],
            "requiredEvidence": ["metric_trend"],
            "requiredClaimIds": ["cpu_saturation"],
            "claimEvidence": {"cpu_saturation": ["metric_trend"]},
            "allowHonestFailure": False,
            "expectedHonestFailure": False,
            "rootCausePatterns": {"cpu_saturation": [r"CPU\s+饱和"]},
            "claimMatchers": {"cpu_saturation": [r"CPU\s+饱和"]},
        }

    def test_load_scenarios_requires_evaluation_annotations(self):
        scenario = self.scenario()
        del scenario["claimEvidence"]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "scenarios.json"
            path.write_text(json.dumps([scenario]), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "claimEvidence is required"):
                adapter.load_scenarios(path)

    def test_extracts_roots_and_claims_from_cited_report_lines(self):
        report = """# 告警分析报告
## 告警根因分析
CPU 饱和 [evidence: ev-metric]
## 结论
证据不足，等待人工复核
"""

        self.assertEqual(["cpu_saturation"], adapter.matched_roots(self.scenario(), report))
        self.assertEqual([{
            "claimId": "cpu_saturation",
            "text": "CPU 饱和 [evidence: ev-metric]",
            "definitive": True,
            "evidence": ["ev-metric"],
        }], adapter.matched_claims(self.scenario(), report))

    def test_submission_signs_exact_json_bytes_and_reads_correlation_headers(self):
        captured = {}

        def fake_request(base_url, path, method, body, headers, timeout):
            captured.update({"base_url": base_url, "path": path, "method": method,
                             "body": body, "headers": headers, "timeout": timeout})
            return 200, {"X-Incident-ID": "inc-1", "X-Diagnosis-Run-ID": "run-1"}, b"ok"

        with patch.object(adapter, "request_raw", side_effect=fake_request):
            incident_id, run_id = adapter.submit_webhook(
                "http://localhost:9900", {"status": "firing"}, "secret", 3.0)

        self.assertEqual(("inc-1", "run-1"), (incident_id, run_id))
        self.assertEqual("POST", captured["method"])
        self.assertEqual("/api/webhook/alert", captured["path"])
        self.assertEqual(b'{"status":"firing"}', captured["body"])
        self.assertTrue(captured["headers"]["X-Webhook-Signature"].startswith("sha256="))
        timestamp = captured["headers"]["X-Webhook-Timestamp"]
        nonce = captured["headers"]["X-Webhook-Nonce"]
        signed = f"{timestamp}.{nonce}.".encode() + captured["body"]
        expected = adapter.hmac.new(b"secret", signed, adapter.hashlib.sha256).hexdigest()
        self.assertEqual("sha256=" + expected, captured["headers"]["X-Webhook-Signature"])

    def test_to_result_counts_only_tool_evidence_and_detects_gap(self):
        scenario = self.scenario()
        run = {
            "runId": "run-1",
            "status": "COMPLETED_WITH_GAPS",
            "report": "# 告警分析报告\n## 告警根因分析\nCPU 饱和 [evidence: ev-1] [来源: doc-1]",
            "evidence": [
                {"id": "ev-context", "type": "alert_context", "toolName": None},
                {"id": "ev-metric", "type": "tool_call", "toolName": "queryMetricTrend",
                 "summary": "cpu_usage 最近 15m 持续上升", "success": True},
            ],
            "retrieval": {"retrievedDocumentIds": ["doc-1"]},
        }
        result = adapter.to_result(scenario, run, 12.5)
        self.assertEqual(1, result["toolCalls"])
        self.assertTrue(result["honestFailure"])
        self.assertEqual(12.5, result["durationMs"])
        self.assertEqual(["doc-1"], result["retrievedDocumentIds"])
        self.assertEqual(["doc-1"], result["citedDocumentIds"])
        self.assertEqual(["ev-context", "ev-metric"],
                         [item["id"] for item in result["evidence"]])

    def test_evidence_aliases_rejects_empty_matcher(self):
        scenario = {"id": "case-1", "evidenceMatchers": {"metric_trend": {}}}
        with self.assertRaisesRegex(ValueError, "must declare a selector"):
            adapter.evidence_aliases(scenario, [{"id": "ev-1"}])


if __name__ == "__main__":
    unittest.main()
