package com.elaina.limitedtrade.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@Sql({"/sql/category-test-data.sql", "/sql/catalog-product-data.sql"})
class ProductSearchApiTest {
    @Autowired private MockMvc mvc;

    @Test
    void anonymousDefaultSearchReturnsOnlyOnSaleProductsIncludingUnclassified() throws Exception {
        mvc.perform(get("/api/products/search")).andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(9)).andExpect(jsonPath("$.items.length()").value(9))
                .andExpect(jsonPath("$.items[0].id").value(10001))
                .andExpect(jsonPath("$.items[0].categoryId").value(12001))
                .andExpect(jsonPath("$.items[0].categoryName").value("数码配件"))
                .andExpect(jsonPath("$.items[1].stock").value(0))
                .andExpect(jsonPath("$.items[3].id").value(10004))
                .andExpect(jsonPath("$.items[3].categoryId").isEmpty())
                .andExpect(jsonPath("$.items[3].categoryName").isEmpty())
                .andExpect(jsonPath("$.items[8].id").value(10010));
    }

    @Test
    void filtersByCategoryForCountAndItems() throws Exception {
        mvc.perform(get("/api/products/search").param("categoryId", "12001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].id").value(10001))
                .andExpect(jsonPath("$.items[4].id").value(10010));
    }

    @Test
    void nameKeywordIsTrimmedAndCombinedWithCategory() throws Exception {
        mvc.perform(get("/api/products/search").param("categoryId", "12001").param("keyword", "  键盘  ")
                        .param("size", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(10006));
    }

    @Test
    void nameKeywordAloneIncludesAllMatchingCategoriesAndUnclassified() throws Exception {
        mvc.perform(get("/api/products/search").param("keyword", "键盘"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[0].id").value(10001))
                .andExpect(jsonPath("$.items[1].id").value(10003))
                .andExpect(jsonPath("$.items[2].id").value(10004))
                .andExpect(jsonPath("$.items[3].id").value(10006));
    }

    @Test
    void whitespaceKeywordMeansNoNameFilter() throws Exception {
        mvc.perform(get("/api/products/search").param("keyword", "  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(9));
    }

    @ParameterizedTest
    @CsvSource({"%,10006", "_,10007", "!,10008", "O'Reilly,10009"})
    void keywordMetacharactersAreMatchedLiterally(String keyword, long id) throws Exception {
        mvc.perform(get("/api/products/search").param("keyword", keyword))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void backslashIsAlsoAnOrdinaryKeywordCharacter() throws Exception {
        mvc.perform(get("/api/products/search").param("keyword", "\\"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(10010));
    }

    @Test
    void sqlLookingKeywordCannotChangeQueryConditions() throws Exception {
        mvc.perform(get("/api/products/search").param("keyword", "' OR 1=1 --"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void unknownCategoryOrUnmatchedKeywordReturnsAnEmptyPage() throws Exception {
        mvc.perform(get("/api/products/search").param("categoryId", "12999"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/products/search").param("keyword", "不存在的名称"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void middleLastAndOutOfRangePagesPreserveTotalAndStableOrder() throws Exception {
        mvc.perform(get("/api/products/search").param("page", "2").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.items[0].id").value(10003))
                .andExpect(jsonPath("$.items[1].id").value(10004));
        mvc.perform(get("/api/products/search").param("page", "5").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(10010));
        mvc.perform(get("/api/products/search").param("page", "6").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void largestLegalPageDoesNotOverflowOffset() throws Exception {
        mvc.perform(get("/api/products/search").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(9))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"page,0", "page,-1", "page,1.5", "page,no", "page,2147483648", "size,0", "size,-1", "size,101", "categoryId,0", "categoryId,-1", "categoryId,no", "categoryId,1.5"})
    void invalidQueryParametersAreRejected(String parameter, String value) throws Exception {
        mvc.perform(get("/api/products/search").param(parameter, value)).andExpect(status().isBadRequest());
    }

    @Test
    void oversizedKeywordIsRejected() throws Exception {
        mvc.perform(get("/api/products/search").param("keyword", "字".repeat(101))).andExpect(status().isBadRequest());
    }

    @Test
    void oldPublicListStillHasExactlyItsOriginalFiveFields() throws Exception {
        var result = mvc.perform(get("/api/products")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9)).andReturn().getResponse().getContentAsString();
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result).get(0);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("id", "name", "price", "stock", "status"),
                new java.util.HashSet<>(node.properties().stream().map(java.util.Map.Entry::getKey).toList()));
    }
}
