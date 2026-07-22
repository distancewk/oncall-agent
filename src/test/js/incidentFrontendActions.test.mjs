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
    URLSearchParams,
    setTimeout() {}
  };
  vm.createContext(sandbox);
  vm.runInContext(`${source}\nglobalThis.SuperBizAgentApp = SuperBizAgentApp;`, sandbox);
  return sandbox.SuperBizAgentApp.prototype;
}

const prototype = loadAppPrototype();
const app = Object.create(prototype);
app.apiBaseUrl = 'http://localhost:9900/api';

assert.equal(
  app.buildIncidentHistoryQuery({
    status: 'OPEN',
    severity: 'critical',
    latestRunStatus: 'COMPLETED',
    q: 'payment service',
    humanReviewStatus: 'CONFIRMED'
  }),
  '?status=OPEN&severity=critical&latestRunStatus=COMPLETED&q=payment+service&humanReviewStatus=CONFIRMED'
);

assert.equal(app.buildIncidentHistoryQuery({}), '');

assert.equal(
  app.incidentDiagnosisUrl('inc/1', true),
  'http://localhost:9900/api/incidents/inc%2F1/diagnose?force=true'
);

assert.equal(
  app.incidentRunActionUrl('inc-1', 'run-1', 'confirm', '根因准确'),
  'http://localhost:9900/api/incidents/inc-1/runs/run-1/confirm?comment=%E6%A0%B9%E5%9B%A0%E5%87%86%E7%A1%AE'
);

assert.equal(
  app.incidentRunActionUrl('inc/1', 'run 1', 'cancel', '用户取消'),
  'http://localhost:9900/api/incidents/inc%2F1/runs/run%201/cancel?reason=%E7%94%A8%E6%88%B7%E5%8F%96%E6%B6%88'
);

console.log('incident frontend action tests passed');
