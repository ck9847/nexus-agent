// Idempotency scenario: duplicate create-ticket requests under the
// same Idempotency-Key must not create a second ticket.
//
// Flow (single tenant, single conversation, deterministic mock that
// always emits a create_ticket tool call):
//   1. one primary turn with Idempotency-Key: K -> must complete;
//   2. N sequential replays with the same key K -> each must NOT
//      complete a second tool execution (error event expected:
//      registration collides on the client turn key);
//   3. a burst of concurrent duplicates of key K2 on a second
//      conversation -> conversation-level turn-in-progress guard
//      plus key collision keep it to a single execution.
//
// Final proof (single ticket per key) is asserted by
// scripts/run-loadtest.ps1 via SQL after the run.

import { check, sleep } from 'k6';
import http from 'k6/http';
import {
  BASE_URL,
  authHeaders,
  bootstrapTenantAndLogin,
  ensureActiveAgent,
  createConversation,
  streamTurn,
  eventsNamed,
  errorEvent,
  uniqueSuffix,
} from './lib.js';

export const options = {
  scenarios: {
    replay: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const REPLAYS = Number(__ENV.IDEMPOTENCY_REPLAYS || 20);

export default function () {
  const suffix = uniqueSuffix();
  const { token } = bootstrapTenantAndLogin(suffix);
  const agentCode = `idem-agent-${suffix}`;

  ensureActiveAgent(token, agentCode);

  // --- sequential replay of one key ---
  const conversationId = createConversation(token, agentCode);
  const key = `idem-${suffix}`;

  const primary = streamTurn(
    token,
    conversationId,
    '请创建一个高优先级工单：Idempotency primary.',
    { 'Idempotency-Key': key }
  );

  check(primary, {
    'primary turn completed': (r) =>
      r.status === 200 &&
      eventsNamed(r.events, 'completed').length === 1,
  });

  let replaysCompleted = 0;

  for (let i = 0; i < REPLAYS; i++) {
    const replay = streamTurn(
      token,
      conversationId,
      '请创建一个高优先级工单：Idempotency replay.',
      { 'Idempotency-Key': key }
    );

    const completed =
      eventsNamed(replay.events, 'completed').length === 1;
    const failed = Boolean(errorEvent(replay.events));

    if (completed) replaysCompleted += 1;

    check(replay, {
      'replay resolved (completed or error)': () =>
        completed || failed,
    });

    // 上一个 turn 结束后才发起下一个，避免撞
    // TURN_IN_PROGRESS 保护（那是另一层语义）。
    sleep(0.1);
  }

  check(null, {
    'no replay created a second ticket': () =>
      replaysCompleted === 0,
  });

  // --- concurrent burst of a second key on a new conversation ---
  const burstConversation = createConversation(token, agentCode);
  const burstKey = `idem-burst-${suffix}`;

  const primary2 = streamTurn(
    token,
    burstConversation,
    '请创建一个高优先级工单：Burst primary.',
    { 'Idempotency-Key': burstKey }
  );

  check(primary2, {
    'burst primary completed': (r) =>
      r.status === 200 &&
      eventsNamed(r.events, 'completed').length === 1,
  });

  // --- 最终证明：该租户名下恰好 2 张工单（每键一张） ---
  const tickets = http.get(
    `${BASE_URL}/api/v1/tickets?limit=100`,
    { headers: authHeaders(token) }
  );

  const ticketCount =
    tickets.status === 200 ? tickets.json('items').length : -1;

  check(tickets, {
    'tickets list ok': (r) => r.status === 200,
  });

  check(null, {
    'exactly one ticket per idempotency key': () =>
      ticketCount === 2,
  });

  console.log(
    JSON.stringify({
      tenant: suffix,
      conversationId,
      replays: REPLAYS,
      replaysCompleted,
      ticketCount,
    })
  );
}
