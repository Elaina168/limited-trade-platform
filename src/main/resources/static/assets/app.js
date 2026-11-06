import { api, write, ApiError, UserError, errorMessage, onSessionExpired, advanceSession, pendingOrder, placeOrder, clearPendingOrder } from './api.js';
import { el, button, heading, empty, line, money, time, badge, modal, formDialog, field, options, numberField, addressLabel, notify, pager, statusNames } from './ui.js';
import { adminPage } from './admin.js';
import { agentPage, disposeAgentPage, resetAgentIdentity } from './agent.js';

const state = { user: null, ready: false };
let version = 0;
let identityEpoch = 0;
function setUser(user) {
  if (state.user?.id !== user?.id) resetAgentIdentity(state.user?.id);
  state.user = user; identityEpoch++; advanceSession();
}
function captureView(sessionOnly = false) {
  const pageVersion = version, identity = identityEpoch;
  return () => identity === identityEpoch && (sessionOnly || pageVersion === version);
}
const main = document.querySelector('#main');
const path = () => location.hash.slice(1).split('?')[0] || 'products';
function go(route) { if (location.hash === `#${route}`) return navigate(); location.hash = route; }
function needUser() {
  if (state.user) return true;
  openAuth(); return false;
}
function account() {
  const node = document.querySelector('#account');
  document.querySelector('#admin-link').hidden = state.user?.role !== 'ADMIN';
  if (state.user) {
    const logout = button('退出', async () => {
      await write('/api/auth/logout'); setUser(null);
      document.querySelector('#modal').close(); account(); await navigate(); notify('已退出登录。');
    }, 'quiet');
    logout.setAttribute('data-logout', '');
    node.replaceChildren(el('strong', {}, state.user.username), badge(state.user.role === 'ADMIN' ? '管理员' : '普通用户'), logout);
  } else node.replaceChildren(button('登录 / 注册', () => openAuth(), ''));
}
async function readIdentity() {
  try { setUser(await api('/api/auth/me')); }
  catch (error) { if (error instanceof ApiError && error.status === 401) setUser(null); else throw error; }
  state.ready = true; account();
}
onSessionExpired(() => {
  setUser(null); document.querySelector('#modal').close(); account();
  version++; main.replaceChildren(empty('登录已过期，请重新登录。'), button('重新登录', () => openAuth(), ''));
});

function openAuth(mode = 'login', username = '') {
  const register = mode === 'register';
  const view = formDialog(register ? '创建你的账号' : '欢迎回来', [
    ['username', '用户名', { required: true, value: username, autocomplete: 'username', ...(register ? { pattern: '[A-Za-z0-9_]{3,50}', minLength: 3, maxLength: 50 } : {}), wide: true }],
    ['password', '密码', { type: 'password', required: true, minLength: register ? 8 : 1, autocomplete: register ? 'new-password' : 'current-password', wide: true }]
  ], register ? '注册账号' : '登录', async values => {
    if (register) {
      if (new TextEncoder().encode(values.password).length > 72) throw new UserError('密码UTF-8编码不能超过72字节。');
      try { await write('/api/auth/register', 'POST', values); }
      catch (error) { if (error instanceof ApiError && error.status === 409) throw new UserError('这个用户名已被使用，请换一个。'); throw error; }
      view.form.reset(); notify('注册成功，请使用新账号登录。'); openAuth('login', values.username);
    } else {
      await write('/api/auth/login', 'POST', values, true);
      view.form.reset(); await readIdentity(); await navigate(); notify('登录成功。');
    }
  }, register ? '用户名为3—50位字母、数字或下划线；密码至少8个字符，UTF-8编码最多72字节。' : '使用账号密码登录，继续你的购买流程。');
  view.form.prepend(el('div', { class: 'tabs' }, button('登录', () => openAuth('login'), register ? 'quiet' : ''), button('注册', () => openAuth('register'), register ? '' : 'quiet')));
}

function pendingBanner() {
  const intent = state.user && pendingOrder(state.user.id);
  if (!intent) return null;
  return el('aside', { class: 'pending' }, el('p', {}, '上一笔下单的结果尚未确认。请查询订单，或使用原请求重试，避免重复购买。'),
    el('div', { class: 'actions' }, button('使用原请求重试', async () => {
      const current = captureView();
      const order = await placeOrder(state.user.id, intent.path, intent.data);
      if (!current()) return;
      notify(`订单 #${order.id} 已确认。`); await go('orders'); await showOrder(order);
    }), button('查询我的订单', () => go('orders'))));
}

async function productsPage() {
  const query = new URLSearchParams(location.hash.split('?')[1] || '');
  const page = Number(query.get('page') || 1);
  const [categories, result] = await Promise.all([api('/api/categories'), api(`/api/products/search?${new URLSearchParams({ page: String(page), size: '12', ...(query.get('keyword') ? { keyword: query.get('keyword') } : {}), ...(query.get('categoryId') ? { categoryId: query.get('categoryId') } : {}) })}`)]);
  const keyword = el('input', { class: 'search', name: 'keyword', placeholder: '搜索商品名称', maxLength: 100, value: query.get('keyword') || '', 'aria-label': '搜索商品名称' });
  const category = el('select', { 'aria-label': '商品分类' }, options(categories.map(c => [c.id, c.name]), '全部分类')); category.value = query.get('categoryId') || '';
  const search = el('form', { class: 'toolbar' }, keyword, category, el('button', { class: 'button', type: 'submit' }, '搜索'));
  search.addEventListener('submit', event => { event.preventDefault(); go(`products?${new URLSearchParams({ page: '1', keyword: keyword.value, categoryId: category.value })}`); });
  return el('section', {}, el('div', { class: 'hero' }, el('div', { class: 'eyebrow' }, 'GOOD FINDS, LIMITED MOMENTS'), el('h1', {}, '发现值得入手的好物'), el('p', {}, '浏览在售商品，挑选喜欢的好物；限时限量活动和专属满减券，也在这里。'), button('看看限量活动', () => go('activities'), '')),
    search, pendingBanner(), heading('正在售卖', `共 ${result.total} 件商品`, button('刷新', navigate)),
    result.items.length ? el('div', { class: 'grid' }, result.items.map(product => el('article', { class: 'card product-card' },
      el('div', { class: 'product-art', 'aria-hidden': 'true' }, product.name.slice(0, 2)), el('div', { class: 'product-body' },
        el('span', { class: 'badge' }, product.categoryName || '好物精选'), el('h2', {}, product.name), el('div', { class: 'price' }, money(product.price)),
        el('p', { class: 'muted' }, `库存 ${product.stock} · 商品 #${product.id}`), el('div', { class: 'actions' }, button('立即购买', () => openBuy(product), ''), button('加入购物车', () => addToCart(product))))))) : empty('没有找到符合条件的在售商品。'),
    pager(page, 12, result.total, next => { query.set('page', next); return go(`products?${query}`); }));
}

async function addToCart(product) {
  if (!needUser()) return;
  const current = captureView();
  const cart = await api('/api/cart/items'); const existing = cart.find(item => item.productId === product.id);
  if (!current()) return;
  const quantity = (existing?.quantity || 0) + 1;
  await write(`/api/cart/items/${product.id}`, 'PUT', { quantity }); notify(`已加入购物车：${product.name}，当前数量 ${quantity}。`);
}

async function openBuy(product, activity = null) {
  if (!needUser()) return;
  const current = captureView();
  const [addresses, coupons] = await Promise.all([api('/api/addresses'), activity ? api('/api/coupons/mine') : Promise.resolve([])]);
  if (!current()) return;
  if (!addresses.length) { notify('请先添加收货地址，再下单。', true); return go('addresses'); }
  const usable = coupons.filter(c => c.status === 'AVAILABLE' && !c.expired && c.coupon.activityId === activity?.id && new Date(c.coupon.useStartsAt) <= new Date());
  const definitions = [
    ['quantity', '购买数量', numberField(1)],
    ['addressId', '收货地址', { choices: addresses.map(a => [a.id, addressLabel(a)]), value: addresses.find(a => a.defaultAddress)?.id || '', required: true }]
  ];
  if (activity) definitions.push(['userCouponId', '活动优惠券', { choices: usable.map(c => [c.id, `${c.coupon.name} · 满${money(c.coupon.thresholdAmount)}减${money(c.coupon.discountAmount)}`]), placeholder: '不使用优惠券', wide: true }]);
  formDialog(activity ? `参加活动 · ${activity.name}` : `购买 · ${product.name}`, definitions, '提交订单', async values => {
    const currentSession = captureView(true);
    const data = { productId: product.id, quantity: Number(values.quantity), activityId: activity?.id || null, userCouponId: values.userCouponId ? Number(values.userCouponId) : null, addressId: Number(values.addressId) };
    const order = await placeOrder(state.user.id, '/api/orders', data);
    if (!currentSession()) return;
    notify(`订单 #${order.id} 创建成功，应付 ${money(order.totalAmount)}。`); await go('orders'); await showOrder(order);
  }, `商品单价 ${money(product.price)}。金额、库存、活动名额和优惠券是否可用，以提交时的服务器结果为准。`);
}

function activityPhase(activity) {
  const now = new Date();
  return now < new Date(activity.startsAt) ? '即将开始' : now >= new Date(activity.endsAt) ? '已结束' : activity.remainingQuantity === 0 ? '名额已用完' : '进行中';
}
async function activitiesPage() {
  const activities = await api('/api/activities');
  return el('section', {}, heading('限时限量活动', '活动名额与商品库存分别计算，先领券再购买。', button('刷新', navigate)), pendingBanner(),
    activities.length ? el('div', { class: 'grid' }, activities.map(a => el('article', { class: 'card' }, badge(activityPhase(a)), el('h2', {}, a.name), line('活动编号', `#${a.id}`), line('剩余名额', `${a.remainingQuantity} / ${a.totalQuantity}`), el('p', { class: 'muted' }, `${time(a.startsAt)} 至 ${time(a.endsAt)}`), button('查看活动与领券', () => showActivity(a), '')))) : empty('暂时没有活动。'));
}
async function showActivity(activity) {
  const current = captureView();
  const [coupons, products] = await Promise.all([api(`/api/activities/${activity.id}/coupons`), api('/api/products')]);
  if (!current()) return;
  const product = products.find(p => p.id === activity.productId);
  const buy = button('参加活动下单', () => openBuy(product, activity), ''); buy.disabled = !product || activityPhase(activity) !== '进行中';
  modal(activity.name, el('div', {}, line('活动时间', `${time(activity.startsAt)} 至 ${time(activity.endsAt)}`), line('剩余活动名额', activity.remainingQuantity), product ? line(product.name, money(product.price)) : empty('活动商品当前不在售。'), buy,
    el('h3', {}, '活动专用优惠券'), coupons.length ? el('div', { class: 'stack' }, coupons.map(c => {
      const claim = button('领取优惠券', async () => {
        if (!needUser()) return;
        const current = captureView();
        const owned = await write(`/api/coupons/${c.id}/claim`);
        if (!current()) return;
        notify(`已领取 ${owned.coupon.name}，可在我的券中查看。`); await showActivity(activity);
      });
      claim.disabled = new Date() < new Date(c.claimStartsAt) || new Date() >= new Date(c.claimEndsAt) || c.remainingQuantity === 0;
      return el('article', { class: 'card' }, el('h3', {}, c.name), line('满减', `满${money(c.thresholdAmount)}减${money(c.discountAmount)}`), line('剩余可领', c.remainingQuantity), el('p', { class: 'muted' }, `领取：${time(c.claimStartsAt)} 至 ${time(c.claimEndsAt)}`), el('p', { class: 'muted' }, `使用：${time(c.useStartsAt)} 至 ${time(c.useEndsAt)}`), claim);
    })) : empty('该活动暂时没有优惠券。')));
}

async function cartPage() {
  const [items, addresses] = await Promise.all([api('/api/cart/items'), api('/api/addresses')]);
  const selections = [];
  const rows = items.map(item => {
    const checked = el('input', { type: 'checkbox', 'aria-label': `选择${item.name}`, disabled: item.status !== 'ON_SALE' });
    const quantity = el('input', { type: 'number', min: 1, max: 2147483647, step: 1, value: item.quantity, 'aria-label': `${item.name}数量` });
    selections.push([item.productId, checked, quantity, item.quantity]);
    return el('article', { class: 'card cart-row' }, checked, el('div', {}, el('strong', {}, item.name), el('div', { class: 'muted' }, `库存 ${item.stock} · ${statusNames[item.status]}`)), el('span', { class: 'price' }, money(item.price)), quantity,
      el('div', { class: 'actions' }, button('保存数量', async () => {
        if (!quantity.reportValidity()) return;
        await write(`/api/cart/items/${item.productId}`, 'PUT', { quantity: Number(quantity.value) }); notify('购物车数量已保存。'); await navigate();
      }), button('移除', async () => { await write(`/api/cart/items/${item.productId}`, 'DELETE'); await navigate(); notify('商品已移出购物车。'); }, 'danger')));
  });
  const address = field('addressId', '结算收货地址', { choices: addresses.map(a => [a.id, addressLabel(a)]), value: addresses.find(a => a.defaultAddress)?.id || '', required: true });
  const checkout = button('结算选中商品', async () => {
    const current = captureView();
    const productIds = selections.filter(([, input]) => input.checked).map(([id]) => id).sort((a, b) => a - b);
    if (!productIds.length) throw new UserError('请至少选中一件商品。');
    if (selections.some(([, checked, quantity, saved]) => checked.checked && Number(quantity.value) !== saved)) throw new UserError('选中商品的数量有未保存的修改，请先保存数量。');
    if (!address.input.value) throw new UserError('请选择收货地址；没有地址时请先到收货地址页添加。');
    const order = await placeOrder(state.user.id, '/api/orders/checkout', { productIds, addressId: Number(address.input.value) });
    if (!current()) return;
    notify(`结算成功，订单 #${order.id}。`); await go('orders'); await showOrder(order);
  }, '');
  return el('section', {}, heading('我的购物车', '数量修改后请先保存，再勾选结算。购物车结算暂不使用活动券。', button('继续选购', () => go('products'))), pendingBanner(),
    items.length ? el('div', {}, el('div', { class: 'stack' }, rows), el('div', { class: 'summary' }, address.node, checkout)) : empty('购物车还是空的，去看看在售商品吧。'));
}

const addressDefinitions = a => [
  ['recipientName', '收货人', { required: true, maxLength: 50, value: a?.recipientName || '' }],
  ['phone', '手机号码', { required: true, pattern: '1[3-9][0-9]{9}', inputMode: 'tel', value: a?.phone || '' }],
  ...[['province', '省'], ['city', '市'], ['district', '区 / 县']].map(([key, name]) => [key, name, { required: true, maxLength: 50, value: a?.[key] || '' }]),
  ['detailAddress', '详细地址', { required: true, maxLength: 200, wide: true, value: a?.detailAddress || '' }]
];
function editAddress(address = null) {
  formDialog(address ? '编辑收货地址' : '新增收货地址', addressDefinitions(address), '保存地址', async values => {
    await write(`/api/addresses${address ? `/${address.id}` : ''}`, address ? 'PUT' : 'POST', values); await navigate(); notify('收货地址已保存。');
  });
}
async function addressesPage() {
  const addresses = await api('/api/addresses');
  return el('section', {}, heading('收货地址', '订单保存成交时的地址，修改地址簿不改变历史订单。', button('新增地址', () => editAddress(), '')),
    addresses.length ? el('div', { class: 'grid' }, addresses.map(a => el('article', { class: 'card' }, a.defaultAddress ? badge('默认地址') : null, el('h2', {}, a.recipientName), el('p', {}, a.phone), el('p', { class: 'muted' }, `${a.province}${a.city}${a.district}${a.detailAddress}`), el('div', { class: 'actions' }, button('编辑', () => editAddress(a)), !a.defaultAddress ? button('设为默认', async () => { await write(`/api/addresses/${a.id}/default`, 'PUT'); await navigate(); }) : null, button('删除', async () => {
      formDialog('确认删除地址', [], '确认删除', async () => { await write(`/api/addresses/${a.id}`, 'DELETE'); await navigate(); notify('地址已删除，历史订单快照仍保留。'); }, `删除 ${a.recipientName} 的这条地址？`);
    }, 'danger'))))) : empty('还没有收货地址，先添加一条吧。'));
}

async function couponsPage() {
  const coupons = await api('/api/coupons/mine');
  return el('section', {}, heading('我的优惠券', '每人每种券限领一张；释放后的券仍需在使用期限内。', button('去领券', () => go('activities'))), coupons.length ? el('div', { class: 'grid' }, coupons.map(c => el('article', { class: 'card' }, badge(c.expired ? '已过期' : c.status), el('h2', {}, c.coupon.name), el('p', { class: 'price' }, `减 ${money(c.coupon.discountAmount)}`), el('p', { class: 'muted' }, `满 ${money(c.coupon.thresholdAmount)} 可用 · 活动 #${c.coupon.activityId}`), el('p', { class: 'muted' }, `${time(c.coupon.useStartsAt)} 至 ${time(c.coupon.useEndsAt)}`), c.orderId ? line('关联订单', `#${c.orderId}`) : null))) : empty('还没有领取优惠券。'));
}

async function viewOrder(id, admin = false) {
  const current = captureView();
  const order = await api(`/api/${admin ? 'admin/' : ''}orders/${id}`);
  if (current()) await showOrder(order, admin);
}
export async function showOrder(order, admin = false) {
  const content = el('div', {}, badge(order.status), el('div', { class: 'order-items' }, order.items.map(item => el('div', { class: 'order-item' }, el('span', {}, `${item.productName} × ${item.quantity}`), el('strong', {}, money(item.lineAmount))))),
    line('原金额', money(order.originalAmount)), line('优惠', money(order.discountAmount)), line('应付', money(order.totalAmount)), order.coupon ? line('优惠券', order.coupon.name) : null,
    line('创建时间', time(order.createdAt)), line('支付截止', time(order.expiresAt)), line('支付时间', time(order.paidAt)), order.endedAt ? line('取消 / 关闭时间', time(order.endedAt)) : null);
  content.append(el('h3', {}, '收货地址'), order.address ? el('p', { class: 'details' }, `${order.address.recipientName} · ${order.address.phone}\n${order.address.province}${order.address.city}${order.address.district}${order.address.detailAddress}`) : empty('该订单未保存地址，不能发货。'));
  if (order.shipment) content.append(el('h3', {}, '物流信息'), line('承运方', order.shipment.carrier), line('运单号', order.shipment.trackingNo), line('发货时间', time(order.shipment.shippedAt)), line('收货时间', time(order.shipment.receivedAt)));
  const actions = el('div', { class: 'actions' });
  if (admin && order.status === 'PAID' && order.address) actions.append(button('登记发货', () => {
    formDialog(`订单 #${order.id} 发货`, [['carrier', '承运方', { required: true, maxLength: 50 }], ['trackingNo', '运单号', { required: true, maxLength: 64 }]], '确认发货', async values => {
      const current = captureView(true);
      const updated = await write(`/api/admin/orders/${order.id}/ship`, 'POST', values);
      if (!current()) return;
      notify('发货信息已保存。'); await navigate(); if (current()) await showOrder(updated, true);
    }, '仅登记人工发货信息，不代表调用真实物流服务。');
  }, ''));
  if (!admin) {
    const action = (label, name) => button(label, async () => { const current = captureView(true); const updated = await write(`/api/orders/${order.id}/${name}`); if (!current()) return; notify('订单状态已更新。'); await navigate(); if (current()) await showOrder(updated); }, name === 'cancel' ? 'danger' : '');
    if (order.status === 'PENDING_PAYMENT') {
      if (new Date(order.expiresAt) > new Date()) actions.append(action('模拟支付', 'pay'));
      actions.append(action('取消订单', 'cancel'));
    }
    if (order.status === 'SHIPPED') actions.append(action('确认收货', 'receive'));
  }
  content.append(actions); modal(`订单 #${order.id}`, content);
}
async function ordersPage() {
  const query = new URLSearchParams(location.hash.split('?')[1] || '');
  const page = Number(query.get('page') || 1);
  const params = new URLSearchParams({ page: String(page), size: '20' });
  if (query.get('status')) params.set('status', query.get('status'));
  const result = await api(`/api/orders?${params}`);
  const orders = result.items; const pending = pendingOrder(state.user.id);
  if (pending && orders.some(order => order.requestId === pending.requestId)) clearPendingOrder(state.user.id);
  const status = field('status', '订单状态', { choices: ['PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELED', 'CLOSED'].map(key => [key, statusNames[key]]), placeholder: '全部状态', value: query.get('status') || '' });
  const form = el('form', { class: 'toolbar' }, status.node, el('button', { type: 'submit', class: 'button' }, '筛选订单'));
  form.addEventListener('submit', event => { event.preventDefault(); go(`orders?${new URLSearchParams({ page: '1', status: status.input.value })}`); });
  return el('section', {}, heading('我的订单', '支付、取消和收货均以服务器最新状态为准。', button('刷新订单', navigate)), form, pendingBanner(), orders.length ? el('div', { class: 'stack' }, orders.map(order => el('article', { class: 'card' },
    el('div', { class: 'order-top' }, el('strong', {}, `订单 #${order.id}`), badge(order.status)), el('div', { class: 'order-items' }, order.items.map(item => el('div', { class: 'order-item' }, `${item.productName} × ${item.quantity}`, money(item.lineAmount)))),
    el('div', { class: 'line' }, el('span', { class: 'muted' }, time(order.createdAt)), el('strong', {}, `应付 ${money(order.totalAmount)}`)), button('查看订单', () => viewOrder(order.id))))) : empty('没有符合条件的订单。'),
    pager(result.page, result.size, result.total, next => { query.set('page', next); return go(`orders?${query}`); }));
}

async function navigate() {
  disposeAgentPage();
  const stamp = ++version; const userId = state.user?.id;
  const route = path();
  document.querySelectorAll('nav a').forEach(a => a.classList.toggle('active', a.hash.split('?')[0] === `#${route}`));
  main.replaceChildren(empty('正在加载…'));
  try {
    if (['cart', 'addresses', 'coupons', 'orders', 'admin', 'agent'].includes(route) && !state.user) {
      main.replaceChildren(heading('登录后继续', route === 'agent' ? '登录后即可咨询本人订单和平台交易规则。' : '登录即可管理你的购物车、地址、优惠券和订单。'), button('登录 / 注册', () => openAuth(), '')); return;
    }
    const render = { products: productsPage, activities: activitiesPage, cart: cartPage, addresses: addressesPage, coupons: couponsPage, orders: ordersPage,
      agent: () => agentPage(state.user, captureView()),
      admin: () => adminPage(state.user, { go, viewOrder, guard: captureView, refresh: navigate }) }[route];
    const view = render ? await render() : await productsPage();
    if (stamp === version && userId === state.user?.id) main.replaceChildren(view);
  } catch (error) {
    if (stamp !== version) return;
    main.replaceChildren(empty(errorMessage(error)), button('重试加载', navigate));
  }
}
window.addEventListener('hashchange', navigate);
try { await readIdentity(); }
catch (error) { document.querySelector('#account').replaceChildren(button('重试登录状态', async () => { await readIdentity(); await navigate(); })); notify(errorMessage(error), true); }
await navigate();
