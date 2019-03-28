package org.test.app.model;

import lombok.Value;

import java.util.List;

@Value
public class OrderTotal {
    private final String id;
    private final List<ProductPackage> products;
    private final Currency orderCurrency;
    private final double totalPrice;
}
