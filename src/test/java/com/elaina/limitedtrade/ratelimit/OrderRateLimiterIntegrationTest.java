package com.elaina.limitedtrade.ratelimit;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class OrderRateLimiterIntegrationTest {
    @Autowired StringRedisTemplate redis;
    private final String prefix="limited-trade:test:order-rate-limit:lua:"+UUID.randomUUID();
    private final long userId=8291;
    private String key(){return prefix+":user:"+userId;}
    @AfterEach void cleanup(){redis.delete(key());}

    @Test void twoInstancesAtomicallyShareOneBudgetUnderConcurrency()throws Exception {
        var properties=new OrderRateLimitProperties(true,3,30,prefix);
        var first=new RedisOrderRateLimiter(redis,properties);var second=new RedisOrderRateLimiter(redis,properties);
        var pool=Executors.newFixedThreadPool(10);var start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(int i=0;i<100;i++){
                var limiter=i%2==0?first:second;
                futures.add(pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));try{limiter.check(userId);return true;}catch(OrderRateLimitExceededException e){assertEquals(429,e.getStatusCode().value());assertTrue(Long.parseLong(e.getHeaders().getFirst("Retry-After"))>=1);return false;}}));
            }
            start.countDown();int passed=0;for(var f:futures)if(f.get(15,TimeUnit.SECONDS))passed++;
            assertEquals(3,passed);assertEquals("3",redis.opsForValue().get(key()));
            long ttl=redis.getExpire(key(),TimeUnit.MILLISECONDS);assertTrue(ttl>0&&ttl<=30000);
        }finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void rejectedRequestsDoNotIncrementOrExtendWindowAndExpiryRestoresBudget()throws Exception {
        var limiter=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(true,1,1,prefix));
        limiter.check(userId);long initial=redis.getExpire(key(),TimeUnit.MILLISECONDS);
        Thread.sleep(200);
        assertThrows(OrderRateLimitExceededException.class,()->limiter.check(userId));
        long after=redis.getExpire(key(),TimeUnit.MILLISECONDS);
        assertTrue(after<initial-100);assertEquals("1",redis.opsForValue().get(key()));
        await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(30)).until(()->!redis.hasKey(key()));
        assertDoesNotThrow(()->limiter.check(userId));assertEquals("1",redis.opsForValue().get(key()));
    }

    @Test void admittedRequestsAlsoKeepTheFirstWindowDeadline()throws Exception {
        var limiter=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(true,3,5,prefix));
        limiter.check(userId);long initial=redis.getExpire(key(),TimeUnit.MILLISECONDS);
        Thread.sleep(150);limiter.check(userId);
        assertEquals("2",redis.opsForValue().get(key()));assertTrue(redis.getExpire(key(),TimeUnit.MILLISECONDS)<initial-75);
    }

    @Test void subSecondRemainingWindowStillRejectsWithPositiveRetryAfter(){
        redis.opsForValue().set(key(),"3",Duration.ofMillis(500));
        var limiter=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(true,3,30,prefix));
        var error=assertThrows(OrderRateLimitExceededException.class,()->limiter.check(userId));
        assertEquals("1",error.getHeaders().getFirst("Retry-After"));assertEquals("3",redis.opsForValue().get(key()));
    }

    @Test void keyWithoutExpiryFailsInsteadOfAllowingOrRepairingCorruptState(){
        redis.opsForValue().set(key(),"1");
        var limiter=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(true,3,30,prefix));
        assertThrows(DataAccessException.class,()->limiter.check(userId));
        assertEquals("1",redis.opsForValue().get(key()));assertEquals(-1L,redis.getExpire(key(),TimeUnit.MILLISECONDS));
    }

    @ParameterizedTest @ValueSource(strings={"not-a-number","0","-1","1.5"})
    void invalidCountersAreNotSilentlyAccepted(String value){
        redis.opsForValue().set(key(),value,Duration.ofSeconds(30));
        var limiter=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(true,3,30,prefix));
        assertThrows(DataAccessException.class,()->limiter.check(userId));assertEquals(value,redis.opsForValue().get(key()));
    }

    @Test void wrongRedisValueTypeFailsWithoutOverwritingData(){
        redis.opsForList().rightPush(key(),"owned-test-value");redis.expire(key(),Duration.ofSeconds(30));
        var limiter=new RedisOrderRateLimiter(redis,new OrderRateLimitProperties(true,3,30,prefix));
        assertThrows(DataAccessException.class,()->limiter.check(userId));assertEquals(List.of("owned-test-value"),redis.opsForList().range(key(),0,-1));
    }
}
