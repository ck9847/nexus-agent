// OpenAI-compatible SSE mock for deterministic load testing.
//
// Env knobs:
//   PORT               listen port (default 18080)
//   CHUNK_DELAY_MS     delay between streamed chunks (default 5)
//   FAIL_FIRST_N       first N requests get HTTP 500 (default 0) —
//                      exercises safe retry / circuit breaker paths
//   TEXT_CHUNKS        number of text deltas per text round (default 8)
//   SEED               seed for per-request IDs (default fixed)
//
// Behavior: requests carrying `tools` get a create_ticket tool call
// (first model round); requests without tools get plain text
// (tool-free continuation round).

import http from 'node:http';

const PORT = Number(process.env.PORT || 18080);
const CHUNK_DELAY_MS = Number(process.env.CHUNK_DELAY_MS || 5);
const FAIL_FIRST_N = Number(process.env.FAIL_FIRST_N || 0);
const TEXT_CHUNKS = Number(process.env.TEXT_CHUNKS || 8);

let requestCounter = 0;
let toolCallCounter = 0;

function sse(res, obj) {
  res.write(`data: ${JSON.stringify(obj)}\n\n`);
}

function chunk(id, model, delta, finishReason, usage) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason ?? null }],
    ...(usage ? { usage } : {}),
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function handle(req, res) {
  requestCounter += 1;

  if (FAIL_FIRST_N > 0 && requestCounter <= FAIL_FIRST_N) {
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'injected failure' } }));
    return;
  }

  const body = await new Promise((resolve, reject) => {
    const parts = [];
    req.on('data', (p) => parts.push(p));
    req.on('end', () => resolve(Buffer.concat(parts).toString('utf8')));
    req.on('error', reject);
  });

  let parsed = {};
  try {
    parsed = JSON.parse(body);
  } catch {
    res.writeHead(400, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: { message: 'bad json' } }));
    return;
  }

  const id = `chatcmpl-mock-${requestCounter}`;
  const model = parsed.model || 'mock-model';

  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  });

  const hasTools = Array.isArray(parsed.tools) && parsed.tools.length > 0;

  if (hasTools) {
    toolCallCounter += 1;
    const callId = `call-mock-${toolCallCounter}`;
    const args = JSON.stringify({
      title: 'Load test incident',
      description:
        'Deterministic ticket created by the OpenAI mock during the load test run.',
      priority: 'HIGH',
    });

    sse(res, chunk(id, model, {
      role: 'assistant',
      tool_calls: [{
        index: 0,
        id: callId,
        type: 'function',
        function: { name: 'create_ticket', arguments: '' },
      }],
    }));
    await sleep(CHUNK_DELAY_MS);

    sse(res, chunk(id, model, {
      tool_calls: [{
        index: 0,
        function: { arguments: args },
      }],
    }));
    await sleep(CHUNK_DELAY_MS);

    sse(res, chunk(id, model, {}, 'tool_calls', {
      prompt_tokens: 42,
      completion_tokens: 7,
    }));
  } else {
    sse(res, chunk(id, model, { role: 'assistant', content: '' }));
    await sleep(CHUNK_DELAY_MS);

    for (let i = 0; i < TEXT_CHUNKS; i++) {
      sse(res, chunk(id, model, { content: `chunk-${i} ` }));
      await sleep(CHUNK_DELAY_MS);
    }

    sse(res, chunk(id, model, {}, 'stop', {
      prompt_tokens: 42,
      completion_tokens: TEXT_CHUNKS,
    }));
  }

  res.write('data: [DONE]\n\n');
  res.end();
}

http.createServer(handle).listen(PORT, () => {
  console.log(
    `openai-mock listening on :${PORT} ` +
    `(chunk_delay=${CHUNK_DELAY_MS}ms, fail_first=${FAIL_FIRST_N})`
  );
});
