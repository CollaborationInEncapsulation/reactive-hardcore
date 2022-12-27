package org.test.app.model;

import java.util.List;
import java.util.Map;

import lombok.Value;

@Value
public class CurrencyGroupedOrder {
    private final String id;
    private final Map<Currency, List<ProductPackage>> products;
    private final Currency orderCurrency;
}
