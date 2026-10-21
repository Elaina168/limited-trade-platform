// 只验证本次前端；使用浏览器契约夹具，不启动HTTP服务或访问开发库。
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/resources/static');
const past='2020-01-01T00:00:00', future='2099-01-01T00:00:00';
const address={id:11,recipientName:'张三',phone:'13800138000',province:'浙江',city:'杭州',district:'西湖',detailAddress:'学院路1号',defaultAddress:true};
const coupon={id:31,activityId:21,name:'满200减20',thresholdAmount:200,discountAmount:20,totalQuantity:5,remainingQuantity:5,claimStartsAt:past,claimEndsAt:future,useStartsAt:past,useEndsAt:future};
async function fixture(browser,role=null){
  const context=await browser.newContext(),page=await context.newPage();page.setDefaultTimeout(5000);
  const s={user:role?{id:1,username:'learner',role}:null,token:1,requests:[],errors:[],failure:null,
    categories:[{id:1,name:'数码'}],products:[{id:1,name:'机械键盘',price:129,stock:9,status:'ON_SALE',categoryId:1,categoryName:'数码'},{id:2,name:'备用鼠标',price:20,stock:2,status:'OFF_SALE',categoryId:null}],
    addresses:[{...address}],cart:[],owned:[],orders:[],coupons:[{...coupon}],activities:[{id:21,productId:1,name:'键盘限量专场',totalQuantity:5,remainingQuantity:5,startsAt:past,endsAt:future}]};
  page.on('pageerror',e=>{s.errors.push(e.message);console.error('浏览器脚本错误:',e.message);});
  await page.route('http://shop.test/**',async route=>{
    const req=route.request(),url=new URL(req.url()),p=url.pathname,m=req.method();
    const json=(v,status=200,headers={})=>route.fulfill({status,contentType:'application/json',headers,body:JSON.stringify(v)});
    const done=()=>route.fulfill({status:204});
    if(!p.startsWith('/api/')){const f=path.join(root,p==='/'?'index.html':p);return fs.existsSync(f)?route.fulfill({path:f,contentType:p.endsWith('.js')?'application/javascript':p.endsWith('.css')?'text/css':'text/html'}):route.fulfill({status:404,body:'尚未实现'});}
    const d=req.postData()?(req.headers()['content-type']?.includes('application/json')?req.postDataJSON():Object.fromEntries(new URLSearchParams(req.postData()))):null;
    s.requests.push({p,m,d,query:url.searchParams.toString()});
    if(m!=='GET'){assert.equal(req.headers()['x-csrf-token'],`token-${s.token}`);if(s.failure){const f=s.failure;s.failure=null;return f==='network'?route.abort('failed'):json({},f,{...(f===429?{'Retry-After':'3'}:{}),...(s.failureTrace?{'X-Trace-Id':s.failureTrace}:{})});}}
    if(p==='/api/auth/csrf'){const value={headerName:'X-CSRF-TOKEN',token:`token-${s.token}`};if(s.holdCsrf)return new Promise(resolve=>{s.releaseCsrf=()=>resolve(json(value));});return json(value);}
    if(p==='/api/auth/me')return s.user?json(s.user):json({},401);
    if(p==='/api/auth/register')return json({id:1,username:d.username,role:'USER'},201);
    if(p==='/api/auth/login'){assert.ok(req.headers()['content-type'].startsWith('application/x-www-form-urlencoded'));s.user={id:1,username:d.username,role:'USER'};s.token++;return done();}
    if(p==='/api/auth/logout'){s.user=null;s.token++;return done();}
    if(p.startsWith('/api/admin/')&&s.user?.role!=='ADMIN')return json({},403);
    if(p==='/api/categories')return json(s.categories);
    if(p==='/api/products'||p==='/api/admin/products'&&m==='GET')return json(s.products.filter(i=>p.includes('admin')||i.status==='ON_SALE'));
    if(p==='/api/products/search'){const items=s.products.filter(i=>i.status==='ON_SALE'&&(!url.searchParams.get('keyword')||i.name.includes(url.searchParams.get('keyword'))));return json({page:1,size:12,total:items.length,items});}
    if(p==='/api/activities')return json(s.activities);
    if(/^\/api\/activities\/\d+\/coupons$/.test(p))return json(s.coupons);
    if(p==='/api/coupons/mine')return json(s.owned);
    if(p.endsWith('/claim')){const c={id:41,status:'AVAILABLE',claimedAt:past,orderId:null,coupon:{...coupon},expired:false};s.owned=[c];return json(c);}
    if(p==='/api/addresses'&&m==='GET'){if(s.expireAddress){s.user=null;return json({},401);}return json(s.addresses);}
    if(p==='/api/addresses'){const a={...d,id:12,defaultAddress:false};s.addresses.push(a);return json(a,201);}
    if(/^\/api\/addresses\/\d+/.test(p)){const id=Number(p.split('/')[3]);if(p.endsWith('/default')){s.addresses.forEach(a=>a.defaultAddress=a.id===id);return done();}if(m==='DELETE'){s.addresses=s.addresses.filter(a=>a.id!==id);return done();}Object.assign(s.addresses.find(a=>a.id===id),d);return json(s.addresses.find(a=>a.id===id));}
    if(p==='/api/cart/items')return json(s.cart);
    if(p.startsWith('/api/cart/items/')){const id=Number(p.split('/')[4]);s.cart=s.cart.filter(i=>i.productId!==id);if(m==='PUT')s.cart.push({...s.products.find(i=>i.id===id),productId:id,quantity:d.quantity});return done();}
    if(p==='/api/orders'&&m==='GET'){
      const page=Number(url.searchParams.get('page')||1),size=Number(url.searchParams.get('size')||20),status=url.searchParams.get('status');
      const items=s.orders.filter(o=>!status||o.status===status);
      return json({page,size,total:items.length,items:items.slice((page-1)*size,page*size)});
    }
    if((p==='/api/orders'||p==='/api/orders/checkout')&&m==='POST'){
      assert.ok(d.requestId&&d.addressId);assert.ok(!('userId'in d));
      const old=s.orders.find(o=>o.requestId===d.requestId);if(old)return json(old);
      const items=d.productIds?s.cart.filter(i=>d.productIds.includes(i.productId)).map(i=>({productId:i.productId,productName:i.name,quantity:i.quantity,lineAmount:i.price*i.quantity,unitPrice:i.price,activityId:null})):[{productId:d.productId,productName:'机械键盘',quantity:d.quantity,lineAmount:129*d.quantity,unitPrice:129,activityId:d.activityId}];
      const originalAmount=items.reduce((sum,i)=>sum+i.lineAmount,0),discountAmount=d.userCouponId?20:0;
      const o={id:100+s.orders.length,userId:1,status:'PENDING_PAYMENT',requestId:d.requestId,items,originalAmount,discountAmount,totalAmount:originalAmount-discountAmount,address:{...s.addresses.find(a=>a.id===d.addressId)},coupon:d.userCouponId?{...coupon,userCouponId:d.userCouponId}:null,shipment:null,createdAt:past,expiresAt:future,paidAt:null,endedAt:null};s.orders.unshift(o);
      if(d.productIds)s.cart=s.cart.filter(i=>!d.productIds.includes(i.productId));return json(o,201);
    }
    if(p==='/api/admin/orders')return json({page:1,size:20,total:s.orders.length,items:s.orders});
    if(/^\/api\/(admin\/)?orders\/\d+$/.test(p)){const o=s.orders.find(o=>o.id===Number(p.split('/').at(-1)));if(s.holdOrder)return new Promise(resolve=>{s.releaseOrder=()=>resolve(json(o));});return json(o);}
    if(/^\/api\/(admin\/)?orders\/\d+\/(pay|cancel|ship|receive)$/.test(p)){const a=p.split('/').at(-1),o=s.orders.find(o=>o.id===Number(p.split('/').at(-2)));o.status={pay:'PAID',cancel:'CANCELED',ship:'SHIPPED',receive:'COMPLETED'}[a];if(a==='ship')o.shipment={...d,shippedAt:past,receivedAt:null};return json(o);}
    if(p==='/api/admin/products'){const item={...d,id:3,status:'OFF_SALE'};s.products.unshift(item);return json(item,201);}
    if(p.startsWith('/api/admin/products/')){const item=s.products.find(i=>i.id===Number(p.split('/')[4]));if(m==='PUT')assert.ok(!('stock'in d));Object.assign(item,d);return json(item);}
    if(p==='/api/admin/categories'){s.categories.push({...d,id:2});return json(s.categories.at(-1),201);}
    if(p.startsWith('/api/admin/categories/')){Object.assign(s.categories.find(c=>c.id===Number(p.split('/').at(-1))),d);return json(d);}
    if(p==='/api/admin/activities'){const a={...d,id:22,remainingQuantity:d.totalQuantity};s.activities.push(a);return json(a,201);}
    if(p==='/api/admin/coupons'){const c={...d,id:32,remainingQuantity:d.totalQuantity};s.coupons.push(c);return json(c,201);}
    throw Error(`接口契约未覆盖 ${m} ${p}`);
  });
  await page.goto('http://shop.test/');await page.getByRole('heading',{name:'发现值得入手的好物'}).waitFor();return{page,context,s};
}
async function open(page,route,title){await page.locator(`nav a[href="#${route}"]`).click();if(title)await page.getByRole('heading',{name:title,exact:true}).waitFor();}
async function submit(page,label){await page.locator('dialog').getByRole('button',{name:label,exact:true}).last().click();}
async function fill(page,values){for(const[k,v]of Object.entries(values))await page.getByLabel(k,{exact:true}).fill(String(v));}
async function close(page){await page.locator('dialog').getByRole('button',{name:'关闭',exact:true}).click();}
(async () => {
  const browser = await chromium.launch({ headless: true });
  const only=process.env.WEB_UI_CASE;
  try {
    if(!only||only==='pagination'){
      const{page,context,s}=await fixture(browser,'USER');
      s.orders=Array.from({length:23},(_,i)=>({id:200-i,userId:1,status:i%2?'PAID':'PENDING_PAYMENT',requestId:`page-${i}`,items:[{productName:`分页商品${i}`,quantity:1,lineAmount:20}],originalAmount:20,discountAmount:0,totalAmount:20,createdAt:past,expiresAt:future,address:null,shipment:null,coupon:null}));
      await open(page,'orders','我的订单');await page.getByText('第 1 页 · 共 23 条',{exact:true}).waitFor();
      assert.equal(await page.locator('main article').count(),20);assert.equal(await page.getByRole('button',{name:'上一页',exact:true}).isDisabled(),true);
      await page.getByRole('button',{name:'下一页',exact:true}).click();await page.getByText('第 2 页 · 共 23 条',{exact:true}).waitFor();
      assert.equal(await page.locator('main article').count(),3);assert.equal(await page.getByRole('button',{name:'下一页',exact:true}).isDisabled(),true);
      await page.getByRole('button',{name:'查看订单',exact:true}).first().click();await submit(page,'取消订单');await close(page);
      await page.getByText('第 2 页 · 共 23 条',{exact:true}).waitFor();await page.getByRole('button',{name:'刷新订单',exact:true}).click();
      await page.getByText('第 2 页 · 共 23 条',{exact:true}).waitFor();
      await page.getByLabel('订单状态',{exact:true}).selectOption('PAID');await page.getByRole('button',{name:'筛选订单',exact:true}).click();
      await page.getByText('第 1 页 · 共 11 条',{exact:true}).waitFor();assert.equal(await page.locator('main article').count(),11);
      assert.ok(s.requests.some(r=>r.p==='/api/orders'&&new URLSearchParams(r.query).get('page')==='2'));
      assert.equal(new URLSearchParams(s.requests.filter(r=>r.p==='/api/orders'&&r.m==='GET').at(-1).query).get('status'),'PAID');
      await page.getByLabel('订单状态',{exact:true}).selectOption('SHIPPED');await page.getByRole('button',{name:'筛选订单',exact:true}).click();
      await page.getByText('没有符合条件的订单。',{exact:true}).waitFor();await page.getByText('第 1 页 · 共 0 条',{exact:true}).waitFor();
      await page.getByLabel('订单状态',{exact:true}).selectOption('');await page.getByRole('button',{name:'筛选订单',exact:true}).click();await page.getByText('第 1 页 · 共 23 条',{exact:true}).waitFor();
      assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 用户订单分页、筛选、空结果和操作刷新');
    }
    if(!only||only==='trace'){
      const{page,context,s}=await fixture(browser,'USER');
      await page.getByRole('button',{name:'立即购买',exact:true}).click();
      s.failure=503;s.failureTrace='0123456789abcdef0123456789abcdef';
      await submit(page,'提交订单');
      await page.locator('dialog').getByText('服务暂不可用，请稍后重试。追踪编号：0123456789abcdef0123456789abcdef',{exact:true}).waitFor();
      const legacy=await page.evaluate(async()=>{const{errorMessage,ApiError}=await import('/assets/api.js');return errorMessage(new ApiError(503,null,'/api/orders'));});
      assert.equal(legacy,'服务暂不可用，请稍后重试。');
      assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 接口失败展示追踪编号及无编号兼容');
    }
    if(!only||only==='notice'){
      const{page,context,s}=await fixture(browser);
      await page.clock.install();
      await page.getByRole('button',{name:'登录 / 注册'}).click();
      await fill(page,{用户名:'learner',密码:'Learning2026!'});await submit(page,'登录');
      await page.locator('#notice').getByText('登录成功。',{exact:true}).waitFor();
      await page.clock.fastForward(3000);
      assert.equal(await page.locator('#notice').isVisible(),false,'登录成功提示应在3秒后消失');
      await page.evaluate(async()=>{const{notify}=await import('/assets/ui.js');notify('第一条成功提示');});
      await page.clock.fastForward(2000);
      await page.evaluate(async()=>{const{notify}=await import('/assets/ui.js');notify('第二条成功提示');});
      await page.clock.fastForward(1000);
      assert.equal(await page.locator('#notice').isVisible(),true,'旧计时器不能关闭新提示');
      await page.clock.fastForward(2000);
      assert.equal(await page.locator('#notice').isVisible(),false);
      await page.evaluate(async()=>{const{notify}=await import('/assets/ui.js');notify('成功提示');});
      await page.clock.fastForward(2000);
      await page.evaluate(async()=>{const{notify}=await import('/assets/ui.js');notify('需要处理的错误',true);});
      await page.clock.fastForward(5000);
      assert.equal(await page.locator('#notice').isVisible(),true,'错误提示保留，不能被旧成功提示计时器关闭');
      assert.equal(await page.locator('#notice').getAttribute('role'),'alert');
      assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 登录提示自动关闭及新提示计时器隔离');
    }
    if(!only||only==='user'){
      const{page,context,s}=await fixture(browser);
      await page.getByRole('button',{name:'登录 / 注册'}).click();await submit(page,'注册');await fill(page,{用户名:'learner',密码:'Learning2026!'});await submit(page,'注册账号');await page.getByRole('heading',{name:'欢迎回来'}).waitFor();assert.equal(s.user,null);
      await fill(page,{密码:'Learning2026!'});await submit(page,'登录');await page.locator('#account strong').waitFor();
      await open(page,'addresses','收货地址');await page.getByRole('button',{name:'新增地址',exact:true}).click();await fill(page,{收货人:'李四',手机号码:'13900139000',省:'江苏',市:'南京','区 / 县':'鼓楼',详细地址:'测试路2号'});await submit(page,'保存地址');
      const card=page.locator('article').filter({has:page.getByRole('heading',{name:'李四',exact:true})});await card.getByRole('button',{name:'编辑',exact:true}).click();await fill(page,{详细地址:'修改后地址'});await submit(page,'保存地址');await page.getByText('江苏南京鼓楼修改后地址',{exact:true}).waitFor();
      await card.getByRole('button',{name:'设为默认'}).click();await card.getByText('默认地址',{exact:true}).waitFor();
      await open(page,'products','发现值得入手的好物');await page.getByRole('button',{name:'加入购物车'}).click();await page.getByText(/已加入购物车/).waitFor();
      await open(page,'cart','我的购物车');await page.getByLabel('机械键盘数量').fill('2');await page.getByRole('button',{name:'保存数量'}).click();await page.getByLabel('选择机械键盘').check();await page.getByRole('button',{name:'结算选中商品'}).click();await page.getByRole('heading',{name:'订单 #100',exact:true}).waitFor();assert.deepEqual(s.requests.find(r=>r.p==='/api/orders/checkout').d.productIds,[1]);
      await submit(page,'模拟支付');await page.locator('dialog').getByText('已支付',{exact:true}).waitFor();await close(page);
      await open(page,'activities','限时限量活动');await page.getByRole('button',{name:'查看活动与领券'}).click();await submit(page,'领取优惠券');await page.getByText(/已领取 满200减20/).waitFor();await submit(page,'参加活动下单');await fill(page,{购买数量:2});await page.getByLabel('活动优惠券',{exact:true}).selectOption('41');await submit(page,'提交订单');await page.getByRole('heading',{name:'订单 #101',exact:true}).waitFor();
      const data=s.requests.filter(r=>r.p==='/api/orders'&&r.m==='POST').at(-1).d;assert.equal(data.activityId,21);assert.equal(data.userCouponId,41);assert.equal(data.addressId,12);
      await submit(page,'取消订单');await page.locator('dialog').getByText('已取消',{exact:true}).waitFor();await close(page);
      s.orders[0].status='SHIPPED';s.orders[0].shipment={carrier:'测试物流',trackingNo:'TRACK1',shippedAt:past,receivedAt:null};await open(page,'orders','我的订单');await page.getByRole('button',{name:'查看订单'}).first().click();await submit(page,'确认收货');await page.locator('dialog').getByText('已完成',{exact:true}).waitFor();await close(page);
      await open(page,'coupons','我的优惠券');await page.getByRole('heading',{name:'满200减20',exact:true}).waitFor();await open(page,'addresses','收货地址');await card.getByRole('button',{name:'删除',exact:true}).click();await submit(page,'确认删除');await page.getByText('地址已删除，历史订单快照仍保留。',{exact:true}).waitFor();
      await page.getByRole('button',{name:'退出',exact:true}).click();await page.getByRole('button',{name:'登录 / 注册',exact:true}).first().waitFor();assert.equal(s.user,null);assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 用户完整页面链路');
    }
    if(!only||only==='admin'){
      const{page,context,s}=await fixture(browser,'ADMIN');await open(page,'admin','管理中心');await page.getByRole('button',{name:'分类管理',exact:true}).click();await page.getByRole('button',{name:'创建分类',exact:true}).click();await fill(page,{分类名称:'办公'});await submit(page,'保存分类');
      const category=page.locator('article').filter({hasText:'#2 办公'});await category.getByRole('button',{name:'重命名'}).click();await fill(page,{分类名称:'办公用品'});await submit(page,'保存分类');
      await page.getByRole('button',{name:'商品管理',exact:true}).click();await page.getByRole('button',{name:'创建商品',exact:true}).click();await fill(page,{商品名称:'办公鼠标',销售单价:'59.90',初始库存:10});await page.getByLabel('分类',{exact:true}).selectOption('2');await submit(page,'保存商品');
      const product=page.locator('article').filter({hasText:'#3 办公鼠标'});await product.getByRole('button',{name:'上架',exact:true}).click();await product.getByText('在售',{exact:true}).waitFor();await product.getByRole('button',{name:'编辑商品'}).click();await fill(page,{销售单价:'49.90'});await submit(page,'保存商品');await page.getByText('商品信息已更新。',{exact:true}).waitFor();assert.equal(s.products.find(p=>p.id===3).price,'49.90');
      await page.getByRole('button',{name:'活动管理',exact:true}).click();await page.getByRole('button',{name:'创建活动',exact:true}).click();await page.getByLabel('活动商品',{exact:true}).selectOption('1');await fill(page,{活动名称:'新活动',销售名额:2,活动开始:'2026-10-20T10:00',活动截止:'2026-10-20T20:00'});await submit(page,'创建活动');await page.getByText('#22 新活动',{exact:true}).waitFor();
      await page.getByRole('button',{name:'优惠券规则',exact:true}).click();await page.getByRole('button',{name:'创建优惠券',exact:true}).click();await page.getByLabel('绑定活动',{exact:true}).selectOption('22');await fill(page,{优惠券名称:'新券',满减门槛:100,优惠金额:10,发放总量:2,领取开始:'2026-10-19T10:00',领取截止:'2026-10-20T12:00',使用开始:'2026-10-20T10:00',使用截止:'2026-10-20T18:00'});await submit(page,'创建优惠券');await page.locator('article').filter({hasText:'#22 新活动'}).getByRole('button',{name:'查看券规则'}).click();await page.getByRole('heading',{name:'新券',exact:true}).waitFor();await close(page);
      s.orders=[{id:888,userId:1,status:'PAID',requestId:'test',items:[{productName:'机械键盘',quantity:2,lineAmount:258}],originalAmount:258,discountAmount:20,totalAmount:238,address:{...address},createdAt:past,expiresAt:future,paidAt:past,shipment:null,coupon:null}];
      await page.getByRole('button',{name:'订单履约',exact:true}).click();await page.getByLabel('订单状态',{exact:true}).selectOption('PAID');await page.getByRole('button',{name:'筛选订单'}).click();await page.getByRole('button',{name:'查看详情 / 发货'}).click();await submit(page,'登记发货');await fill(page,{承运方:'测试物流',运单号:'SHIP888'});await submit(page,'确认发货');await page.locator('dialog').getByText('已发货',{exact:true}).waitFor();assert.equal(s.orders[0].shipment.trackingNo,'SHIP888');assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 管理维护与履约');
    }
    if(!only||only==='retry'){
      const{page,context,s}=await fixture(browser,'USER');await page.getByRole('button',{name:'立即购买',exact:true}).click();s.failure='network';await submit(page,'提交订单');await page.locator('dialog').getByText(/无法连接服务器/).waitFor();const key=s.requests.filter(r=>r.p==='/api/orders'&&r.m==='POST').at(-1).d.requestId;
      s.failure=429;await submit(page,'提交订单');await page.locator('dialog').getByText(/等待 3 秒/).waitFor();s.failure=503;await submit(page,'提交订单');await page.locator('dialog').getByText(/服务暂不可用/).waitFor();await submit(page,'提交订单');await page.getByRole('heading',{name:'订单 #100',exact:true}).waitFor();assert.ok(s.requests.filter(r=>r.p==='/api/orders'&&r.m==='POST').every(r=>r.d.requestId===key));await close(page);
      await open(page,'products','发现值得入手的好物');await page.getByRole('button',{name:'立即购买',exact:true}).click();s.failure=429;await submit(page,'提交订单');await page.locator('dialog').getByText(/等待 3 秒/).waitFor();assert.equal(s.requests.filter(r=>r.p==='/api/orders'&&r.m==='POST').length,5);await close(page);assert.equal(s.orders.length,1);assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 未知结果重试幂等及429提示');
    }
    if(!only||only==='layout'){
      const{page,context,s}=await fixture(browser,'USER');s.products[1].status='ON_SALE';s.products[1].name='<img src=x onerror=alert(1)>';await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('heading',{name:'<img src=x onerror=alert(1)>',exact:true}).waitFor();assert.equal(await page.locator('main img').count(),0);
      await page.setViewportSize({width:390,height:844});await page.screenshot({path:'tools/web-ui-mobile.png',fullPage:true});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
      s.products=[];await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByText('没有找到符合条件的在售商品。',{exact:true}).waitFor();await page.setViewportSize({width:1440,height:1000});s.products=[{id:1,name:'机械键盘',price:129,stock:9,status:'ON_SALE',categoryId:1,categoryName:'数码'}];await page.getByRole('button',{name:'刷新',exact:true}).click();await page.getByRole('heading',{name:'机械键盘',exact:true}).waitFor();await page.screenshot({path:'tools/web-ui-desktop.png',fullPage:true});assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 动态文字安全、空状态及移动布局');
    }
    if(!only||only==='privacy'){
      const{page,context,s}=await fixture(browser,'USER');
      s.orders=[{id:444,userId:1,status:'PAID',requestId:'privacy',items:[],originalAmount:20,discountAmount:0,totalAmount:20,address:{...address},createdAt:past,expiresAt:future,paidAt:past,shipment:null,coupon:null}];
      await open(page,'orders','我的订单');s.holdOrder=true;
      await page.getByRole('button',{name:'查看订单',exact:true}).click();
      while(!s.releaseOrder)await new Promise(resolve=>setTimeout(resolve,5));
      s.expireAddress=true;await open(page,'addresses');await page.getByText('登录已过期，请重新登录。',{exact:true}).waitFor();
      const response=page.waitForResponse('http://shop.test/api/orders/444');s.releaseOrder();await response;await page.waitForLoadState('networkidle');
      assert.equal(await page.locator('#modal').evaluate(n=>n.open),false);assert.equal(await page.locator('dialog').getByText('张三 · 13800138000',{exact:false}).isVisible(),false);
      s.user={id:1,username:'learner',role:'USER'};s.expireAddress=false;s.holdOrder=false;
      await page.goto('http://shop.test/');await page.getByRole('heading',{name:'发现值得入手的好物'}).waitFor();
      await page.getByRole('button',{name:'立即购买',exact:true}).click();s.holdCsrf=true;await submit(page,'提交订单');
      while(!s.releaseCsrf)await new Promise(resolve=>setTimeout(resolve,5));
      await page.keyboard.press('Escape');s.expireAddress=true;await open(page,'addresses');await page.getByText('登录已过期，请重新登录。',{exact:true}).waitFor();
      const writes=s.requests.filter(r=>r.p==='/api/orders'&&r.m==='POST').length;
      s.releaseCsrf();await page.waitForLoadState('networkidle');
      assert.equal(s.requests.filter(r=>r.p==='/api/orders'&&r.m==='POST').length,writes);
      assert.deepEqual(s.errors,[]);await context.close();console.log('PASS 会话失效后的延迟详情与写入隔离');
    }
    console.log(only?`关联场景 ${only} 通过；未运行全量后端回归。`:'7个前端主线场景通过；未运行全量后端回归。');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
