package org.test.app.model;

import lombok.Value;

/**
 * Let's assume that DOUBLE is good enough for money representation.
 */
@Value
public class Product {
    private final String sku;
    private final String name;
    private final Currency currency;
    private final double price;
}
