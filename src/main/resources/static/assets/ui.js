import { errorMessage } from './api.js';

export function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === null || value === undefined) continue;
    if (key.startsWith('on')) node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'class') node.className = value;
    else if (key in node && !key.startsWith('aria')) node[key] = value;
    else node.setAttribute(key, value);
  }
  for (const child of children.flat(Infinity)) {
    if (child !== null && child !== undefined && child !== false) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}
export const money = value => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value);
export const time = value => value ? value.replace('T', ' ') : '—';
export const statusNames = { PENDING_PAYMENT: '待支付', PAID: '已支付', SHIPPED: '已发货', COMPLETED: '已完成', CANCELED: '已取消', CLOSED: '已关闭', ON_SALE: '在售', OFF_SALE: '已下架', AVAILABLE: '未占用', RESERVED: '订单占用中', USED: '已核销' };
export function badge(status) { return el('span', { class: `badge ${['PAID', 'COMPLETED', 'ON_SALE'].includes(status) ? 'success' : status === 'PENDING_PAYMENT' ? 'warn' : 'neutral'}` }, statusNames[status] || status); }
let noticeTimer;
export function notify(message, error = false) {
  clearTimeout(noticeTimer);
  const node = document.querySelector('#notice'); node.textContent = message;
  node.className = `notice${error ? ' error' : ''}`; node.hidden = false;
  node.setAttribute('role', error ? 'alert' : 'status');
  // 成功提示自动收起；错误提示保留，避免用户来不及阅读。
  if (!error) noticeTimer = setTimeout(() => { node.hidden = true; }, 3000);
}
let busy = 0;
export async function run(control, job) {
  if (control.disabled) return;
  control.disabled = true; busy++;
  document.querySelectorAll('[data-logout]').forEach(b => b.disabled = true);
  try { await job(); }
  catch (error) { notify(errorMessage(error), true); }
  finally {
    control.disabled = false; busy--;
    document.querySelectorAll('[data-logout]').forEach(b => b.disabled = busy > 0);
  }
}
export function button(label, job, style = 'secondary') {
  const b = el('button', { type: 'button', class: `button ${style}` }, label);
  if (job) b.addEventListener('click', () => run(b, job));
  return b;
}
export function heading(title, subtitle, ...actions) { return el('div', { class: 'page-heading' }, el('div', {}, el('h1', {}, title), el('p', {}, subtitle)), el('div', { class: 'actions' }, actions)); }
export function empty(message) { return el('p', { class: 'empty' }, message); }
export function line(label, value) { return el('div', { class: 'line' }, el('span', { class: 'muted' }, label), el('span', {}, value)); }
export function options(items, placeholder = '请选择') { return [el('option', { value: '' }, placeholder), ...items.map(([value, label]) => el('option', { value: String(value) }, label))]; }
export function field(name, label, config = {}) {
  const { type = 'text', value = '', choices, wide, hint, ...attrs } = config;
  const input = choices ? el('select', { name, ...attrs }, options(choices, config.placeholder || '请选择')) : el('input', { name, type, ...attrs });
  input.setAttribute('aria-label', label);
  input.value = value;
  return { input, node: el('label', { class: `field${wide ? ' wide' : ''}` }, el('span', {}, label), input, hint ? el('span', { class: 'hint' }, hint) : null) };
}
export function modal(title, content) {
  const dialog = document.querySelector('#modal');
  const close = button('关闭', () => dialog.close(), 'quiet');
  document.querySelector('#modal-content').replaceChildren(el('div', { class: 'dialog-head' }, el('h2', { id: 'modal-title' }, title), close), content);
  if (!dialog.open) dialog.showModal();
  return dialog;
}
export function formDialog(title, definitions, submitLabel, handler, note = '') {
  const fields = {};
  const form = el('form', {});
  const error = el('p', { class: 'form-error', role: 'alert', hidden: true });
  const grid = el('div', { class: 'form-grid' });
  for (const [name, label, config] of definitions) {
    const item = field(name, label, config); fields[name] = item.input; grid.append(item.node);
  }
  const submit = el('button', { type: 'submit', class: 'button' }, submitLabel);
  form.append(grid, error, note ? el('p', { class: 'section-note' }, note) : '', el('div', { class: 'actions' }, submit));
  const dialog = modal(title, form);
  form.addEventListener('submit', async event => {
    event.preventDefault(); if (submit.disabled) return;
    error.hidden = true; submit.disabled = true;
    busy++; document.querySelectorAll('[data-logout]').forEach(b => b.disabled = true);
    // 冻结表单，防止等待网络时修改已经提交的购买内容。
    Object.values(fields).forEach(input => input.disabled = true);
    try { await handler(Object.fromEntries(Object.entries(fields).map(([name, input]) => [name, input.value]))); if (dialog.open && document.querySelector('#modal-content').contains(form)) dialog.close(); }
    catch (failure) { error.textContent = errorMessage(failure); error.hidden = false; }
    finally { submit.disabled = false; Object.values(fields).forEach(input => input.disabled = false); busy--; document.querySelectorAll('[data-logout]').forEach(b => b.disabled = busy > 0); }
  });
  return { form, fields, dialog };
}
export const numberField = (value = '', extra = {}) => ({ type: 'number', min: 1, max: 2147483647, step: 1, required: true, value, ...extra });
export function addressLabel(a) { return `${a.recipientName} · ${a.province}${a.city}${a.district}${a.detailAddress}${a.defaultAddress ? '（默认）' : ''}`; }
export function pager(page, size, total, change) {
  const prev = button('上一页', () => change(page - 1)); prev.disabled = page <= 1;
  const next = button('下一页', () => change(page + 1)); next.disabled = page * size >= total;
  return el('div', { class: 'pager' }, prev, el('span', { class: 'muted' }, `第 ${page} 页 · 共 ${total} 条`), next);
}
