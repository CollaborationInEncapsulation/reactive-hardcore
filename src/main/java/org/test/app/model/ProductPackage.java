package org.test.app.model;

import lombok.Value;

@Value
public class ProductPackage {
    private final Product product;
    private final int quantity;
}
