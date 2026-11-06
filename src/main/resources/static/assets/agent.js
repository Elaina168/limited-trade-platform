import { write, streamWrite, ApiError, UserError, errorMessage } from './api.js';
import { StreamEventError } from './sse.js';
import { el, heading } from './ui.js';

let session = null;
let dispose = null;
const storageKey = userId => `limited-market:agent:${userId}`;

export function disposeAgentPage() { if (dispose) dispose(); dispose = null; }
export function resetAgentIdentity(userId) {
  disposeAgentPage();
  if (userId != null) sessionStorage.removeItem(storageKey(userId));
  session = null;
}

function failureMessage(error, creating) {
  if (error instanceof ApiError || error instanceof StreamEventError) {
    if (error.status === 404) return creating ? '交易助手当前不可用，请稍后重试。' : '会话已过期或不可访问，请新建会话后继续。';
    if (error.status === 409) return creating ? '会话数量已达上限，可清空当前会话继续使用，或稍后再试。' : '当前会话正在处理上一请求或已发生变化，请稍后手动重试。';
    if (error.status === 502 || error.status === 504) return '助手暂时未能完成回答，请稍后重试。';
    if (error instanceof StreamEventError) return error.message;
  }
  return errorMessage(error);
}

export function agentPage(user, current) {
  if (!session || session.userId !== user.id) session = { userId: user.id, id: sessionStorage.getItem(storageKey(user.id)), messages: [] };
  const state = session;
  let alive = true, busy = false, controller = null, activeAssistant = null;
  const valid = () => alive && current();
  const refs = new Map();
  const status = el('p', { class: 'chat-notice', role: 'status', 'aria-live': 'polite' });
  const messages = el('div', { class: 'chat-messages', role: 'log', 'aria-label': '聊天记录', 'aria-live': 'off', tabIndex: 0 });
  const input = el('textarea', { id: 'agent-question', name: 'message', maxLength: 2000, rows: 3,
    placeholder: '例如：查一下我的待支付订单，并解释支付期限。', required: true });
  const counter = el('span', { class: 'muted' }, '0 / 2000');
  const send = el('button', { type: 'submit', class: 'button' }, '发送');
  const stop = el('button', { type: 'button', class: 'button secondary', disabled: true, onclick: () => {
    controller?.abort(); status.textContent = '正在停止，已收到的内容尚未确认完成。';
  } }, '停止生成');
  const create = el('button', { type: 'button', class: 'button secondary', onclick: () => changeConversation(false) }, '新会话');
  const confirmClear = el('button', { type: 'button', class: 'button danger', onclick: () => changeConversation(true) }, '确认清空');
  const cancelClear = el('button', { type: 'button', class: 'button secondary', onclick: () => { confirmation.hidden = true; clear.focus(); } }, '保留历史');
  const confirmation = el('div', { class: 'chat-confirm', role: 'group', 'aria-label': '确认清空历史', hidden: true },
    el('span', {}, '清空后，助手将不再使用此会话的历史记录。'), confirmClear, cancelClear);
  const clear = el('button', { type: 'button', class: 'button secondary', onclick: () => { confirmation.hidden = false; confirmClear.focus(); } }, '清空历史');

  function controls() {
    send.disabled = busy; stop.disabled = !busy || !activeAssistant;
    create.disabled = busy; clear.disabled = busy || !state.id; input.disabled = busy;
    confirmClear.disabled = busy; cancelClear.disabled = busy;
    counter.textContent = `${input.value.length} / 2000`;
  }
  function remember(id) { state.id = id; sessionStorage.setItem(storageKey(user.id), id); }
  function forget() { state.id = null; sessionStorage.removeItem(storageKey(user.id)); }
  function sources(row) {
    const items = [
      ...row.knowledgeSources.map(source => el('article', { class: 'chat-source' },
        el('strong', {}, source.title), el('span', { class: 'muted' }, `第 ${source.startLine}—${source.endLine} 行`), el('p', {}, source.text))),
      ...row.sources.map(source => el('article', { class: 'chat-source' }, el('strong', {}, source.id), el('p', {}, source.text)))
    ];
    return items.length ? el('details', { class: 'chat-sources' }, el('summary', {}, `查看规则来源（${items.length}）`), items) : null;
  }
  function update(row) {
    const ref = refs.get(row);
    if (!ref) return;
    const follow = messages.scrollHeight - messages.scrollTop - messages.clientHeight < 90;
    ref.text.textContent = row.text || (row.status === '准备中' ? '正在连接助手…' : row.status === '生成中' ? '正在查询并生成回答…' : '');
    ref.state.textContent = row.role === 'user' ? '' : row.status;
    ref.state.className = `chat-state${['未完成', '已停止'].includes(row.status) ? ' chat-state-error' : ''}`;
    ref.sources.replaceChildren();
    if (row.status === '完成') { const details = sources(row); if (details) ref.sources.append(details); }
    if (follow) messages.scrollTop = messages.scrollHeight;
  }
  function paint() {
    refs.clear(); messages.replaceChildren();
    if (!state.messages.length) messages.append(el('div', { class: 'chat-empty' },
      el('strong', {}, '有什么订单问题想了解？'), el('p', {}, '可咨询本人的订单和平台交易规则。助手只读，不代为支付、取消或退款。')));
    for (const row of state.messages) {
      const text = el('div', { class: 'chat-text' });
      const label = el('span', { class: 'chat-state' });
      const evidence = el('div');
      messages.append(el('article', { class: `chat-message ${row.role}` },
        el('div', { class: 'chat-message-heading' }, el('strong', {}, row.role === 'user' ? '你' : '交易助手'), label), text, evidence));
      refs.set(row, { text, state: label, sources: evidence }); update(row);
    }
    messages.scrollTop = messages.scrollHeight;
  }

  async function changeConversation(clearing) {
    if (busy || !valid()) return;
    confirmation.hidden = true;
    busy = true; controller = new AbortController(); controls();
    status.textContent = clearing ? '正在清空历史…' : '正在创建会话…';
    try {
      if (clearing) await write(`/api/agent/conversations/${encodeURIComponent(state.id)}/messages`, 'DELETE', undefined, false, controller.signal);
      else {
        const created = await write('/api/agent/conversations', 'POST', undefined, false, controller.signal);
        if (!valid()) return;
        remember(created.conversationId);
      }
      if (!valid()) return;
      state.messages = []; paint(); status.textContent = clearing ? '历史已清空，可以开始新的问题。' : '新会话已就绪。';
    } catch (error) {
      if (!valid()) return;
      if (error.name === 'AbortError') status.textContent = '操作已停止，结果尚未确认。';
      else { if (clearing && error.status === 404) forget(); status.textContent = failureMessage(error, !clearing); }
    } finally { if (valid()) { busy = false; controller = null; controls(); } }
  }

  async function submit(event) {
    event.preventDefault();
    if (busy || !valid() || !input.reportValidity()) return;
    const question = input.value.trim();
    if (!question || question.length > 2000) { status.textContent = '请输入1到2000字符的问题。'; return; }
    busy = true; controller = new AbortController();
    const row = { role: 'assistant', text: '', status: '准备中', sources: [], knowledgeSources: [] };
    activeAssistant = row;
    state.messages.push({ role: 'user', text: question, status: '完成', sources: [], knowledgeSources: [] }, row);
    // 本地只展示最近六轮，正文不写入浏览器存储。
    state.messages = state.messages.slice(-12);
    input.value = ''; paint(); controls(); status.textContent = '正在准备回答…';
    let creating = !state.id;
    try {
      if (creating) {
        const created = await write('/api/agent/conversations', 'POST', undefined, false, controller.signal);
        if (!valid()) return;
        remember(created.conversationId); creating = false;
      }
      await streamWrite(`/api/agent/conversations/${encodeURIComponent(state.id)}/messages/stream`, { message: question }, (name, payload) => {
        if (!valid()) return;
        if (name === 'start') { row.status = '生成中'; status.textContent = '助手正在查询并生成回答…'; }
        if (name === 'delta') { row.status = '生成中'; row.text += payload.text; }
        if (name === 'reset') { row.text = ''; row.status = '生成中'; }
        if (name === 'done') {
          row.text = payload.answer; row.status = '完成'; row.sources = payload.sources; row.knowledgeSources = payload.knowledgeSources;
          status.textContent = '回答已完成。';
        }
        update(row);
      }, controller.signal);
    } catch (error) {
      if (!valid()) return;
      row.status = error.name === 'AbortError' ? '已停止' : '未完成';
      row.sources = []; row.knowledgeSources = []; update(row);
      if (error.status === 404 && !creating) forget();
      status.textContent = error.name === 'AbortError' ? '已停止生成，回答是否已保存尚未确认；请勿自动重复发送。' : failureMessage(error, creating);
    } finally {
      if (valid()) { busy = false; controller = null; activeAssistant = null; controls(); input.focus(); }
    }
  }

  input.addEventListener('input', controls);
  input.addEventListener('keydown', event => {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
      event.preventDefault(); input.form.requestSubmit();
    }
  });
  const form = el('form', { class: 'chat-composer', onsubmit: submit },
    el('label', { htmlFor: 'agent-question' }, '你的问题'), input,
    el('div', { class: 'chat-compose-actions' }, el('span', { class: 'hint' }, 'Enter发送，Shift＋Enter换行'), counter, stop, send));
  const view = el('section', { class: 'chat-page' },
    heading('交易助手', '查询本人订单，了解交易规则。', create, clear), confirmation, status, messages, form);
  status.textContent = state.id && !state.messages.length ? '已接续上次会话，之前的消息不在当前页面显示；可清空历史后重新开始。' : '发送问题后会自动建立会话。';
  paint(); controls();
  dispose = () => {
    alive = false;
    if (activeAssistant && activeAssistant.status !== '完成') activeAssistant.status = '已停止';
    controller?.abort();
  };
  return view;
}
