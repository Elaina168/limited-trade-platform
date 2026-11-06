package com.elaina.limitedtrade.system;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class WebUiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void prepare(){
        jdbc.update("INSERT INTO product(id,name,price,stock,status) VALUES(20001,'页面在售商品',20,5,'ON_SALE'),(20002,'页面下架商品',30,2,'OFF_SALE')");
        jdbc.update("INSERT INTO activity(id,product_id,name,total_quantity,remaining_quantity,starts_at,ends_at) VALUES(20001,20001,'页面活动',3,3,NOW(),DATE_ADD(NOW(),INTERVAL 1 HOUR))");
    }
    @Test void pagesAndOnlyTheirAssetsArePublic()throws Exception{
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/assets/app.js")).andExpect(status().isOk());
        mvc.perform(get("/assets/agent.js")).andExpect(status().isOk());
        mvc.perform(get("/assets/sse.js")).andExpect(status().isOk());
        mvc.perform(get("/assets/styles.css")).andExpect(status().isOk());
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/config/db.properties")).andExpect(status().isUnauthorized());
    }
    @Test void activityBrowseWorksWithoutLogin()throws Exception{
        mvc.perform(get("/api/activities")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(20001))
                .andExpect(jsonPath("$[0].productId").value(20001)).andExpect(jsonPath("$[0].remainingQuantity").value(3));
    }
    @Test void adminProductListIncludesOffSaleAndRemainsAdminOnly()throws Exception{
        var admin=user(new PlatformUserDetails(new AppUser(1L,"ui_admin","unused","ADMIN")));
        mvc.perform(get("/api/admin/products").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("OFF_SALE"));
        mvc.perform(get("/api/admin/products").with(user(new PlatformUserDetails(new AppUser(2L,"ui_user","unused","USER")))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
}
