import { api, write } from './api.js';
import { el, button, heading, empty, line, money, time, badge, modal, formDialog, field, numberField, pager, statusNames, notify } from './ui.js';

export async function adminPage(user, context) {
  if (user.role !== 'ADMIN') return empty('当前账号没有管理权限。');
  const params = new URLSearchParams(location.hash.split('?')[1] || '');
  const tab = params.get('tab') || 'products';
  const tabs = [['products', '商品管理'], ['categories', '分类管理'], ['activities', '活动管理'], ['coupons', '优惠券规则'], ['orders', '订单履约']];
  const navigation = el('div', { class: 'tabs' }, tabs.map(([key, name]) => button(name, () => context.go(`admin?tab=${key}`), tab === key ? '' : 'secondary')));
  const finish = async message => { await context.refresh(); notify(message); };
  const loaders = { products, categories, activities, coupons, orders };
  return el('section', {}, heading('管理中心', '维护在售内容，查看成交订单，登记发货。'), navigation, await (loaders[tab] || products)());

  async function products() {
    const [items, categories] = await Promise.all([api('/api/admin/products'), api('/api/categories')]);
    function edit(product = null) {
      const definitions = [
        ['name', '商品名称', { required: true, maxLength: 100, value: product?.name || '', wide: true }],
        ['price', '销售单价', { type: 'number', min: '0.01', max: '99999999.99', step: '0.01', required: true, value: product?.price || '' }],
        ['categoryId', '分类', { choices: categories.map(c => [c.id, c.name]), value: product?.categoryId || '', placeholder: '不分类' }]
      ];
      if (!product) definitions.push(['stock', '初始库存', numberField(0, { min: 0 })]);
      formDialog(product ? `编辑商品 #${product.id}` : '创建商品', definitions, '保存商品', async values => {
        const data = { name: values.name, price: values.price, categoryId: values.categoryId ? Number(values.categoryId) : null };
        if (!product) data.stock = Number(values.stock);
        await write(`/api/admin/products${product ? `/${product.id}` : ''}`, product ? 'PUT' : 'POST', data);
        await finish(product ? '商品信息已更新。' : '商品已创建，初始为下架状态，可在列表中上架。');
      }, product ? '当前接口只修改名称、价格和分类，库存由交易流程维护。' : '新商品保存后为下架状态，需要单独上架。');
    }
    return el('div', {}, el('div', { class: 'page-heading' }, el('h2', {}, '全部商品'), button('创建商品', () => edit(), '')), items.length ? el('div', { class: 'admin-list' }, items.map(p => el('article', { class: 'card admin-row' },
      el('div', {}, el('strong', {}, `#${p.id} ${p.name}`), el('div', { class: 'muted' }, `${money(p.price)} · 库存 ${p.stock}`), badge(p.status)),
      el('div', { class: 'actions' }, button('编辑商品', () => edit(p)), button(p.status === 'ON_SALE' ? '下架' : '上架', async () => {
        await write(`/api/admin/products/${p.id}/status`, 'PATCH', { status: p.status === 'ON_SALE' ? 'OFF_SALE' : 'ON_SALE' }); await finish('商品状态已更新。');
      }))))) : empty('还没有商品。'));
  }
  async function categories() {
    const items = await api('/api/categories');
    const edit = category => formDialog(category ? '重命名分类' : '创建分类', [['name', '分类名称', { required: true, maxLength: 50, value: category?.name || '', wide: true }]], '保存分类', async values => {
      await write(`/api/admin/categories${category ? `/${category.id}` : ''}`, category ? 'PUT' : 'POST', values); await finish('分类已保存。');
    });
    return el('div', {}, el('div', { class: 'page-heading' }, el('h2', {}, '商品分类'), button('创建分类', () => edit(null), '')), items.length ? el('div', { class: 'admin-list' }, items.map(c => el('article', { class: 'card admin-row' }, el('strong', {}, `#${c.id} ${c.name}`), button('重命名', () => edit(c))))) : empty('还没有分类。'));
  }
  async function activities() {
    const [items, products] = await Promise.all([api('/api/activities'), api('/api/admin/products')]);
    const create = () => formDialog('创建限时限量活动', [
      ['productId', '活动商品', { choices: products.map(p => [p.id, `#${p.id} ${p.name}（${statusNames[p.status]}）`]), required: true, wide: true }],
      ['name', '活动名称', { required: true, maxLength: 100, wide: true }], ['totalQuantity', '销售名额', numberField()],
      ['startsAt', '活动开始', { type: 'datetime-local', step: 1, required: true }], ['endsAt', '活动截止', { type: 'datetime-local', step: 1, required: true }]
    ], '创建活动', async values => {
      const result = await write('/api/admin/activities', 'POST', { ...values, productId: Number(values.productId), totalQuantity: Number(values.totalQuantity) });
      await finish(`活动 #${result.id} 已创建，可前往活动页查看。`);
    }, '活动名额不代表提前预留库存。当前活动规则只创建，不提供修改入口。');
    return el('div', {}, el('div', { class: 'page-heading' }, el('h2', {}, '活动列表'), button('创建活动', create, '')), items.length ? el('div', { class: 'grid' }, items.map(a => el('article', { class: 'card' }, el('h3', {}, `#${a.id} ${a.name}`), line('商品', `#${a.productId}`), line('剩余名额', `${a.remainingQuantity}/${a.totalQuantity}`), el('p', { class: 'muted' }, `${time(a.startsAt)} 至 ${time(a.endsAt)}`)))) : empty('还没有活动。'));
  }
  async function coupons() {
    const activities = await api('/api/activities');
    const create = () => formDialog('创建活动满减券', [
      ['activityId', '绑定活动', { choices: activities.map(a => [a.id, `#${a.id} ${a.name}`]), required: true, wide: true }],
      ['name', '优惠券名称', { required: true, maxLength: 100, wide: true }],
      ['thresholdAmount', '满减门槛', { type: 'number', min: '.01', max: '99999999.99', step: '.01', required: true }],
      ['discountAmount', '优惠金额', { type: 'number', min: '.01', max: '99999999.99', step: '.01', required: true }],
      ['totalQuantity', '发放总量', numberField()],
      ...[['claimStartsAt', '领取开始'], ['claimEndsAt', '领取截止'], ['useStartsAt', '使用开始'], ['useEndsAt', '使用截止']].map(([name, label]) => [name, label, { type: 'datetime-local', step: 1, required: true }])
    ], '创建优惠券', async values => {
      await write('/api/admin/coupons', 'POST', { ...values, activityId: Number(values.activityId), totalQuantity: Number(values.totalQuantity) }); await finish('活动优惠券规则已创建。');
    }, '优惠金额须小于门槛；使用区间须在活动区间内，领取截止不晚于使用截止。规则创建后不修改。');
    return el('div', {}, el('div', { class: 'page-heading' }, el('h2', {}, '按活动查看券规则'), button('创建优惠券', create, '')), activities.length ? el('div', { class: 'admin-list' }, activities.map(a => el('article', { class: 'card admin-row' }, el('strong', {}, `#${a.id} ${a.name}`), button('查看券规则', async () => {
      const current = context.guard();
      const items = await api(`/api/activities/${a.id}/coupons`);
      if (!current()) return;
      modal(`${a.name} · 券规则`, items.length ? el('div', { class: 'stack' }, items.map(c => el('article', { class: 'card' }, el('h3', {}, c.name), line('满减规则', `满${money(c.thresholdAmount)}减${money(c.discountAmount)}`), line('剩余可领', `${c.remainingQuantity}/${c.totalQuantity}`), line('领取时间', `${time(c.claimStartsAt)} 至 ${time(c.claimEndsAt)}`), line('使用时间', `${time(c.useStartsAt)} 至 ${time(c.useEndsAt)}`)))) : empty('该活动还没有券规则。'));
    })))) : empty('请先创建一个活动。'));
  }
  async function orders() {
    const page = Number(params.get('page') || 1);
    const query = new URLSearchParams({ page: String(page), size: '20' });
    for (const key of ['status', 'userId']) if (params.get(key)) query.set(key, params.get(key));
    const result = await api(`/api/admin/orders?${query}`);
    const status = field('status', '订单状态', { choices: ['PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELED', 'CLOSED'].map(key => [key, statusNames[key]]), placeholder: '全部状态', value: params.get('status') || '' });
    const userId = field('userId', '用户编号', numberField(params.get('userId') || '', { required: false }));
    const form = el('form', { class: 'toolbar' }, status.node, userId.node, el('button', { type: 'submit', class: 'button' }, '筛选订单'));
    form.addEventListener('submit', event => { event.preventDefault(); context.go(`admin?${new URLSearchParams({ tab: 'orders', status: status.input.value, userId: userId.input.value })}`); });
    return el('div', {}, form, result.items.length ? el('div', { class: 'admin-list' }, result.items.map(o => el('article', { class: 'card admin-row' }, el('div', {}, el('strong', {}, `订单 #${o.id} · 用户 #${o.userId}`), line('应付', money(o.totalAmount)), badge(o.status)), button('查看详情 / 发货', () => context.viewOrder(o.id, true))))) : empty('没有符合条件的订单。'), pager(page, 20, result.total, next => { params.set('page', next); return context.go(`admin?${params}`); }));
  }
}
