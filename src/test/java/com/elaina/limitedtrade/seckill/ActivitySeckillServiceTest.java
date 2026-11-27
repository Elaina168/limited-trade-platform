package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.order.OrderService;
import com.elaina.limitedtrade.order.TradeOrder;
import com.elaina.limitedtrade.ratelimit.RedisOrderRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivitySeckillServiceTest {

    @Test
    void reservesInRedisAndFallsBackToDbReservationWhenRabbitUnavailable() {
        SeckillProperties props = new SeckillProperties(true, "test:seckill", 3600, 1000, 5000);
        ActivitySeckillRedisStore redisStore = Mockito.mock(ActivitySeckillRedisStore.class);
        ActivitySeckillMapper seckillMapper = Mockito.mock(ActivitySeckillMapper.class);
        ActivityMapper activityMapper = Mockito.mock(ActivityMapper.class);
        OrderService orderService = Mockito.mock(OrderService.class);
        RedisOrderRateLimiter rateLimiter = Mockito.mock(RedisOrderRateLimiter.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ActivitySeckillService service = new ActivitySeckillService(
                props, redisStore, seckillMapper, activityMapper, orderService,
                rateLimiter, rabbitTemplate, mapper, txManager, "test");

        SeckillRequest request = new SeckillRequest(10L, 1, "req-100", null, 5L);
        when(redisStore.reserve(eq(7L), eq(3L), eq(request), any(), any()))
                .thenReturn(new ActivitySeckillRedisStore.ReserveResult("QUEUED", Long.toString(System.currentTimeMillis())));
        doThrow(new AmqpConnectException(new RuntimeException("broker down")))
                .when(rabbitTemplate).convertAndSend(any(), any(), any(ActivitySeckillMessage.class));

        ActivitySeckillService.SubmitOutcome outcome = service.submit(7L, 3L, request);
        assertEquals(HttpStatus.ACCEPTED, outcome.httpStatus());
        assertEquals("QUEUED", outcome.body().status());
        verify(seckillMapper).insertIgnore(any(ActivityPurchaseReservation.class));
    }

    @Test
    void businessFailureInConsumerMarksFailedAndReleasesRedisQuota() {
        SeckillProperties props = new SeckillProperties(true, "test:seckill", 3600, 1000, 5000);
        ActivitySeckillRedisStore redisStore = Mockito.mock(ActivitySeckillRedisStore.class);
        ActivitySeckillMapper seckillMapper = Mockito.mock(ActivitySeckillMapper.class);
        ActivityMapper activityMapper = Mockito.mock(ActivityMapper.class);
        OrderService orderService = Mockito.mock(OrderService.class);
        RedisOrderRateLimiter rateLimiter = Mockito.mock(RedisOrderRateLimiter.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        Activity activity = new Activity();
        activity.setId(3L);
        activity.setTotalQuantity(50);
        when(activityMapper.findById(3L)).thenReturn(activity);

        ActivityPurchaseReservation saved = new ActivityPurchaseReservation();
        saved.setId(99L);
        saved.setStatus("QUEUED");
        when(seckillMapper.findByUserAndRequest(7L, "req-bad")).thenReturn(saved);
        when(orderService.materializeSeckillOrder(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已失效"));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ActivitySeckillService service = new ActivitySeckillService(
                props, redisStore, seckillMapper, activityMapper, orderService,
                rateLimiter, rabbitTemplate, mapper, txManager, "test");

        service.consumeMessage(new ActivitySeckillMessage(7L, 3L, 10L, 1, 5L, 88L, "req-bad", LocalDateTime.now()));
        verify(seckillMapper).markFailed(99L, "优惠券已失效");
        verify(redisStore).release(7L, 3L, "req-bad", 1, 50, "FAILED:优惠券已失效");
    }

    @Test
    void perUserLimitExceededThrowsConflict() {
        SeckillProperties props = new SeckillProperties(true, "test:seckill", 3600, 1000, 5000);
        ActivitySeckillRedisStore redisStore = Mockito.mock(ActivitySeckillRedisStore.class);
        ActivitySeckillMapper seckillMapper = Mockito.mock(ActivitySeckillMapper.class);
        ActivityMapper activityMapper = Mockito.mock(ActivityMapper.class);
        OrderService orderService = Mockito.mock(OrderService.class);
        RedisOrderRateLimiter rateLimiter = Mockito.mock(RedisOrderRateLimiter.class);
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        PlatformTransactionManager txManager = Mockito.mock(PlatformTransactionManager.class);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ActivitySeckillService service = new ActivitySeckillService(
                props, redisStore, seckillMapper, activityMapper, orderService,
                rateLimiter, rabbitTemplate, mapper, txManager, "test");

        SeckillRequest request = new SeckillRequest(10L, 1, "req-limit", null, 5L);
        when(redisStore.reserve(anyLong(), anyLong(), eq(request), any(), any()))
                .thenReturn(new ActivitySeckillRedisStore.ReserveResult("LIMIT_EXCEEDED", "已达到本活动每人限购上限"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.submit(7L, 3L, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
