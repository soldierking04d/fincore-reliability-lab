import test from 'node:test';
import assert from 'node:assert/strict';
import { createReadOnlyRpc, parseStrictJson } from '../src/rpc.mjs';

const GENESIS = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d';
const ENDPOINT = 'https://api.mainnet.solana.com';
const ok = (request, result) => ({ statusCode: 200,
  body: JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) });
function scripted(callback = () => 7) {
  const calls = [];
  const transport = async options => {
    const request = JSON.parse(options.body.toString());
    calls.push({ ...request, endpoint: options.endpoint, signal: options.signal });
    return request.method === 'getGenesisHash' ? ok(request, GENESIS) : callback(request, options);
  };
  return { calls, transport };
}
function wire() {
  return Buffer.concat([Buffer.from([1]), Buffer.alloc(64), Buffer.from([1, 0, 1, 2]),
    Buffer.alloc(32, 3), Buffer.alloc(32, 4), Buffer.alloc(32, 5), Buffer.from([1, 1, 0, 1, 7])]);
}
function simulation(bytes = wire()) {
  return [bytes.toString('base64'), { encoding: 'base64', commitment: 'confirmed',
    sigVerify: false, replaceRecentBlockhash: false }];
}

test('strict JSON preserves decimal display values but rejects unsafe integers and ambiguity', () => {
  assert.deepEqual(parseStrictJson('{"supply":"18446744073709551615","uiAmount":1.25}'),
    { supply: '18446744073709551615', uiAmount: 1.25 });
  for (const input of ['{"x":1,"x":2}', '{"x":1,"\\u0078":2}', '{"x":9007199254740992}',
    '{"x":9.007199254740992e15}', '{"x":1e400}', '{"x":01}', '{"x":true} {}',
    '{"x":NaN}', '{"x":undefined}', '[1,]', '{"x":1,}', '"unterminated',
    '['.repeat(34) + '0' + ']'.repeat(34), '{"x":"raw\nnewline"}']) {
    assert.throws(() => parseStrictJson(input), error => error.code === 'RPC_JSON_INVALID'
      && !error.message.includes(input));
  }
  const parsed = parseStrictJson('{"__proto__":{"polluted":true}}');
  assert.equal(Object.hasOwn(parsed, '__proto__'), true);
  assert.equal({}.polluted, undefined);
});

test('strict JSON rejects oversized documents and invalid input types', () => {
  for (const input of [' '.repeat(1_048_577), null, {}, undefined]) {
    assert.throws(() => parseStrictJson(input), error => typeof error.code === 'string');
  }
});

test('RPC-only lossless integer mode preserves u64 rentEpoch while HTTP stays strict', async () => {
  const raw = '{"rentEpoch":18446744073709551615,"uiAmount":1.25}';
  assert.throws(() => parseStrictJson(raw), { code: 'RPC_JSON_INVALID' });
  assert.deepEqual(parseStrictJson(raw, { unsafeIntegers: 'string' }),
    { rentEpoch: '18446744073709551615', uiAmount: 1.25 });
  for (const value of ['1.0000000000000001', '9007199254740990.4', '1e-999',
    '18446744073709551615.0', '1.8446744073709551615e19']) {
    assert.throws(() => parseStrictJson('{"x":' + value + '}', { unsafeIntegers: 'string' }), { code: 'RPC_JSON_INVALID' });
  }
  const fixture = scripted(request => ({ statusCode: 200,
    body: '{"jsonrpc":"2.0","id":' + request.id + ',"result":' + raw + '}' }));
  const result = await createReadOnlyRpc({ transport: fixture.transport })('getAccountInfo', []);
  assert.equal(result.rentEpoch, '18446744073709551615');
});

test('every logical read uses fixed endpoint and fresh genesis before target', async () => {
  const fixture = scripted(request => ok(request, { value: 7 }));
  const rpc = createReadOnlyRpc({ transport: fixture.transport });
  assert.deepEqual(await rpc('getAccountInfo', ['public-account']), { value: 7 });
  assert.deepEqual(await rpc('getBalance', ['public-account']), { value: 7 });
  assert.deepEqual(fixture.calls.map(call => call.method),
    ['getGenesisHash', 'getAccountInfo', 'getGenesisHash', 'getBalance']);
  assert.ok(fixture.calls.every(call => call.endpoint === ENDPOINT));
  assert.equal(new Set(fixture.calls.map(call => call.id)).size, 4);
});

test('genesis-only call makes one request and a wrong cluster stops the target', async () => {
  let calls = 0;
  const rpc = createReadOnlyRpc({ transport: async ({ body }) => {
    calls++;
    return ok(JSON.parse(body), GENESIS);
  } });
  assert.equal(await rpc('getGenesisHash', []), GENESIS);
  assert.equal(calls, 1);
  const wrong = createReadOnlyRpc({ transport: async ({ body }) => {
    calls++;
    return ok(JSON.parse(body), '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp');
  } });
  await assert.rejects(wrong('getBalance', []), { code: 'RPC_CLUSTER_MISMATCH' });
  assert.equal(calls, 2);
});

test('write methods and malformed params fail before any transport access', async () => {
  let calls = 0;
  const rpc = createReadOnlyRpc({ transport: async () => { calls++; throw new Error('unreachable'); } });
  for (const method of ['sendTransaction', 'sendRawTransaction', 'requestAirdrop', 'signTransaction',
    'signAllTransactions', 'getgenesisHash', '__proto__', null]) {
    await assert.rejects(rpc(method, []), { code: 'RPC_METHOD_FORBIDDEN' });
  }
  for (const params of [null, {}, [undefined], [1n], [Infinity], [Number.MAX_SAFE_INTEGER + 1], ['x'.repeat(65_536)]]) {
    await assert.rejects(rpc('getBalance', params));
  }
  const circular = []; circular.push(circular);
  await assert.rejects(rpc('getBalance', circular));
  await assert.rejects(rpc('getGenesisHash', [1]));
  assert.equal(calls, 0);
});

test('request params are snapshotted before asynchronous genesis lookup', async () => {
  let finishGenesis;
  const target = [];
  const rpc = createReadOnlyRpc({ transport: ({ body }) => {
    const request = JSON.parse(body);
    if (request.method === 'getGenesisHash') return new Promise(resolve => { finishGenesis = () => resolve(ok(request, GENESIS)); });
    target.push(request.params); return Promise.resolve(ok(request, 1));
  } });
  const params = ['original', { commitment: 'finalized' }];
  const result = rpc('getAccountInfo', params);
  params[0] = 'changed'; params[1].commitment = 'processed';
  finishGenesis();
  await result;
  assert.deepEqual(target, [['original', { commitment: 'finalized' }]]);
});

test('HTTP errors, malformed envelopes and unavailable results stay explicit safe failures', async () => {
  const variations = [
    request => ({ statusCode: 302, body: 'remote-secret' }),
    request => ({ statusCode: 429, body: 'remote-secret' }),
    request => ({ statusCode: 200, body: '{"jsonrpc":"1.0","id":1,"result":1}' }),
    request => ({ statusCode: 200, body: JSON.stringify({ jsonrpc: '2.0', id: String(request.id), result: 1 }) }),
    request => ({ statusCode: 200, body: JSON.stringify({ jsonrpc: '2.0', id: request.id + 1, result: 1 }) }),
    request => ({ statusCode: 200, body: JSON.stringify({ jsonrpc: '2.0', id: request.id }) }),
    request => ok(request, null),
    request => ({ statusCode: 200, body: JSON.stringify({ jsonrpc: '2.0', id: request.id,
      error: { code: -1, message: 'remote-secret' } }) }),
    request => ({ statusCode: 200, body: JSON.stringify({ jsonrpc: '2.0', id: request.id, result: 1, error: null }) }),
    request => ({ statusCode: 200, body: Buffer.alloc(1_048_577) }),
    request => ({ statusCode: 200, body: Buffer.from([0xc0, 0x80]) }),
  ];
  for (const respond of variations) {
    let calls = 0;
    const rpc = createReadOnlyRpc({ transport: async ({ body }) => { calls++; return respond(JSON.parse(body)); } });
    await assert.rejects(rpc('getBalance', []), error => typeof error.code === 'string'
      && !error.message.includes('remote-secret') && error.cause === undefined);
    assert.equal(calls, 1, 'Bad genesis envelope must never trigger target or retry');
  }
});

test('transport failures expose no request, upstream body or cause', async () => {
  const rpc = createReadOnlyRpc({ transport: async () => { throw new Error('remote-secret'); } });
  await assert.rejects(rpc('getBalance', []), error => error.code === 'RPC_TRANSPORT_FAILED'
    && !error.message.includes('remote-secret') && error.cause === undefined);
});

test('target responses independently validate envelope types after successful genesis', async () => {
  const variants = [
    [request => JSON.stringify({ jsonrpc: '1.0', id: request.id, result: 5 }), 'RPC_ENVELOPE_INVALID'],
    [request => JSON.stringify({ jsonrpc: '2.0', id: String(request.id), result: 5 }), 'RPC_ENVELOPE_INVALID'],
    [request => JSON.stringify({ jsonrpc: '2.0', id: request.id + 1, result: 5 }), 'RPC_ENVELOPE_INVALID'],
    [request => JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: 'bad', message: 'raw detail' } }), 'RPC_ENVELOPE_INVALID'],
    [request => JSON.stringify({ jsonrpc: '2.0', id: request.id, error: { code: -1, message: 'raw detail' } }), 'RPC_REMOTE_ERROR'],
    [request => JSON.stringify({ jsonrpc: '2.0', id: request.id, result: null }), 'RPC_RESULT_UNAVAILABLE'],
    [request => '{"jsonrpc":"2.0","id":' + request.id + ',"result":1,"result":2}', 'RPC_JSON_INVALID'],
  ];
  for (const [body, code] of variants) {
    const fixture = scripted(request => ({ statusCode: 200, body: body(request) }));
    await assert.rejects(createReadOnlyRpc({ transport: fixture.transport })('getBlockHeight', []), { code });
    assert.deepEqual(fixture.calls.map(call => call.method), ['getGenesisHash', 'getBlockHeight']);
  }
});

test('nested null remains available for core interpretation', async () => {
  const fixture = scripted(request => ok(request, { context: { slot: 1 }, value: null }));
  const rpc = createReadOnlyRpc({ transport: fixture.transport });
  assert.equal((await rpc('getAccountInfo', [])).value, null);
});

test('global inflight admission rejects fifth call across distinct clients and recovers', async () => {
  const releases = [];
  const transport = ({ body }) => new Promise(resolve => {
    const request = JSON.parse(body); releases.push(() => resolve(ok(request, GENESIS)));
  });
  const active = Array.from({ length: 4 }, () => createReadOnlyRpc({ transport })('getGenesisHash', []));
  let extraCalls = 0;
  const extra = createReadOnlyRpc({ transport: async ({ body }) => { extraCalls++; return ok(JSON.parse(body), GENESIS); } });
  await assert.rejects(extra('getGenesisHash', []), { code: 'RPC_OVERLOADED' });
  assert.equal(extraCalls, 0);
  releases.forEach(release => release()); await Promise.all(active);
  assert.equal(await extra('getGenesisHash', []), GENESIS);
});

test('one ten-second deadline includes incomplete body and releases capacity on abort', async context => {
  context.mock.timers.enable({ apis: ['setTimeout'] });
  let aborted = false;
  const rpc = createReadOnlyRpc({ transport: ({ signal }) => new Promise(() => {
    signal.addEventListener('abort', () => { aborted = true; }, { once: true });
  }) });
  const pending = assert.rejects(rpc('getGenesisHash', []), { code: 'RPC_DEADLINE_EXCEEDED' });
  context.mock.timers.tick(10_001);
  await pending;
  assert.equal(aborted, true);
});

test('valid unsigned simulation preserves pinned configuration', async () => {
  const fixture = scripted(request => ok(request, { value: { err: null } }));
  const rpc = createReadOnlyRpc({ transport: fixture.transport });
  const params = simulation();
  assert.equal((await rpc('simulateTransaction', params)).value.err, null);
  assert.deepEqual(fixture.calls[1].params, params);
});

test('public simulation boundary rejects signed, versioned, malformed and replaced transactions', async () => {
  const malformed = [];
  let signed = wire(); signed[1] = 1; malformed.push(simulation(signed));
  let versioned = wire(); versioned[65] = 0x80; malformed.push(simulation(versioned));
  malformed.push(simulation(Buffer.concat([wire(), Buffer.from([0])])));
  malformed.push(simulation(wire().subarray(0, 90)));
  let duplicate = wire(); duplicate.copy(duplicate, 101, 69, 101); malformed.push(simulation(duplicate));
  let payerProgram = wire(); payerProgram[payerProgram.length - 4] = 0; malformed.push(simulation(payerProgram));
  let alias = Buffer.concat([Buffer.from([0x81, 0]), wire().subarray(1)]); malformed.push(simulation(alias));
  let multi = wire(); multi[0] = 2; malformed.push(simulation(multi));
  let nonce = Buffer.concat([wire().subarray(0, -5), Buffer.from([1, 1, 0, 4, 4, 0, 0, 0])]);
  nonce.fill(0, 101, 133); malformed.push(simulation(nonce));
  for (const [key, value] of [['sigVerify', true], ['replaceRecentBlockhash', true],
    ['commitment', 'processed'], ['encoding', 'base58']]) {
    const params = simulation(); params[1][key] = value; malformed.push(params);
  }
  let noncanonical = simulation(); noncanonical[0] += '\n'; malformed.push(noncanonical);
  let calls = 0;
  const rpc = createReadOnlyRpc({ transport: async () => { calls++; throw new Error('unreachable'); } });
  for (const params of malformed) await assert.rejects(rpc('simulateTransaction', params));
  assert.equal(calls, 0);
});
