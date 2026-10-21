package com.elaina.limitedtrade.order;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record OrderPageResponse(
        @Schema(description = "请求页码，从1开始") int page,
        @Schema(description = "请求的每页条数") int size,
        @Schema(description = "本人且符合筛选条件的订单总数，不是当前页条数") long total,
        @Schema(description = "当前页订单，超出末页时为空列表") List<OrderResponse> items) {
}
