export interface HighlightSegment {
  text: string
  hit: boolean
}

function decodeHtmlEntities(input: string): string {
  return input
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&#39;', "'")
    .replaceAll('&#x27;', "'")
    .replaceAll('&amp;', '&')
}

export function parseHighlightSegments(rawName: string, highlightedName?: string | null): HighlightSegment[] {
  if (!highlightedName || !highlightedName.includes('<mark>')) {
    return [{ text: rawName, hit: false }]
  }
  const segments: HighlightSegment[] = []
  const pattern = /<mark>([\s\S]*?)<\/mark>/g
  let cursor = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(highlightedName)) !== null) {
    if (match.index > cursor) {
      segments.push({ text: decodeHtmlEntities(highlightedName.slice(cursor, match.index)), hit: false })
    }
    if (match[1]) {
      segments.push({ text: decodeHtmlEntities(match[1]), hit: true })
    }
    cursor = match.index + match[0].length
  }
  if (cursor < highlightedName.length) {
    segments.push({ text: decodeHtmlEntities(highlightedName.slice(cursor)), hit: false })
  }
  const reconstructed = segments.map(s => s.text).join('')
  if (reconstructed !== rawName) {
    return [{ text: rawName, hit: false }]
  }
  return segments
}
