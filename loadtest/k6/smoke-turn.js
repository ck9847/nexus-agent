// Smoke scenario: one full tool round on a fresh tenant.
// Verifies the load-test stack is wired correctly before ramping.

import { check } from 'k6';
import {
  bootstrapTenantAndLogin,
  ensureActiveAgent,
  createConversation,
  streamTurn,
  eventsNamed,
  uniqueSuffix,
} from './lib.js';

export const options = {
  scenarios: {
    smoke: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const suffix = uniqueSuffix();
  const { token } = bootstrapTenantAndLogin(suffix);
  const agentCode = `smoke-agent-${suffix}`;

  ensureActiveAgent(token, agentCode);

  const conversationId = createConversation(token, agentCode);
  const turn = streamTurn(
    token,
    conversationId,
    '请创建一个高优先级工单：Load test smoke.'
  );

  check(turn, {
    'turn http 200': (r) => r.status === 200,
    'started event': (r) =>
      eventsNamed(r.events, 'started').length === 1,
    'completed event': (r) =>
      eventsNamed(r.events, 'completed').length === 1,
    'no error event': (r) =>
      eventsNamed(r.events, 'error').length === 0,
  });
}
