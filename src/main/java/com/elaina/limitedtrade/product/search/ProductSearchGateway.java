package com.elaina.limitedtrade.product.search;

import com.elaina.limitedtrade.product.ProductSearchCriteria;
import com.elaina.limitedtrade.product.ProductSearchPage;
import java.util.List;

public interface ProductSearchGateway {

    ProductSearchPage search(ProductSearchCriteria criteria);

    List<String> suggest(String prefix, int limit);
}
