package com.elaina.limitedtrade.address;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AddressConcurrencyIntegrationTest {
    @Autowired AddressService service;
    @Autowired JdbcTemplate jdbc;
    private boolean owned;
    private final SaveAddressRequest request=new SaveAddressRequest("张三","13800138000","浙江","杭州","西湖","学院路1号");
    @BeforeEach void prepare() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE id IN (7941,7942) OR username IN ('address_parallel_1','address_parallel_2')",Integer.class));
        jdbc.update("INSERT INTO app_user(id,username,password_hash,role) VALUES (7941,'address_parallel_1','unused','USER'),(7942,'address_parallel_2','unused','USER')");
        owned=true;
    }
    @AfterEach void cleanup() {
        if(owned) {
            jdbc.update("DELETE FROM user_address WHERE user_id IN (7941,7942)");
            jdbc.update("DELETE FROM app_user WHERE id IN (7941,7942)");
        }
    }
    @Test void concurrentFirstCreatesOnlyOneDefault() throws Exception {
        List<Callable<Long>> tasks=new ArrayList<>();
        for(int i=0;i<20;i++) tasks.add(()->service.createAddress(7941,request).id());
        assertEquals(20,new HashSet<>(runConcurrently(tasks)).size());
        assertEquals(20,service.listAddresses(7941).size());
        assertEquals(1,service.listAddresses(7941).stream().filter(AddressResponse::defaultAddress).count());
    }
    @Test void concurrentSwitchesKeepOneDefaultPerUser() throws Exception {
        long a=service.createAddress(7941,request).id(),b=service.createAddress(7941,request).id();
        var other=service.createAddress(7942,request);
        List<Callable<Long>> tasks=new ArrayList<>();
        for(int i=0;i<20;i++) {
            long target=i%2==0?a:b;
            tasks.add(()->{service.setDefaultAddress(7941,target);return target;});
        }
        runConcurrently(tasks);
        var defaults=service.listAddresses(7941).stream().filter(AddressResponse::defaultAddress).toList();
        assertEquals(1,defaults.size()); assertTrue(List.of(a,b).contains(defaults.getFirst().id()));
        assertEquals(List.of(other),service.listAddresses(7942));
    }
    @Test void switchingAndDeletingTargetHasOnlyAllowedResults() throws Exception {
        long a=service.createAddress(7941,request).id(),b=service.createAddress(7941,request).id();
        var other=service.createAddress(7942,request);
        runConcurrently(List.of(()->{
            try {service.setDefaultAddress(7941,b);}
            catch(ResponseStatusException e){assertEquals(404,e.getStatusCode().value());}
            return b;
        },()->{service.deleteAddress(7941,b);return b;}));
        var remaining=service.listAddresses(7941);
        assertEquals(1,remaining.size());assertEquals(a,remaining.getFirst().id());
        assertEquals(List.of(other),service.listAddresses(7942));
    }
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(10);
        try {
            var futures=tasks.stream().map(task->pool.submit(()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));return task.call();
            })).toList();
            start.countDown();var results=new ArrayList<T>();
            for(var future:futures)results.add(future.get(20,TimeUnit.SECONDS));
            return results;
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    }
}
