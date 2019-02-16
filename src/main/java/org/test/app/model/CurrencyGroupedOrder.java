package org.test.app.model;

import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
public class CurrencyGroupedOrder {
    private final String id;
    private final Map<Currency, List<ProductPackage>> products;
    private final Currency orderCurrency;
}
