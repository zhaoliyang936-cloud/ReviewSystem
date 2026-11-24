import test from 'node:test'
import assert from 'node:assert/strict'
import { formatAnswer } from '../src/format.js'

test('choice shows option key, not raw json', () => {
  assert.equal(formatAnswer({ selectedOption: 'A' }, 'CHOICE'), 'A')
  assert.equal(formatAnswer({ correctOption: 'D' }, 'CHOICE', 'reference'), 'D')
  assert.equal(formatAnswer({ selectedOption: '' }, 'CHOICE'), '未作答')
})

test('fill joins blanks with index and handles accepted set', () => {
  assert.equal(
    formatAnswer({ blanks: ['栈顶', ''] }, 'FILL'),
    '第 1 空：栈顶；第 2 空：（空）'
  )
  assert.equal(
    formatAnswer({ blanks: [{ accepted: ['A', 'a'] }, { accepted: ['O(1)'] }] }, 'FILL', 'reference'),
    '第 1 空：A / a；第 2 空：O(1)'
  )
})

test('subjective keeps text and code on separate lines', () => {
  assert.equal(formatAnswer({ text: '先序遍历' }, 'SHORT_ANSWER'), '先序遍历')
  assert.equal(
    formatAnswer({ text: '', code: 'void f(){\n  return;\n}' }, 'ALGORITHM'),
    '代码：\nvoid f(){\n  return;\n}'
  )
  assert.equal(
    formatAnswer({ referenceAnswer: '分治后合并' }, 'ALGORITHM', 'reference'),
    '分治后合并'
  )
})

test('missing answer falls back by kind', () => {
  assert.equal(formatAnswer(null, 'CHOICE'), '未作答')
  assert.equal(formatAnswer(null, 'CHOICE', 'reference'), '—')
  assert.equal(formatAnswer({}, 'SHORT_ANSWER'), '未作答')
})
