package org.test.app.service;

import lombok.RequiredArgsConstructor;
import org.test.app.model.Currency;
import org.test.app.model.CurrencyGroupedOrder;
import org.test.app.model.OrderRequest;
import org.test.app.model.OrderRequestInOneCurrency;
import org.test.app.model.OrderTotal;
import org.test.app.model.OrderTotalWithDiscount;
import org.test.app.model.ProductPackage;
import org.test.reactive.Flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class OrderProcessingService {
    private final CurrencyService currencyService;

    public OrderTotalWithDiscount imperativeProcessingPipeline(OrderRequest orderRequest) {

        CurrencyGroupedOrder currencyGroupedOrder = toCurrencyGroupedOrder(orderRequest);
        OrderRequestInOneCurrency orderRequestInOneCurrency = toOneCurrencyOrder(currencyGroupedOrder);
        OrderTotal orderTotal = toOrderTotal(orderRequestInOneCurrency);
        OrderTotalWithDiscount orderTotalWithDiscount = applyDiscount(orderTotal);

        return orderTotalWithDiscount;
    }

    public OrderTotalWithDiscount imperativeProcessingPipeline2(OrderRequest orderRequest) {
        return applyDiscount(
            toOrderTotal(
                toOneCurrencyOrder(
                    toCurrencyGroupedOrder(orderRequest)
                )
            )
        );
    }

    public Function<OrderRequest, OrderTotalWithDiscount> functionalProcessingPipeline() {
        return Function.<OrderRequest>identity()
            .andThen(this::toCurrencyGroupedOrder)
            .andThen(this::toOneCurrencyOrder)
            .andThen(this::toOrderTotal)
            .andThen(this::applyDiscount);
    }

    public Stream<OrderTotalWithDiscount> streamProcessingPipeline(Stream<OrderRequest> orderRequestStream) {
        return orderRequestStream
            .filter(orderRequest -> !orderRequest.getProducts().isEmpty())
            .map(this::toCurrencyGroupedOrder)
            .map(this::toOneCurrencyOrder)
            .map(this::toOrderTotal)
            .map(this::applyDiscount);
    }

    public Flow<OrderTotalWithDiscount> flowProcessingPipelineNotFused(Flow<OrderRequest> flow) {
        return flow
            .filter(orderRequest -> !orderRequest.getProducts().isEmpty())
            .map(this::toCurrencyGroupedOrder)
            .map(this::toOneCurrencyOrder)
            .map(this::toOrderTotal)
            .map(this::applyDiscount);
    }

    public Flow<OrderTotalWithDiscount> flowProcessingPipelineFused(Flow<OrderRequest> flow) {
        return flow
            .filter(orderRequest -> !orderRequest.getProducts().isEmpty())
            .map(this::imperativeProcessingPipeline);
    }

    // --- Processing steps ----------------------------------------------------

    public CurrencyGroupedOrder toCurrencyGroupedOrder(OrderRequest request) {
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

    public OrderRequestInOneCurrency toOneCurrencyOrder(CurrencyGroupedOrder currencyGroupedOrder) {
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

    public OrderTotal toOrderTotal(OrderRequestInOneCurrency orderRequest) {
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

    public OrderTotalWithDiscount applyDiscount(OrderTotal orderTotal) {
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
