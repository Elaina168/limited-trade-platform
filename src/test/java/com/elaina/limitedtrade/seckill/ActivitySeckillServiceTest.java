package com.elaina.limitedtrade.seckill;

import com.elaina.limitedtrade.activity.Activity;
import com.elaina.limitedtrade.activity.ActivityMapper;
import com.elaina.limitedtrade.order.OrderService;
import com.elaina.limitedtrade.order.TradeOrder;
import com.elaina.limitedtrade.ratelimit.RedisOrderRateLimiter;
import com.elaina.limitedtrade.user.AppUserMapper;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
                props, redisStore, seckillMapper, activityMapper, Mockito.mock(AppUserMapper.class), orderService,
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

        var message = new ActivitySeckillMessage(7L, 3L, 10L, 1, 5L, 88L, "req-bad", LocalDateTime.now());
        ActivityPurchaseReservation saved = ActivitySeckillService.toReservation(message, "QUEUED", null);
        saved.setId(99L);
        when(seckillMapper.findByUserAndRequest(7L, "req-bad")).thenReturn(saved);
        when(orderService.materializeSeckillOrder(any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "优惠券已失效"));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ActivitySeckillService service = new ActivitySeckillService(
                props, redisStore, seckillMapper, activityMapper, Mockito.mock(AppUserMapper.class), orderService,
                rateLimiter, rabbitTemplate, mapper, txManager, "test");

        service.consumeMessage(message);
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
                props, redisStore, seckillMapper, activityMapper, Mockito.mock(AppUserMapper.class), orderService,
                rateLimiter, rabbitTemplate, mapper, txManager, "test");

        SeckillRequest request = new SeckillRequest(10L, 1, "req-limit", null, 5L);
        when(redisStore.reserve(anyLong(), anyLong(), eq(request), any(), any()))
                .thenReturn(new ActivitySeckillRedisStore.ReserveResult("LIMIT_EXCEEDED", "已达到本活动每人限购上限"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.submit(7L, 3L, request));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void persistedFingerprintWinsAfterRedisStateExpires() {
        var props = new SeckillProperties(true, "test:seckill", 3600, 1000, 5000);
        var redisStore = Mockito.mock(ActivitySeckillRedisStore.class);
        var reservations = Mockito.mock(ActivitySeckillMapper.class);
        var orders = Mockito.mock(OrderService.class);
        var rabbit = Mockito.mock(RabbitTemplate.class);
        var service = new ActivitySeckillService(props, redisStore, reservations, Mockito.mock(ActivityMapper.class),
                Mockito.mock(AppUserMapper.class), orders, Mockito.mock(RedisOrderRateLimiter.class), rabbit,
                new ObjectMapper(), Mockito.mock(PlatformTransactionManager.class), "test");
        var request = new SeckillRequest(10L, 1, "durable-key", 88L, 5L);
        when(reservations.hasSubmittedRequest(7L, "durable-key")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.submit(7L, 3L, request)).getStatusCode());

        var message = new ActivitySeckillMessage(7L, 3L, 10L, 1, 5L, 88L, "durable-key", LocalDateTime.now());
        var saved = ActivitySeckillService.toReservation(message, "CREATED", null);
        saved.setOrderId(100L);
        var order = new TradeOrder();
        order.setId(100L);
        order.setItems(List.of());
        when(reservations.findByUserAndRequest(7L, "durable-key")).thenReturn(saved);
        when(orders.getSeckillOrder(7L, saved)).thenReturn(order);

        var replay = service.submit(7L, 3L, request);
        assertEquals(HttpStatus.OK, replay.httpStatus());
        assertEquals(100L, replay.body().orderId());
        assertEquals(100L, service.getResult(7L, "durable-key").orderId());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.submit(7L, 4L, request)).getStatusCode());
        for (var changed : List.of(new SeckillRequest(11L, 1, "durable-key", 88L, 5L),
                new SeckillRequest(10L, 2, "durable-key", 88L, 5L),
                new SeckillRequest(10L, 1, "durable-key", 89L, 5L),
                new SeckillRequest(10L, 1, "durable-key", 88L, 6L))) {
            assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                    () -> service.submit(7L, 3L, changed)).getStatusCode());
        }
        verifyNoInteractions(redisStore, rabbit);
    }
}
