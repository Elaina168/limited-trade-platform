import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseHighlightSegments } from '../src/utils/search-highlight.ts'

test('安全解析高亮片段并转义HTML实体，防止XSS注入', () => {
  assert.deepEqual(
    parseHighlightSegments('机械键盘 Pro', '机械<mark>键盘</mark> Pro'),
    [
      { text: '机械', hit: false },
      { text: '键盘', hit: true },
      { text: ' Pro', hit: false },
    ],
  )
  assert.deepEqual(
    parseHighlightSegments('A&B <限量> 键盘', 'A&amp;B &lt;<mark>限量</mark>&gt; 键盘'),
    [
      { text: 'A&B <', hit: false },
      { text: '限量', hit: true },
      { text: '> 键盘', hit: false },
    ],
  )
  // 若高亮文本被篡改与原商品名不一致，强制回退纯文本原名。
  assert.deepEqual(
    parseHighlightSegments('机械键盘', '<img src=x onerror=alert(1)><mark>键盘</mark>'),
    [{ text: '机械键盘', hit: false }],
  )
})
