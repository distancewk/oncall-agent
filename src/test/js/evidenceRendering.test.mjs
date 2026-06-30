import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

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
    setTimeout() {}
  };
  vm.createContext(sandbox);
  vm.runInContext(`${source}\nglobalThis.SuperBizAgentApp = SuperBizAgentApp;`, sandbox);
  return sandbox.SuperBizAgentApp.prototype;
}

const prototype = loadAppPrototype();
const app = Object.create(prototype);

function createAppForTest() {
  return Object.create(prototype);
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
  assert.equal(summary.retriedEvidenceCount, 1);
  assert.equal(summary.totalDurationMs, 400);
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

console.log('evidence rendering tests passed');
