package com.elaina.limitedtrade.coupon;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class ActivityCoupon {
    private Long id;
    private Long activityId;
    private String name;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private Integer totalQuantity;
    private Integer remainingQuantity;
    private LocalDateTime claimStartsAt;
    private LocalDateTime claimEndsAt;
    private LocalDateTime useStartsAt;
    private LocalDateTime useEndsAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getThresholdAmount() { return thresholdAmount; }
    public void setThresholdAmount(BigDecimal thresholdAmount) { this.thresholdAmount = thresholdAmount; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public Integer getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(Integer totalQuantity) { this.totalQuantity = totalQuantity; }

    public Integer getRemainingQuantity() { return remainingQuantity; }
    public void setRemainingQuantity(Integer remainingQuantity) { this.remainingQuantity = remainingQuantity; }

    public LocalDateTime getClaimStartsAt() { return claimStartsAt; }
    public void setClaimStartsAt(LocalDateTime claimStartsAt) { this.claimStartsAt = claimStartsAt; }

    public LocalDateTime getClaimEndsAt() { return claimEndsAt; }
    public void setClaimEndsAt(LocalDateTime claimEndsAt) { this.claimEndsAt = claimEndsAt; }

    public LocalDateTime getUseStartsAt() { return useStartsAt; }
    public void setUseStartsAt(LocalDateTime useStartsAt) { this.useStartsAt = useStartsAt; }

    public LocalDateTime getUseEndsAt() { return useEndsAt; }
    public void setUseEndsAt(LocalDateTime useEndsAt) { this.useEndsAt = useEndsAt; }
}
