import test from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import { EventEmitter } from 'node:events';
import { createReadOnlyRpc } from '../src/rpc.mjs';

const GENESIS = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d';
function mockHttps(context, respond) {
  const requests = [];
  context.mock.method(https, 'request', (options, callback) => {
    const request = new EventEmitter();
    request.destroyed = false;
    request.destroy = () => { request.destroyed = true; };
    request.end = body => queueMicrotask(() => respond({ options, request,
      json: JSON.parse(body.toString()), incoming(statusCode = 200, headers = {}) {
        const response = new EventEmitter();
        response.statusCode = statusCode; response.headers = headers; response.complete = false;
        response.destroyed = false;
        response.destroy = () => { response.destroyed = true; response.emit('close'); };
        callback(response); return response;
      } }));
    options.signal.addEventListener('abort', () => request.emit('error', new Error('cancelled upstream detail')), { once: true });
    requests.push({ options, request });
    return request;
  });
  return requests;
}
function complete(response, json, result) {
  response.emit('data', Buffer.from(JSON.stringify({ jsonrpc: '2.0', id: json.id, result })));
  response.complete = true; response.emit('end'); response.emit('close');
}

test('native HTTPS pins host/path, bypasses shared proxy agent and sends once per exchange', async context => {
  const requests = mockHttps(context, ({ incoming, json }) => {
    complete(incoming(), json, json.method === 'getGenesisHash' ? GENESIS : { value: 10 });
  });
  assert.deepEqual(await createReadOnlyRpc()('getBalance', ['public-key']), { value: 10 });
  assert.equal(requests.length, 2);
  for (const { options } of requests) {
    assert.equal(options.hostname, 'api.mainnet.solana.com');
    assert.equal(options.port, 443); assert.equal(options.path, '/');
    assert.equal(options.method, 'POST'); assert.equal(options.agent, false);
    assert.equal(options.headers['Accept-Encoding'], 'identity');
    assert.equal(options.headers.Authorization, undefined);
    assert.equal(options.headers['Content-Length'] > 0, true);
  }
});

test('native redirect response is closed without following Location or retrying', async context => {
  let response;
  const requests = mockHttps(context, ({ incoming }) => {
    response = incoming(302, { location: 'https://untrusted.invalid/private' });
  });
  await assert.rejects(createReadOnlyRpc()('getBalance', []), { code: 'RPC_HTTP_REJECTED' });
  assert.equal(requests.length, 1);
  assert.equal(requests[0].request.destroyed, true);
  assert.equal(response.destroyed, true);
});

test('native chunked body stops immediately at the byte cap', async context => {
  let response;
  const requests = mockHttps(context, ({ incoming }) => {
    response = incoming();
    response.emit('data', Buffer.alloc(1_048_576));
    response.emit('data', Buffer.from([1]));
  });
  await assert.rejects(createReadOnlyRpc()('getGenesisHash', []), { code: 'RPC_RESPONSE_TOO_LARGE' });
  assert.equal(response.destroyed, true);
  assert.equal(requests[0].request.destroyed, true);
});

test('native incomplete body and request errors cannot resolve partial JSON', async context => {
  const requests = mockHttps(context, ({ incoming }) => {
    const response = incoming();
    response.emit('data', Buffer.from('{"jsonrpc":"2.0"'));
    response.emit('aborted');
  });
  await assert.rejects(createReadOnlyRpc()('getGenesisHash', []), error => error.code === 'RPC_TRANSPORT_FAILED'
    && error.cause === undefined && !error.message.includes('jsonrpc'));
  assert.equal(requests.length, 1);
});

test('native entire-body deadline aborts a connection that keeps the body open', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  let response;
  const requests = mockHttps(context, ({ incoming }) => {
    response = incoming(); response.emit('data', Buffer.from('{'));
  });
  const pending = assert.rejects(createReadOnlyRpc()('getGenesisHash', []), { code: 'RPC_DEADLINE_EXCEEDED' });
  await new Promise(setImmediate);
  context.mock.timers.tick(10_001);
  await pending;
  assert.equal(requests[0].options.signal.aborted, true);
  assert.equal(requests[0].request.destroyed, true);
  assert.equal(response.destroyed, true);
});

test('genesis time consumes the same deadline as the target request', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const methods = [];
  const requests = mockHttps(context, ({ incoming, json }) => {
    methods.push(json.method);
    const response = incoming();
    if (json.method === 'getGenesisHash') setTimeout(() => complete(response, json, GENESIS), 6_000);
  });
  const pending = assert.rejects(createReadOnlyRpc()('getBalance', []), { code: 'RPC_DEADLINE_EXCEEDED' });
  await new Promise(setImmediate);
  context.mock.timers.tick(6_000);
  await new Promise(setImmediate);
  assert.deepEqual(methods, ['getGenesisHash', 'getBalance']);
  context.mock.timers.tick(4_001);
  await pending;
  assert.equal(requests.length, 2);
  assert.ok(requests.every(({ options }) => options.signal.aborted));
});
