package com.elaina.limitedtrade.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/product-query-data.sql")
class ProductQueryTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getProductsReturnsOnlyOnSaleProductsInIdOrder() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(9001))
                .andExpect(jsonPath("$[0].name").value("测试键盘"))
                .andExpect(jsonPath("$[0].price").value(129.00))
                .andExpect(jsonPath("$[0].stock").value(10))
                .andExpect(jsonPath("$[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$[1].id").value(9002));
    }
}
