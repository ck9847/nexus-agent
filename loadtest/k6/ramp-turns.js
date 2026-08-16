// Ramp scenario: escalating concurrent SSE turns across isolated
// tenants (one tenant per VU, one conversation per iteration).
//
// Env knobs:
//   RAMP_STAGES   e.g. "2,15s:8,30s" (vus,duration pairs, ':'-joined)

import { check, sleep } from 'k6';
import {
  bootstrapTenantAndLogin,
  ensureActiveAgent,
  createConversation,
  streamTurn,
  eventsNamed,
  uniqueSuffix,
} from './lib.js';

function parseStages() {
  const raw = __ENV.RAMP_STAGES ||
    '2,15s:8,30s:16,60s:32,120s:8,30s';
  const stages = [];
  for (const pair of raw.split(':')) {
    const [target, duration] = pair.split(',');
    stages.push({ target: Number(target), duration });
  }
  return stages;
}

export const options = {
  scenarios: {
    ramp: {
      executor: 'ramping-vus',
      stages: parseStages(),
      gracefulRampDown: '15s',
    },
  },
  thresholds: {
    // 压测判定线：请求失败率 < 1%，turn 端到端 P95 < 45s
    //（mock 供应商 ~10 chunks * 5ms + 两轮模型 + DB 事务）。
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<45000'],
  },
};

export default function () {
  // setup() 为每个 VU 准备独立租户与 Agent（见下）。
  const ctx = orgContext();
  const conversationId = createConversation(
    ctx.token,
    ctx.agentCode,
    `Ramp conversation ${uniqueSuffix()}`
  );

  const turn = streamTurn(
    ctx.token,
    conversationId,
    '请创建一个高优先级工单：Ramp load turn.'
  );

  check(turn, {
    'turn http 200': (r) => r.status === 200,
    'completed event': (r) =>
      eventsNamed(r.events, 'completed').length === 1,
  });

  sleep(0.2);
}

// k6 executes setup() once; per-VU state (tenant + agent) is
// created lazily on first iteration of each VU.
const vuContexts = new Map();

function orgContext() {
  const key = `${__VU}`;
  let ctx = vuContexts.get(key);

  if (!ctx) {
    const suffix = uniqueSuffix();
    const { token } = bootstrapTenantAndLogin(suffix);
    const agentCode = `ramp-agent-${suffix}`;

    ensureActiveAgent(token, agentCode);

    ctx = { token, agentCode };
    vuContexts.set(key, ctx);
  }

  return ctx;
}
