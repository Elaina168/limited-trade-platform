package com.elaina.limitedtrade.observability;

public record OrderMessageDatabaseStats(long outboxPending, long outboxRetrying, long storedFailedAttempts,
        long oldestPendingSeconds, long overdueOrders, long oldestOverdueSeconds,
        long closedLastHour, long closedLastHourMaxDelaySeconds) {
}
