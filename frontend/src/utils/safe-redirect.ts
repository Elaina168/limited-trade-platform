export function safeRedirect(value: unknown): string | undefined {
  if (typeof value !== 'string' || !value.startsWith('/')) return
  let path = value.split(/[?#]/, 1)[0]
  // 检查解码和路径规范化后的目标，返回原站内地址以保留参数。
  while (true) {
    if (path.startsWith('//') || /[\\\u0000-\u0020\u007f?#]/.test(path)) return
    const normalized = new URL(path, 'https://storefront.invalid').pathname
    if (normalized.startsWith('//') || /^\/(?:login|register)\/*$/i.test(normalized)) return
    let decoded: string
    try { decoded = decodeURIComponent(path) }
    catch (cause) { if (cause instanceof URIError) return; throw cause }
    if (decoded === path) return /[\u0000-\u001f\u007f]/.test(value) ? undefined : value
    path = decoded
  }
}
