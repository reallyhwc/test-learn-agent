const NUMERIC_NOISE = /[¥$%,\s]/g

function isNumeric(val) {
  if (val == null || val === '') return false
  return !isNaN(parseFloat(String(val).replace(NUMERIC_NOISE, '')))
}

function cleanNumeric(val) {
  const n = parseFloat(String(val).replace(NUMERIC_NOISE, ''))
  return isNaN(n) ? '0' : String(n)
}

/**
 * 从一个 <table> DOM 节点提取可绘图的数据。
 * 返回 { headers, rows, chartType: 'pie' | 'bar' } 或 null（不可绘）。
 */
export function extractTableData(table) {
  if (!table) return null

  const headers = []
  const thead = table.querySelector('thead')
  if (thead) {
    thead.querySelectorAll('th').forEach((th) =>
      headers.push(th.textContent.trim()),
    )
  } else {
    const firstRow = table.querySelector('tr')
    if (firstRow) {
      firstRow
        .querySelectorAll('td,th')
        .forEach((c) => headers.push(c.textContent.trim()))
    }
  }
  if (headers.length < 2) return null

  const rows = []
  const tbody = table.querySelector('tbody') || table
  tbody.querySelectorAll('tr').forEach((tr) => {
    const cells = [...tr.querySelectorAll('td,th')].map((c) =>
      c.textContent.trim(),
    )
    if (cells.length >= 2) rows.push(cells)
  })
  if (rows.length === 0) return null

  const numCols = []
  for (let i = 1; i < headers.length; i++) {
    const sample = rows.slice(0, 3).filter((r) => r[i]).map((r) => r[i])
    if (sample.length > 0 && sample.every(isNumeric)) {
      numCols.push(i)
    }
  }
  if (numCols.length === 0) return null

  const cleanRows = rows
    .map((r) =>
      r.map((c, i) => {
        if (i === 0) return c
        if (numCols.includes(i)) return cleanNumeric(c)
        return c
      }),
    )
    .filter((r) => r.length >= 2)

  if (cleanRows.length === 0) return null

  const hasMultipleNumericCols = numCols.length > 1
  const chartType = hasMultipleNumericCols
    ? 'bar'
    : cleanRows.length <= 8
      ? 'pie'
      : 'bar'

  return {
    headers: headers.filter((_, i) => i === 0 || numCols.includes(i)),
    rows: cleanRows.map((r) => r.filter((_, i) => i === 0 || numCols.includes(i))),
    chartType,
  }
}
