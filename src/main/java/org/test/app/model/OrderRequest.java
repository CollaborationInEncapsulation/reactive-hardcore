package org.test.app.model;

import java.util.List;

import lombok.Value;

@Value
public class OrderRequest {
    private final String id;
    private final List<ProductPackage> products;
    private final Currency orderCurrency;
}
