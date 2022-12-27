package org.test.app.model;

import java.util.List;
import java.util.stream.Collectors;

import lombok.Value;

@Value
public class OrderTotalWithDiscount {
    private final String id;
    private final List<ProductPackage> products;
    private final Currency orderCurrency;
    private final double totalPrice;
    private final double discount;
    private final double finalPrice;

    @Override
    public String toString() {
        return "OrderTotalWithDiscount(" +
            "\n  id='" + id + '\'' +
            "\n  products=[\n    " +
            products.stream()
                .map(ProductPackage::toString)
                .collect(Collectors.joining("\n    ")) +
            "\n  ]" +
            "\n  orderCurrency=" + orderCurrency +
            "\n  totalPrice=" + totalPrice +
            "\n  discount=" + discount +
            "\n  finalPrice=" + finalPrice +
            "\n)";
    }
}
