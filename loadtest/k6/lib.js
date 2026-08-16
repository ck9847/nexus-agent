// Shared helpers for nexus-agent k6 scenarios: tenant bootstrap,
// login, agent activation, conversation creation and one SSE turn.

import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:18090';

export function uniqueSuffix() {
  return `${Date.now()}-${__VU}-${Math.floor(Math.random() * 1e6)}`;
}

export function bootstrapTenantAndLogin(suffix, tenantLimitEnv) {
  const tenantCode = `load-${suffix}`;

  http.post(
    `${BASE_URL}/api/v1/tenants/bootstrap`,
    JSON.stringify({
      tenantCode,
      tenantName: 'Load Test Tenant',
      adminUsername: 'admin',
      adminEmail: `admin-${suffix}@loadtest.local`,
      adminPassword: 'LoadTest-Password-123',
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  const login = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({
      tenantCode,
      username: 'admin',
      password: 'LoadTest-Password-123',
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  if (login.status !== 200) {
    throw new Error(`login failed: ${login.status} ${login.body}`);
  }

  return {
    tenantCode,
    token: login.json('accessToken'),
  };
}

export function ensureActiveAgent(token, agentCode) {
  const headers = authHeaders(token);

  const created = http.post(
    `${BASE_URL}/api/v1/agents`,
    JSON.stringify({
      code: agentCode,
      name: 'Load Test Agent',
      description: 'Deterministic agent for load testing',
      systemPrompt:
        'You are a support agent. Create a ticket when asked. ' +
        'Always call create_ticket exactly once.',
      modelProvider: 'OPENAI',
      modelName: 'mock-model',
      modelConfig: { temperature: 0.2, maxOutputTokens: 1024 },
    }),
    { headers }
  );

  if (created.status !== 201 && created.status !== 200) {
    throw new Error(`agent create failed: ${created.status} ${created.body}`);
  }

  const activated = http.patch(
    `${BASE_URL}/api/v1/agents/${agentCode}/status`,
    JSON.stringify({
      targetStatus: 'ACTIVE',
      expectedVersion: 0,
    }),
    { headers }
  );

  if (activated.status !== 200) {
    throw new Error(
      `agent activate failed: ${activated.status} ${activated.body}`
    );
  }
}

export function createConversation(token, agentCode, title) {
  const res = http.post(
    `${BASE_URL}/api/v1/conversations`,
    JSON.stringify({
      agentCode,
      title: title || 'Load test conversation',
      initialMessage: 'Initial load-test message.',
    }),
    { headers: authHeaders(token) }
  );

  if (res.status !== 201 && res.status !== 200) {
    throw new Error(`conversation create failed: ${res.status} ${res.body}`);
  }

  return res.json('conversationId');
}

export function authHeaders(token) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };
}

/**
 * Open one SSE turn. Returns { status, events, body } where events
 * are { name, data } pairs parsed from the named SSE frames
 * (started/delta/completed/error).
 */
export function streamTurn(token, conversationId, content, extraHeaders) {
  const params = {
    headers: {
      ...authHeaders(token),
      Accept: 'text/event-stream',
      ...(extraHeaders || {}),
    },
    timeout: '120s',
  };

  const res = http.post(
    `${BASE_URL}/api/v1/conversations/${conversationId}/turns:stream`,
    JSON.stringify({ content }),
    params
  );

  const events = [];
  if (typeof res.body === 'string' && res.body.length > 0) {
    for (const frame of res.body.split('\n\n')) {
      const nameLine = frame
        .split('\n')
        .find((line) => line.startsWith('event:'));
      const dataLine = frame
        .split('\n')
        .find((line) => line.startsWith('data:'));

      if (!dataLine) continue;

      let data = null;
      try {
        data = JSON.parse(dataLine.slice(5).trim());
      } catch {
        data = { raw: dataLine.slice(5).trim() };
      }

      events.push({
        name: nameLine ? nameLine.slice(6).trim() : 'unknown',
        data,
      });
    }
  }

  return { status: res.status, events, body: res.body };
}

export function eventsNamed(events, name) {
  return events.filter((event) => event.name === name);
}

export function errorEvent(events) {
  return events.find((event) => event.name === 'error');
}
