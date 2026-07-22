import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

let appSandbox;

function loadAppPrototype() {
  const source = readFileSync('src/main/resources/static/app.js', 'utf8');
  const sandbox = {
    console,
    window: {},
    document: {
      addEventListener() {},
      createElement() {
        return {
          _text: '',
          set textContent(value) {
            this._text = String(value ?? '');
            this.innerHTML = this._text
              .replace(/&/g, '&amp;')
              .replace(/</g, '&lt;')
              .replace(/>/g, '&gt;')
              .replace(/"/g, '&quot;')
              .replace(/'/g, '&#039;');
          },
          get textContent() {
            return this._text;
          },
          innerHTML: ''
        };
      }
    },
    clearTimeout() {},
    fetch(...args) {
      return sandbox.fetchImpl(...args);
    },
    fetchImpl() {
      throw new Error('fetchImpl not configured');
    },
    Headers,
    setTimeout() {}
  };
  vm.createContext(sandbox);
  vm.runInContext(`${source}\nglobalThis.SuperBizAgentApp = SuperBizAgentApp;`, sandbox);
  appSandbox = sandbox;
  return sandbox.SuperBizAgentApp.prototype;
}

const prototype = loadAppPrototype();
const app = Object.create(prototype);

function createAppForTest() {
  return Object.create(prototype);
}

function createClassListForTest() {
  const classes = new Set();
  return {
    add(value) {
      classes.add(value);
    },
    contains(value) {
      return classes.has(value);
    }
  };
}

const evidence = [
  {
    id: 'ev-cpu-15m',
    type: 'tool_call',
    toolName: 'queryMetricTrend',
    success: true,
    summary: 'cpu_usage 最近 15m 持续上升，latest=94.00，anomalous=true',
    queryParams: '{"metric":"cpu_usage","window":"15m"}',
    timeRange: '15m',
    attemptCount: 2,
    durationMs: 1240,
    retryable: true,
    rawFragment: JSON.stringify({
      metric: 'cpu_usage',
      window: '15m',
      points: [{ timestamp: 't1', value: 50 }, { timestamp: 't2', value: 94 }],
      summary: { latest: 94, avg: 72, direction: 'increasing', anomalous: true }
    })
  },
  {
    id: 'ev-cpu-1h',
    type: 'tool_call',
    toolName: 'queryMetricTrend',
    success: true,
    summary: 'cpu_usage 最近 1h 持续上升，latest=94.00，anomalous=true',
    queryParams: '{"metric":"cpu_usage","window":"1h"}',
    timeRange: '1h'
  },
  {
    id: 'ev-logs',
    type: 'tool_call',
    toolName: 'queryLogs',
    success: true,
    summary: '成功查询到 5 条 ERROR 日志',
    queryParams: '{"query":"level:ERROR"}',
    attemptCount: 1,
    durationMs: 80,
    retryable: true
  },
  {
    id: 'ev-logs-open',
    type: 'tool_call',
    toolName: 'queryLogs',
    success: false,
    summary: 'CLS 日志查询被熔断',
    queryParams: '{"query":"level:ERROR","service":"payment-service"}',
    errorCode: 'CIRCUIT_OPEN',
    errorMessage: '日志依赖熔断',
    attemptCount: 0,
    durationMs: 0,
    retryable: true
  },
  {
    id: 'ev-docs',
    type: 'tool_call',
    toolName: 'queryInternalDocs',
    success: true,
    summary: '命中 CPU 使用率过高处理方案',
    queryParams: '{"query":"CPU SOP"}'
  },
  {
    id: 'ev-case',
    type: 'tool_call',
    toolName: 'searchSimilarIncidentCases',
    success: true,
    summary: '召回 1 个相似历史故障案例',
    queryParams: '{"incidentId":"inc-1"}'
  }
];

const html = app.renderDiagnosisEvidenceList(evidence);

assert.match(html, /关键证据摘要/);
assert.match(html, /指标趋势/);
assert.match(html, /日志查询/);
assert.match(html, /知识库检索/);
assert.match(html, /相似历史案例/);
assert.match(html, /ev-cpu-15m/);
assert.match(html, /cpu_usage 最近 15m/);
assert.match(html, /证据统计/);
assert.match(html, /成功 5/);
assert.match(html, /失败 1/);
assert.match(html, /重试 1/);
assert.match(html, /总耗时 1\.3s/);
assert.match(html, /尝试 2 次/);
assert.match(html, /耗时 1\.2s/);
assert.match(html, /可重试/);
assert.match(html, /依赖熔断/);
assert.match(html, /日志依赖熔断/);
assert.doesNotMatch(html, /<div class="diagnosis-evidence-meta">\{&quot;metric&quot;:&quot;cpu_usage&quot;,&quot;window&quot;:&quot;15m&quot;\}<\/div>/);
assert.match(html, /查看参数/);
assert.match(html, /查看趋势图/);

test('deriveIncidentWorkbenchSummary counts evidence states', () => {
  const app = createAppForTest();
  const detail = {
    severity: 'critical',
    status: 'PROCESSING',
    alertCount: 3,
    diagnosisRuns: [{
      status: 'COMPLETED',
      humanReviewStatus: 'PENDING',
      evidence: [
        { type: 'tool_call', success: true, durationMs: 120 },
        { type: 'tool_call', success: false, errorCode: 'DEPENDENCY_ERROR', durationMs: 80 },
        { type: 'tool_call', success: true, attemptCount: 2, durationMs: 200 },
        { type: 'note', success: false, attemptCount: 5, durationMs: 999 }
      ]
    }]
  };

  const summary = app.deriveIncidentWorkbenchSummary(detail);

  assert.equal(summary.severity, 'critical');
  assert.equal(summary.incidentStatus, 'PROCESSING');
  assert.equal(summary.latestRunStatus, 'COMPLETED');
  assert.equal(summary.humanReviewStatus, 'PENDING');
  assert.equal(summary.evidenceCount, 3);
  assert.equal(summary.failedEvidenceCount, 1);
  assert.equal(summary.skippedEvidenceCount, 0);
  assert.equal(summary.retriedEvidenceCount, 1);
  assert.equal(summary.totalDurationMs, 400);
});

test('incident detail distinguishes queued and waiting-tool diagnosis states', () => {
  const app = createAppForTest();
  assert.match(app.renderDiagnosisPendingReport('QUEUED'), /诊断任务已入队，等待后台任务开始/);
  assert.match(app.renderDiagnosisPendingReport('WAITING_TOOL'), /诊断正在等待工具返回，请稍后刷新详情/);
  assert.match(app.renderDiagnosisPendingReport('RUNNING'), /诊断任务正在执行，请稍后刷新详情/);
});

test('intermediate diagnosis output is not treated as a final report', () => {
  const app = createAppForTest();
  assert.equal(app.isIntermediateDiagnosisReport('{"decision":"PLAN","step":"queryLogs"}'), true);
  assert.equal(app.isIntermediateDiagnosisReport('# 告警分析报告\n\n## 结论'), false);
  assert.match(app.renderDiagnosisInvalidReport(), /未生成最终告警分析报告/);
});

test('deriveDiagnosisTimeline creates ordered incident and evidence events', () => {
  const app = createAppForTest();
  const detail = {
    createdAt: 1000,
    lastAlertAt: 2000,
    alertCount: 1,
    diagnosisRuns: [{
      runId: 'run-1',
      status: 'FAILED',
      createdAt: 2100,
      startedAt: 2200,
      completedAt: 2500,
      errorMessage: 'model failed',
      evidence: [
        {
          id: 'ev-1',
          type: 'tool_call',
          toolName: 'queryLogs',
          success: false,
          errorCode: 'DEPENDENCY_ERROR',
          createdAt: 2300
        }
      ]
    }]
  };

  const events = app.deriveDiagnosisTimeline(detail, detail.diagnosisRuns[0]);

  assert.deepEqual(Array.from(events, event => event.kind), [
    'incident-created',
    'latest-alert',
    'run-created',
    'run-started',
    'tool-failed',
    'run-failed'
  ]);
  assert.equal(events[4].title, 'queryLogs 调用失败');
});

test('deriveDiagnosisTimeline keeps missing timestamps stable', () => {
  const app = createAppForTest();
  const detail = {
    diagnosisRuns: [{
      status: 'COMPLETED',
      completedAt: 3000,
      evidence: [
        {
          id: 'ev-open',
          type: 'tool_call',
          toolName: 'queryLogs',
          success: false,
          errorCode: 'CIRCUIT_OPEN'
        }
      ]
    }]
  };

  const events = app.deriveDiagnosisTimeline(detail, detail.diagnosisRuns[0]);

  assert.deepEqual(Array.from(events, event => event.kind), [
    'incident-created',
    'run-created',
    'tool-breaker-open',
    'run-completed'
  ]);
  assert.equal(events[0].timestamp, 0);
});

test('deriveDiagnosisTimeline keeps latest alert event when timestamp is missing', () => {
  const app = createAppForTest();
  const detail = {
    createdAt: 1000,
    alertCount: 2
  };

  const events = app.deriveDiagnosisTimeline(detail, null);
  const latestAlert = events.find(event => event.kind === 'latest-alert');

  assert.ok(latestAlert);
  assert.equal(latestAlert.timestamp, 0);
});

test('renderIncidentWorkbenchSummary returns status tiles', () => {
  const app = createAppForTest();
  const html = app.renderIncidentWorkbenchSummary({
    severity: 'critical',
    incidentStatus: 'PROCESSING',
    alertCount: 2,
    latestRunStatus: 'RUNNING',
    humanReviewStatus: 'PENDING',
    evidenceCount: 4,
    failedEvidenceCount: 1,
    retriedEvidenceCount: 1,
    totalDurationMs: 320
  });

  assert.match(html, /incident-workbench-summary/);
  assert.match(html, /critical/);
  assert.match(html, /RUNNING/);
  assert.match(html, /实际失败/);
  assert.match(html, /incident-workbench-summary-more/);
});

test('calculateDiagnosisEvidenceStats separates policy skips from real failures', () => {
  const app = createAppForTest();
  const stats = app.calculateDiagnosisEvidenceStats([
    { type: 'tool_call', success: true },
    { type: 'tool_call', success: false, errorCode: 'DEPENDENCY_ERROR', attemptCount: 1 },
    { type: 'tool_call', success: false, errorCode: 'TOOL_DUPLICATE_SKIPPED', attemptCount: 0 },
    { type: 'tool_call', success: false, errorCode: 'TOOL_BUDGET_EXCEEDED' },
    { type: 'tool_call', success: false, errorCode: 'TOOL_BUDGET_EXCEEDED', attemptCount: 0 }
  ]);

  assert.equal(stats.successCount, 1);
  assert.equal(stats.failedCount, 1);
  assert.equal(stats.skippedCount, 3);
  assert.equal(stats.duplicateSkippedCount, 1);
  assert.equal(stats.budgetSkippedCount, 2);
});

test('renderMetricTrendChart exposes scale, threshold and accessible points', () => {
  const app = createAppForTest();
  const html = app.renderMetricTrendChart({
    toolName: 'queryMetricTrend',
    timeRange: '15m',
    rawFragment: JSON.stringify({
      metric: 'cpu_usage',
      window: '15m',
      points: [
        { timestamp: '2026-07-17T09:00:00Z', value: 55 },
        { timestamp: '2026-07-17T09:05:00Z', value: 88 },
        { timestamp: '2026-07-17T09:10:00Z', value: 94 }
      ],
      summary: { latest: 94, avg: 79, direction: 'increasing', anomalous: true }
    })
  });

  assert.match(html, /trend-chart-axis-label/);
  assert.match(html, /trend-chart-threshold/);
  assert.match(html, /阈值 80\.0%/);
  assert.match(html, /<title>cpu_usage 趋势图<\/title>/);
  assert.match(html, /\d{2}:00/);
  assert.match(html, /最新 94\.0%/);
});

test('renderMetricTrendChart renders JVM GC unit and threshold', () => {
  const app = createAppForTest();
  const html = app.renderMetricTrendChart({
    toolName: 'queryMetricTrend',
    timeRange: '15m',
    rawFragment: JSON.stringify({
      metric: 'jvm_gc_collection_seconds_count',
      window: '15m',
      points: [
        { timestamp: '2026-07-17T09:00:00Z', value: 0.4 },
        { timestamp: '2026-07-17T09:05:00Z', value: 1.2 }
      ],
      summary: { latest: 1.2, avg: 0.8, direction: 'increasing', anomalous: true }
    })
  });

  assert.match(html, /阈值 1\.00次\/s/);
  assert.match(html, /最新 1\.20次\/s/);
});

test('renderMetricTrendChart keeps finite coordinates for a single sample', () => {
  const app = createAppForTest();
  const html = app.renderMetricTrendChart({
    toolName: 'queryMetricTrend',
    timeRange: '15m',
    rawFragment: JSON.stringify({
      metric: 'cpu_usage',
      window: '15m',
      points: [{ timestamp: '2026-07-17T09:00:00Z', value: 55 }],
      summary: { latest: 55, avg: 55, direction: 'stable', anomalous: false }
    })
  });

  assert.doesNotMatch(html, /NaN/);
  assert.match(html, /最新 55\.0%/);
});

test('renderDiagnosisEvidenceList opens risky groups and labels status', () => {
  const app = createAppForTest();
  const html = app.renderDiagnosisEvidenceList([
    {
      id: 'ev-failed',
      type: 'tool_call',
      toolName: 'queryLogs',
      success: false,
      errorCode: 'DEPENDENCY_ERROR',
      summary: '日志依赖异常'
    },
    {
      id: 'ev-ok',
      type: 'tool_call',
      toolName: 'queryInternalDocs',
      success: true,
      summary: '命中文档'
    }
  ]);

  assert.match(html, /diagnosis-evidence-group risk-group" open/);
  assert.match(html, /含异常/);
  assert.match(html, /DEPENDENCY_ERROR/);
});

test('policy-skipped evidence is neutral and does not mark its group risky', () => {
  const app = createAppForTest();
  const html = app.renderDiagnosisEvidenceList([
    {
      id: 'ev-skipped',
      type: 'tool_call',
      toolName: 'queryLogs',
      success: false,
      errorCode: 'TOOL_DUPLICATE_SKIPPED',
      attemptCount: 0,
      summary: '重复调用已跳过'
    }
  ]);

  assert.match(html, /diagnosis-evidence-status skipped/);
  assert.match(html, /已跳过/);
  assert.doesNotMatch(html, /diagnosis-evidence-group risk-group/);
});

test('policy-like error with attempts remains a real failure', () => {
  const app = createAppForTest();
  const html = app.renderDiagnosisEvidenceList([
    {
      id: 'ev-real-failure',
      type: 'tool_call',
      toolName: 'queryLogs',
      success: false,
      errorCode: 'TOOL_BUDGET_EXCEEDED',
      attemptCount: 1,
      summary: '工具实际执行后失败'
    }
  ]);

  assert.match(html, /diagnosis-evidence-status failed/);
  assert.doesNotMatch(html, /已跳过/);
});

test('renderAlertHistoryFilters groups controls into two rows', () => {
  const app = createAppForTest();
  app.alertHistoryFilters = {
    status: 'OPEN',
    severity: 'critical',
    latestRunStatus: 'RUNNING',
    humanReviewStatus: 'UNREVIEWED',
    q: 'cpu'
  };

  const html = app.renderAlertHistoryFilters();

  assert.match(html, /alert-history-filter-row-primary/);
  assert.match(html, /alert-history-filter-row-secondary/);
  assert.match(html, /alert-history-filter-actions/);
  assert.equal((html.match(/alert-history-filter-row /g) || []).length, 2);
  assert.match(html, /value="cpu"/);
  assert.match(html, /value="OPEN" selected/);
  assert.match(html, /value="RUNNING" selected/);
});

test('renderIncidentWorkbenchSummary escapes tile values', () => {
  const app = createAppForTest();
  const html = app.renderIncidentWorkbenchSummary({
    severity: '<script>alert(1)</script>',
    incidentStatus: 'OPEN',
    alertCount: 1,
    latestRunStatus: 'RUNNING',
    humanReviewStatus: 'PENDING',
    evidenceCount: 0,
    failedEvidenceCount: 0,
    retriedEvidenceCount: 0,
    totalDurationMs: 0
  });

  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('renderDiagnosisTimeline returns timeline items', () => {
  const app = createAppForTest();
  const html = app.renderDiagnosisTimeline([
    { kind: 'run-started', timestamp: 1000, title: '开始 AI Ops 诊断', detail: '', tone: 'active' },
    { kind: 'tool-failed', timestamp: 2000, title: 'queryLogs 调用失败', detail: 'DEPENDENCY_ERROR', tone: 'warning' }
  ]);

  assert.match(html, /incident-workbench-timeline/);
  assert.match(html, /开始 AI Ops 诊断/);
  assert.match(html, /queryLogs 调用失败/);
  assert.match(html, /DEPENDENCY_ERROR/);
});

test('renderDiagnosisTimeline escapes event content', () => {
  const app = createAppForTest();
  const html = app.renderDiagnosisTimeline([
    { kind: 'tool-failed', timestamp: 1000, title: '<script>alert(1)</script>', detail: '<b>DEPENDENCY_ERROR</b>', tone: 'warning' }
  ]);

  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(html, /&lt;b&gt;DEPENDENCY_ERROR&lt;\/b&gt;/);
  assert.doesNotMatch(html, /<script>/);
  assert.doesNotMatch(html, /<b>DEPENDENCY_ERROR<\/b>/);
});

test('showAlertDetail renders workbench summary before detail sections', async () => {
  const app = createAppForTest();
  const detailContent = {
    innerHTML: '',
    scrollTop: 0,
    querySelector() {
      return null;
    }
  };
  app.apiBaseUrl = '/api';
  app.alertDetailPanel = {
    style: {},
    classList: createClassListForTest()
  };
  app.alertDetailContent = detailContent;
  app.incidentDetailRefreshTimer = null;
  app.incidentDetailRenderSignature = null;
  appSandbox.fetchImpl = async () => ({
    ok: true,
    json: async () => ({
      data: {
        id: 'inc-order',
        aggregationKey: 'order-key',
        title: 'Order test incident',
        status: 'OPEN',
        severity: 'critical',
        alertCount: 1,
        createdAt: 1000,
        alertPayloads: [],
        diagnosisRuns: []
      }
    })
  });

  await app.showAlertDetail('inc-order');

  const summaryIndex = detailContent.innerHTML.indexOf('incident-workbench-summary');
  const firstDetailSectionIndex = detailContent.innerHTML.indexOf('alert-detail-section');
  assert.ok(summaryIndex >= 0);
  assert.ok(firstDetailSectionIndex >= 0);
  assert.ok(summaryIndex < firstDetailSectionIndex);
});

console.log('evidence rendering tests passed');
