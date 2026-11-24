// 答案展示：submittedAnswer / correctAnswer 是后端下发的 JsonNode，按题型翻译成人可读文本
export function formatAnswer(value, type, kind = 'mine') {
  const mine = kind === 'mine'
  if (value == null) return mine ? '未作答' : '—'

  if (type === 'CHOICE') {
    const key = mine ? value.selectedOption : value.correctOption
    return key ? String(key) : mine ? '未作答' : '—'
  }

  if (type === 'FILL') {
    const blanks = Array.isArray(value.blanks) ? value.blanks : []
    if (!blanks.length) return mine ? '未作答' : '—'
    return blanks
      .map((blank, index) => {
        const text = mine
          ? (typeof blank === 'string' && blank.trim() ? blank : '（空）')
          : ((blank && blank.accepted ? blank.accepted : []).join(' / ') || '—')
        return `第 ${index + 1} 空：${text}`
      })
      .join('；')
  }

  if (!mine && value.referenceAnswer) return String(value.referenceAnswer)

  const parts = []
  if (value.text) parts.push(String(value.text))
  if (value.code) parts.push('代码：\n' + value.code)
  if (parts.length) return parts.join('\n\n')
  return mine ? '未作答' : '—'
}
