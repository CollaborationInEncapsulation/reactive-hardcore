package org.test.app.service;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang3.tuple.Pair;
import org.test.app.model.Currency;
import org.test.app.model.Product;
import org.test.app.model.ProductPackage;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.test.app.model.Currency.CAD;
import static org.test.app.model.Currency.EUR;
import static org.test.app.model.Currency.UAH;
import static org.test.app.model.Currency.USD;

/**
 * ATTENTION: Using DOUBLE type only for demo, use a proper money types in production systems!
 */
public class CurrencyService {
    private static Map<Pair<Currency, Currency>, Double> EXCHANGE_RATES =
        ImmutableMap.<Pair<Currency, Currency>, Double>builder()
            .put(Pair.of(USD, USD), 1.000)
            .put(Pair.of(USD, EUR), 0.887)
            .put(Pair.of(USD, UAH), 27.201)
            .put(Pair.of(USD, CAD), 1.327)

            .put(Pair.of(EUR, USD), 1.126)
            .put(Pair.of(EUR, EUR), 1.00)
            .put(Pair.of(EUR, UAH), 30.706)
            .put(Pair.of(EUR, CAD), 1.496)

            .put(Pair.of(UAH, USD), 0.0367)
            .put(Pair.of(UAH, EUR), 0.0325)
            .put(Pair.of(UAH, UAH), 1.00)
            .put(Pair.of(UAH, CAD), 0.0487)

            .put(Pair.of(CAD, USD), 0.754)
            .put(Pair.of(CAD, EUR), 0.668)
            .put(Pair.of(CAD, UAH), 20.528)
            .put(Pair.of(CAD, CAD), 1.00)
        .build();

    public List<ProductPackage> translateProductPrices(
        Currency from,
        Currency to,
        List<ProductPackage> productPackages
    ) {
        double exchangeRate = EXCHANGE_RATES.get(Pair.of(from, to));

        return productPackages.stream()
            .map(originalPackage -> {
                Product originalProduct = originalPackage.getProduct();
                double newPrice = originalProduct.getPrice() * exchangeRate;
                return new ProductPackage(
                    new Product(
                        originalProduct.getSku(),
                        originalProduct.getName(),
                        to,
                        newPrice
                    ),
                    originalPackage.getQuantity()
                );
            })
            .collect(Collectors.toList());
    }
}
