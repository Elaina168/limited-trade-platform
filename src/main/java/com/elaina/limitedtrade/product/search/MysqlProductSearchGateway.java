package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.product.CategoryFacet;
import com.elaina.limitedtrade.product.ProductMapper;
import com.elaina.limitedtrade.product.ProductSearchCriteria;
import com.elaina.limitedtrade.product.ProductSearchItem;
import com.elaina.limitedtrade.product.ProductSearchPage;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

@Component
public class MysqlProductSearchGateway implements ProductSearchGateway {

    private final ProductMapper productMapper;

    public MysqlProductSearchGateway(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductSearchPage search(ProductSearchCriteria criteria) {
        String rawKeyword = criteria.normalizedKeyword();
        String literalKeyword = escapeLike(rawKeyword);
        long offset = (long) (criteria.page() - 1) * criteria.size();
        long total = productMapper.countFilteredSearchProducts(
                criteria.categoryId(), literalKeyword, criteria.minPrice(), criteria.maxPrice(), criteria.inStockOnly());
        List<ProductSearchItem> rows = productMapper.searchFilteredProducts(
                criteria.categoryId(), literalKeyword, criteria.minPrice(), criteria.maxPrice(),
                criteria.inStockOnly(), criteria.normalizedSort(), offset, criteria.size());
        List<ProductSearchItem> items = rawKeyword == null
                ? rows
                : rows.stream().map(item -> item.withHighlight(highlightLiteral(item.name(), rawKeyword))).toList();
        List<CategoryFacet> facets = productMapper.aggregateSearchCategories(
                literalKeyword, criteria.minPrice(), criteria.maxPrice(), criteria.inStockOnly());
        return new ProductSearchPage(criteria.page(), criteria.size(), total, items, facets, "mysql", false);
    }

    @Override
    public List<String> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank() || limit <= 0) {
            return List.of();
        }
        return productMapper.suggestProductNames(escapeLike(prefix.strip()), Math.min(limit, 20));
    }

    public static String escapeLike(String keyword) {
        return keyword == null || keyword.isBlank() ? null
                : keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static String highlightLiteral(String text, String keyword) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return text;
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerKey = keyword.toLowerCase(Locale.ROOT);
        int index = lowerText.indexOf(lowerKey);
        if (index < 0) {
            return HtmlUtils.htmlEscape(text);
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int cursor = 0;
        while (index >= 0) {
            sb.append(HtmlUtils.htmlEscape(text.substring(cursor, index)));
            sb.append("<mark>")
              .append(HtmlUtils.htmlEscape(text.substring(index, index + keyword.length())))
              .append("</mark>");
            cursor = index + keyword.length();
            index = lowerText.indexOf(lowerKey, cursor);
        }
        sb.append(HtmlUtils.htmlEscape(text.substring(cursor)));
        return sb.toString();
    }
}
