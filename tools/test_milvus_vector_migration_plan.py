import importlib.util
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("milvus_vector_migration_plan.py")
SPEC = importlib.util.spec_from_file_location("milvus_vector_migration_plan_under_test", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"cannot load planner from {MODULE_PATH}")
planner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(planner)


class MilvusVectorMigrationPlanTest(unittest.TestCase):

    def test_unknown_vectors_are_quarantined_and_known_vectors_rebuilt(self):
        entries = [
            {"id": "v-known", "documentId": "doc-1", "tenantId": "", "source": "/a"},
            {"id": "v-unknown", "documentId": "", "tenantId": "", "source": "/b"},
            {"id": "v-tagged", "documentId": "doc-2", "tenantId": "tenant-b", "source": "/c"},
        ]

        plan = planner.build_plan(entries, {"doc-1": "tenant-a"}, 10, 0, set())

        self.assertEqual(["REBUILD", "QUARANTINE", "KEEP_TAGGED"],
                         [item["action"] for item in plan["actions"]])
        self.assertTrue(plan["actions"][1]["requiresManualApproval"])
        self.assertFalse(plan["actions"][0]["requiresManualApproval"])

    def test_checkpoint_makes_action_idempotent_and_resume_is_bounded(self):
        entries = [
            {"id": "v-1", "documentId": "doc-1", "tenantId": "", "source": "/a"},
            {"id": "v-2", "documentId": "doc-2", "tenantId": "", "source": "/b"},
        ]
        first = planner.build_plan(entries, {"doc-1": "tenant-a", "doc-2": "tenant-b"},
                                   1, 0, set())
        action_id = first["actions"][0]["actionId"]
        resumed = planner.build_plan(entries, {"doc-1": "tenant-a", "doc-2": "tenant-b"},
                                     1, 1, {action_id})

        self.assertEqual(1, resumed["resumeFrom"])
        self.assertEqual("PENDING", resumed["actions"][0]["status"])
        self.assertEqual(1, resumed["counts"]["SKIPPED"])

    def test_next_resume_from_advances_only_by_emitted_batch(self):
        entries = [
            {"id": "v-1", "documentId": "doc-1", "tenantId": "", "source": "/a"},
            {"id": "v-2", "documentId": "doc-2", "tenantId": "", "source": "/b"},
            {"id": "v-3", "documentId": "doc-3", "tenantId": "", "source": "/c"},
        ]

        plan = planner.build_plan(entries, {"doc-1": "tenant-a", "doc-2": "tenant-b",
                                            "doc-3": "tenant-c"}, 1, 0, set())

        self.assertEqual(1, plan["nextResumeFrom"])
        self.assertEqual(["v-1"], [item["vectorId"] for item in plan["actions"]])


if __name__ == "__main__":
    unittest.main()
