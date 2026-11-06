export class StreamProtocolError extends Error {}
export class StreamEventError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export async function readAgentStream(body, onEvent, signal) {
  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let buffer = '', name = '', data = [], completed = false, result;
  const check = () => { if (signal?.aborted) throw new DOMException('请求已停止', 'AbortError'); };
  const abort = () => { reader.cancel().catch(error => console.debug('停止流读取失败', error.name)); };
  signal?.addEventListener('abort', abort, { once: true });

  function line(value) {
    if (value === '') {
      if (!data.length) { name = ''; return; }
      let payload;
      try { payload = JSON.parse(data.join('\n')); }
      catch { throw new StreamProtocolError('助手返回的数据无法读取。'); }
      if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new StreamProtocolError('助手事件格式不正确。');
      if (name === 'delta' || name === 'reset') {
        if (!Number.isInteger(payload.round) || payload.round < 1 || typeof payload.text !== 'string') throw new StreamProtocolError('助手增量格式不正确。');
      } else if (name === 'done') {
        if (typeof payload.answer !== 'string' || !Array.isArray(payload.sources) || !Array.isArray(payload.knowledgeSources)) throw new StreamProtocolError('助手回答格式不正确。');
        result = payload; completed = true;
      } else if (name === 'error') {
        if (!Number.isInteger(payload.code) || typeof payload.message !== 'string') throw new StreamProtocolError('助手错误事件格式不正确。');
        throw new StreamEventError(payload.code, payload.message);
      } else if (name !== 'start') throw new StreamProtocolError('收到无法识别的助手事件。');
      onEvent(name, payload);
      name = ''; data = [];
      return;
    }
    if (value.startsWith(':')) return;
    const colon = value.indexOf(':');
    const key = colon < 0 ? value : value.slice(0, colon);
    let content = colon < 0 ? '' : value.slice(colon + 1);
    if (content.startsWith(' ')) content = content.slice(1);
    if (key === 'event') name = content;
    if (key === 'data') data.push(content);
  }

  try {
    check();
    while (!completed) {
      const chunk = await reader.read();
      check();
      try { buffer += chunk.done ? decoder.decode() : decoder.decode(chunk.value, { stream: true }); }
      catch (error) { if (error instanceof TypeError) throw new StreamProtocolError('助手返回的文字编码无法读取。'); throw error; }
      let start = 0;
      for (let i = 0; i < buffer.length; i++) {
        if (buffer[i] !== '\n' && buffer[i] !== '\r') continue;
        if (buffer[i] === '\r' && i === buffer.length - 1 && !chunk.done) break;
        line(buffer.slice(start, i));
        if (buffer[i] === '\r' && buffer[i + 1] === '\n') i++;
        start = i + 1;
        if (completed) return result;
      }
      buffer = buffer.slice(start);
      if (chunk.done) throw new StreamProtocolError('连接已结束，但尚未确认回答完成；结果可能已保存，请勿自动重复发送。');
    }
    return result;
  } finally {
    signal?.removeEventListener('abort', abort);
    try { await reader.cancel(); }
    catch (error) { console.debug('流读取清理失败', error.name); }
    reader.releaseLock();
  }
}
