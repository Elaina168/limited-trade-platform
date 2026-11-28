package com.elaina.cloud.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderOutbox outbox = mock(OrderOutbox.class);
    private final ProductClient products = mock(ProductClient.class);
    private final MarketingClient marketing = mock(MarketingClient.class);
    private final OrderService service = new OrderService(orders, outbox, products, marketing, 4000);

    @Test void createsLocalRowAndRecoveryWorkBeforeAcquiringStockAndCoupon() {
        var request = arrangePurchase();
        when(marketing.reserve(17, new MarketingClient.CouponRequest(3, 8)))
            .thenReturn(new MarketingClient.Reservation(new BigDecimal("20.00"), new BigDecimal("6.00")));
        var created = service.create(1, request);
        assertTrue(created.created());
        assertEquals(new BigDecimal("44.00"), created.order().totalAmount());
        var sequence = inOrder(orders, outbox, products, marketing);
        sequence.verify(orders).insert(eq(1L), eq(request), any());
        sequence.verify(outbox).watchRollback(eq(17L), any());
        sequence.verify(products).lock(17, new ProductClient.StockRequest(3, 2));
        sequence.verify(marketing).reserve(17, new MarketingClient.CouponRequest(3, 8));
        sequence.verify(orders).finish(eq(17L), eq(new BigDecimal("44.00")), any());
        sequence.verify(outbox).enqueue(eq(17L), eq(OrderOutbox.Event.TIMEOUT), any(), any());
    }

    @Test void couponFailureEscapesTransactionWithRollbackWorkAndNoTimeoutWork() {
        var request = arrangePurchase();
        when(marketing.reserve(anyLong(), any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "券不可用"));
        var failure = assertThrows(ResponseStatusException.class, () -> service.create(1, request));
        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        verify(outbox).watchRollback(eq(17L), any());
        verify(orders, never()).finish(anyLong(), any(), any());
        verify(outbox, never()).enqueue(anyLong(), any(), any(), any());
    }

    @Test void repeatedCancelQueuesReleaseOnlyForFirstCommittedTransition() {
        var canceled = new OrderRepository.Order(17, 1, "req-17", 3, 2, 8L, BigDecimal.TEN, "CANCELED", Instant.now());
        when(orders.cancel(17, 1)).thenReturn(true, false);
        when(orders.owned(1, 17)).thenReturn(Optional.of(canceled));
        assertEquals(canceled, service.cancel(1, 17));
        assertEquals(canceled, service.cancel(1, 17));
        verify(outbox).enqueue(eq(17L), eq(OrderOutbox.Event.RELEASE), any(), any());
    }

    private OrderService.Request arrangePurchase() {
        var request = new OrderService.Request("req-17", 3, 2, 8L);
        when(orders.request(1, "req-17", false)).thenReturn(Optional.empty());
        when(orders.insert(eq(1L), eq(request), any())).thenReturn(17L);
        when(products.product(3)).thenReturn(new ProductClient.Product(3, "商品", new BigDecimal("25.00"), 10, "stock-a"));
        return request;
    }
}
