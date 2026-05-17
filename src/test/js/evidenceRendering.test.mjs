import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
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

const evidence = [
  {
    id: 'ev-cpu-15m',
    type: 'tool_call',
    toolName: 'queryMetricTrend',
    success: true,
    summary: 'cpu_usage 最近 15m 持续上升，latest=94.00，anomalous=true',
    queryParams: '{"metric":"cpu_usage","window":"15m"}',
    timeRange: '15m',
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
    queryParams: '{"query":"level:ERROR"}'
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
assert.doesNotMatch(html, /<div class="diagnosis-evidence-meta">\{&quot;metric&quot;:&quot;cpu_usage&quot;,&quot;window&quot;:&quot;15m&quot;\}<\/div>/);
assert.match(html, /查看参数/);
assert.match(html, /查看趋势图/);

console.log('evidence rendering tests passed');
