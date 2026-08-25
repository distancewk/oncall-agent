package org.example.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.IncidentCaseService;
import org.example.service.IncidentService;
import org.example.dto.IncidentRecord;
import org.example.service.VectorSearchService.SearchResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 历史案例召回评测（指标2：Top-3 命中 80%+）。
 *
 * 设计：复用项目真实检索路径 {@link IncidentCaseService#findSimilarCases}，
 * 从标注集（qrels）读取 incidentId，调用与线上完全一致的混合检索 + Rerank，
 * 导出 run.json（query -> Top-3 案例 id），再交给 eval/case_recall_eval.py 算 Recall@K / MRR。
 *
 * 前置：
 *  1) 先积累 >=30 条归档案例（跑诊断 -> 前端确认 CONFIRMED -> archive-case 写入 Milvus incident_case）。
 *  2) eval/case_recall_dataset.json 为标注集：{"<incidentId>": ["<relevant_case_id>", ...]}。
 *     相关案例可由同 alertname + 同 service 的历史 CONFIRMED 诊断聚类得到。
 *
 * 默认 @Disabled：避免无 Milvus / 无标注集时 CI 失败。有环境时移除 @Disabled 运行：
 *   mvn -Dspring.profiles.active=dev test -Dtest=CaseRecallEvalTest
 */
@SpringBootTest
@Disabled("需要 Milvus + DashScope embedding + 标注集；积累 >=30 条 incident_case 后移除 @Disabled")
class CaseRecallEvalTest {

    @Autowired
    private IncidentCaseService incidentCaseService;

    @Autowired
    private IncidentService incidentService;

    private static final String DATASET = "eval/case_recall_dataset.json";
    private static final String RUN = "eval/run.json";

    @Test
    void exportRunAndEvaluate() throws Exception {
        ObjectMapper om = new ObjectMapper();
        File datasetFile = new File(DATASET);
        if (!datasetFile.exists()) {
            throw new IllegalStateException("缺少标注集 " + DATASET + "，请先按 README_metrics.md 构建 qrels");
        }
        Map<String, List<String>> dataset = om.readValue(datasetFile,
                new TypeReference<Map<String, List<String>>>() {});

        Map<String, List<String>> run = new LinkedHashMap<>();
        for (String incidentId : dataset.keySet()) {
            IncidentRecord inc = incidentService.getIncident(incidentId)
                    .orElseThrow(() -> new IllegalStateException("incident 不存在: " + incidentId));
            List<SearchResult> top = incidentCaseService.findSimilarCases(inc, 3); // 真实检索路径
            run.put(incidentId, top.stream().map(SearchResult::getId).collect(Collectors.toList()));
        }
        om.writerWithDefaultPrettyPrinter().writeValue(new File(RUN), run);

        // 交给 Python harness 算分（召回评测逻辑与项目解耦，零依赖）
        Process p = new ProcessBuilder("python3", "eval/case_recall_eval.py",
                        "--qrels", DATASET, "--run", RUN, "--k", "3", "--target", "0.8")
                .inheritIO().start();
        int rc = p.waitFor();
        if (rc != 0) {
            throw new AssertionError("Recall@3 未达标阈值 0.8（或将 run.json 人工核对）");
        }
    }
}
