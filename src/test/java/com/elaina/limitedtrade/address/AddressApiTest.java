package com.elaina.limitedtrade.address;

import com.elaina.limitedtrade.auth.PlatformUserDetails;
import com.elaina.limitedtrade.user.AppUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql("/sql/address-test-data.sql")
class AddressApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    private static final String URL="/api/addresses";

    private Map<String,Object> body() {
        return new LinkedHashMap<>(Map.of("recipientName"," 张三 ","phone","13800138000",
                "province"," 浙江省 ","city"," 杭州市 ","district"," 西湖区 ","detailAddress"," 学院路1号 "));
    }
    private RequestPostProcessor buyer(long id) {
        return user(new PlatformUserDetails(new AppUser(id,"address_api_"+id,"unused","USER")));
    }
    private long create(long owner) throws Exception {
        var result=mvc.perform(post(URL).with(buyer(owner)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body()))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.recipientName").value("张三"))
                .andExpect(jsonPath("$.province").value("浙江省")).andExpect(jsonPath("$.city").value("杭州市"))
                .andExpect(jsonPath("$.district").value("西湖区")).andExpect(jsonPath("$.detailAddress").value("学院路1号"))
                .andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray()).get("id").asLong();
    }
    private int defaults(long owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_address WHERE user_id=? AND is_default=1",Integer.class,owner);
    }
    @Test void firstAddressIsDefaultAndLaterOnesAreNot() throws Exception {
        mvc.perform(get(URL).with(buyer(7931))).andExpect(status().isOk()).andExpect(content().json("[]"));
        long a=create(7931),b=create(7931),c=create(7931);
        assertEquals(1,defaults(7931));
        mvc.perform(get(URL).with(buyer(7931))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].id").value(a))
                .andExpect(jsonPath("$[0].defaultAddress").value(true)).andExpect(jsonPath("$[1].id").value(b))
                .andExpect(jsonPath("$[1].defaultAddress").value(false)).andExpect(jsonPath("$[2].id").value(c))
                .andExpect(jsonPath("$[2].defaultAddress").value(false));
    }
    @Test void switchDefaultAndPreserveItOnContentUpdate() throws Exception {
        long a=create(7931),b=create(7931),c=create(7931);
        for(int i=0;i<2;i++) {
            mvc.perform(put(URL+"/"+c+"/default").with(buyer(7931)).with(csrf())).andExpect(status().isNoContent());
            mvc.perform(put(URL+"/"+c).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body()))).andExpect(status().isOk()).andExpect(jsonPath("$.defaultAddress").value(true));
        }
        mvc.perform(get(URL).with(buyer(7931))).andExpect(jsonPath("$[0].id").value(c))
                .andExpect(jsonPath("$[1].id").value(a)).andExpect(jsonPath("$[1].defaultAddress").value(false))
                .andExpect(jsonPath("$[2].id").value(b));
        assertEquals(1,defaults(7931));
    }
    @Test void deleteDefaultDoesNotPromoteRemainingAddresses() throws Exception {
        long a=create(7931),b=create(7931);
        mvc.perform(delete(URL+"/"+a).with(buyer(7931)).with(csrf())).andExpect(status().isNoContent());
        assertEquals(0,defaults(7931)); long c=create(7931); assertEquals(0,defaults(7931));
        for(long id:new long[]{b,c}) mvc.perform(delete(URL+"/"+id).with(buyer(7931)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete(URL+"/"+a).with(buyer(7931)).with(csrf())).andExpect(status().isNotFound());
        create(7931); assertEquals(1,defaults(7931));
    }
    @Test void ownershipAndForgedFieldsCannotChangeOthers() throws Exception {
        long a=create(7931),b=create(7932);
        var forged=body(); forged.put("userId",7932); forged.put("defaultAddress",true);
        mvc.perform(post(URL).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(forged))).andExpect(status().isCreated()).andExpect(jsonPath("$.defaultAddress").value(false));
        forged.put("defaultAddress",false);
        mvc.perform(put(URL+"/"+a).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(forged))).andExpect(status().isOk()).andExpect(jsonPath("$.defaultAddress").value(true));
        for(long target:new long[]{b,Long.MAX_VALUE}) {
            mvc.perform(put(URL+"/"+target).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body()))).andExpect(status().isNotFound());
            mvc.perform(delete(URL+"/"+target).with(buyer(7931)).with(csrf())).andExpect(status().isNotFound());
            mvc.perform(put(URL+"/"+target+"/default").with(buyer(7931)).with(csrf())).andExpect(status().isNotFound());
            assertEquals(1,defaults(7931));
        }
        mvc.perform(get(URL).with(buyer(7932))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(b));
        var admin=user(new PlatformUserDetails(new AppUser(7931L,"admin","unused","ADMIN")));
        mvc.perform(get(URL).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete(URL+"/"+b).with(admin).with(csrf())).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"recipientName","phone","province","city","district","detailAddress"})
    void missingNullAndBlankFieldsAreRejected(String field) throws Exception {
        for(int kind=0;kind<3;kind++) {
            var invalid=body(); if(kind==0) invalid.remove(field); else invalid.put(field,kind==1?null:"   ");
            for(var request:new MockHttpServletRequestBuilder[]{post(URL),put(URL+"/1")})
                mvc.perform(request.with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(invalid))).andExpect(status().isBadRequest());
        }
        assertEquals(0,defaults(7931));
    }
    @ParameterizedTest @ValueSource(strings={"recipientName","province","city","district","detailAddress"})
    void textLengthBoundaries(String field) throws Exception {
        int max=field.equals("detailAddress")?200:50; var data=body(); data.put(field,"字".repeat(max+1));
        mvc.perform(post(URL).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
        data.put(field,"字".repeat(max));
        mvc.perform(post(URL).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(data))).andExpect(status().isCreated());
    }
    @ParameterizedTest @ValueSource(strings={"1380013800","138001380000","12800138000","+8613800138000"," 13800138000","13800138000 ","1380013800x"})
    void invalidPhone(String phone) throws Exception {
        var data=body();data.put("phone",phone);
        mvc.perform(post(URL).with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(data))).andExpect(status().isBadRequest());
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","no","1.5","9223372036854775808"})
    void invalidPath(String id) throws Exception {
        for(var request:new MockHttpServletRequestBuilder[]{put(URL+"/"+id),delete(URL+"/"+id),put(URL+"/"+id+"/default")})
            mvc.perform(request.with(buyer(7931)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body()))).andExpect(status().isBadRequest());
    }
    @Test void authenticationAndCsrf() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        for(int mode=0;mode<3;mode++) {
            for(int endpoint=0;endpoint<4;endpoint++) {
                var request=switch(endpoint){case 0->post(URL);case 1->put(URL+"/1");case 2->delete(URL+"/1");default->put(URL+"/1/default");};
                request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body()));
                if(mode==0) request.with(csrf());
                else { request.with(buyer(7931));if(mode==2)request.with(csrf().useInvalidToken());if(mode==3)request.with(csrf()); }
                mvc.perform(request).andExpect(mode==0?status().isUnauthorized():mode==3?(endpoint==0?status().isCreated():status().isNotFound()):status().isForbidden());
            }
        }
    }
}
