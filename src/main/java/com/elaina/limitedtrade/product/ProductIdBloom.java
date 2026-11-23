package com.elaina.limitedtrade.product;

import java.util.BitSet;
import java.util.List;

final class ProductIdBloom {
    private static final int BITS = 32_768;
    private final BitSet bits = new BitSet(BITS);

    ProductIdBloom(List<Product> products) {
        for (Product product : products) {
            long hash = mix(product.getId());
            bits.set(index(hash));
            bits.set(index(hash >>> 16));
            bits.set(index(hash >>> 32));
        }
    }

    boolean mightContain(long id) {
        long hash = mix(id);
        return bits.get(index(hash)) && bits.get(index(hash >>> 16)) && bits.get(index(hash >>> 32));
    }

    private static long mix(long id) {
        long hash = (id ^ (id >>> 30)) * 0xbf58476d1ce4e5b9L;
        hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
        return hash ^ (hash >>> 31);
    }

    private static int index(long hash) { return (int) hash & (BITS - 1); }
}
