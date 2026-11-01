# 订单查询、身份与隐私

## 本人订单范围
普通用户必须登录后查询自己的订单。用户身份来自服务端Session认证，不能通过问题中声称管理员或指定其他userId来获得权限。订单详情查询同时限定订单ID和登录用户ID；不存在和属于他人的订单都返回404，避免暴露其他用户订单是否存在。

## 分页、状态和排序
本人订单查询支持分页和可选状态筛选，并按订单ID倒序返回。总数total表示本人且符合状态条件的总记录数，不是当前页条数。AI订单列表工具每页最多10条，页码从1开始；状态支持PENDING_PAYMENT、PAID、CANCELED、CLOSED、SHIPPED和COMPLETED。

## 实时数据与工具摘要
具体订单金额、购买商品、状态和支付截止时间必须通过本次本人订单工具读取，不能从规则文档、历史问答或模型猜测中获取。AI只接收咨询必要的订单摘要，不传收货地址、手机号、运单和请求幂等键requestId。规则知识库只包含公开说明，不存用户私有订单。

## 规则依据
依据：src/main/java/com/elaina/limitedtrade/order/OrderService.java的listUserOrders与getUserOrder；src/main/resources/mapper/OrderMapper.xml的findOwnedOrder和userOrderFilters；agent/AgentOrderSummary与TradeAgentTools。
