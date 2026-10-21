package com.elaina.cloud.product;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProductController {
    private final String name;
    private final BigDecimal price;
    private final String instance;
    private final ProductMode mode;
    public ProductController(@Value("${lab.product.name}") String name,
            @Value("${lab.product.price}") BigDecimal price,@Value("${lab.instance}") String instance,ProductMode mode) {
        this.name=name;this.price=price;this.instance=instance;this.mode=mode;
    }
    @GetMapping("/api/products/{id}")
    public Product get(@PathVariable long id) throws InterruptedException {
        if(id!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"商品不存在");
        switch(mode.current.get()) {
            case "slow" -> Thread.sleep(2000);
            case "fail" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"实验下游故障");
            default -> { }
        }
        return new Product(id,name,price,instance);
    }
    public record Product(long id,String name,BigDecimal price,String instance) {}
}
@Component
class ProductMode {
    final AtomicReference<String> current=new AtomicReference<>("normal");
}
