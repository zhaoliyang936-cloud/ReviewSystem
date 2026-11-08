import test from 'node:test'
import assert from 'node:assert/strict'
import { getHealth } from '../src/health.js'
test('accepts UP', async () => { assert.equal(await getHealth(async url => { assert.equal(url, '/api/health'); return { ok: true, json: async () => ({ status: 'UP' }) } }), 'UP') })
test('rejects errors', async () => { await assert.rejects(getHealth(async () => ({ ok: false }))); await assert.rejects(getHealth(async () => ({ ok: true, json: async () => ({ status: 'DOWN' }) }))) })
