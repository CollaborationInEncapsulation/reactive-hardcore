package org.test.app.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.test.app.model.Currency;
import org.test.app.model.CurrencyGroupedOrder;
import org.test.app.model.OrderRequest;
import org.test.app.model.OrderRequestInOneCurrency;
import org.test.app.model.OrderTotal;
import org.test.app.model.OrderTotalWithDiscount;
import org.test.app.model.ProductPackage;

@RequiredArgsConstructor
public class OrderProcessingService {
    private final CurrencyService currencyService;

    public OrderTotalWithDiscount process(OrderRequest orderRequest) {

        CurrencyGroupedOrder currencyGroupedOrder = toCurrencyGroupedOrder(orderRequest);
        OrderRequestInOneCurrency orderRequestInOneCurrency = toOneCurrencyOrder(currencyGroupedOrder);
        OrderTotal orderTotal = toOrderTotal(orderRequestInOneCurrency);
        OrderTotalWithDiscount orderTotalWithDiscount = applyDiscount(orderTotal);

        return orderTotalWithDiscount;
    }

    // --- Processing steps ----------------------------------------------------

    private CurrencyGroupedOrder toCurrencyGroupedOrder(OrderRequest request) {
        Map<Currency, List<ProductPackage>> grouped = new HashMap<>();

        request.getProducts().forEach(
            productPackage -> grouped.computeIfAbsent(
                productPackage.getProduct().getCurrency(),
                (__) -> new ArrayList<>()
            ).add(productPackage)
        );

        return new CurrencyGroupedOrder(
            request.getId(),
            grouped,
            request.getOrderCurrency()
        );
    }

    private OrderRequestInOneCurrency toOneCurrencyOrder(CurrencyGroupedOrder currencyGroupedOrder) {
        Currency targetCurrency = currencyGroupedOrder.getOrderCurrency();

        List<ProductPackage> oneCurrencyPackage = new ArrayList<>();
        currencyGroupedOrder.getProducts().forEach(
            (Currency fromCurrency, List<ProductPackage> products) -> oneCurrencyPackage.addAll(
                currencyService.translateProductPrices(
                    fromCurrency,
                    targetCurrency,
                    products
            ))
        );

        return new OrderRequestInOneCurrency(
            currencyGroupedOrder.getId(),
            oneCurrencyPackage,
            targetCurrency
        );
    }

    private OrderTotal toOrderTotal(OrderRequestInOneCurrency orderRequest) {
        double totalPrice = orderRequest.getProducts().stream()
            .mapToDouble(pkg -> pkg.getProduct().getPrice() * pkg.getQuantity())
            .sum();
        return new OrderTotal(
            orderRequest.getId(),
            orderRequest.getProducts(),
            orderRequest.getOrderCurrency(),
            totalPrice
        );
    }

    private OrderTotalWithDiscount applyDiscount(OrderTotal orderTotal) {
        // 20% discount
        double discount = orderTotal.getTotalPrice() * 0.2;
        double finalPrice = orderTotal.getTotalPrice() - discount;

        return new OrderTotalWithDiscount(
            orderTotal.getId(),
            orderTotal.getProducts(),
            orderTotal.getOrderCurrency(),
            orderTotal.getTotalPrice(),
            discount,
            finalPrice
        );
    }
}
