package org.test.app;

import org.test.app.model.OrderRequest;
import org.test.app.model.OrderTotalWithDiscount;
import org.test.app.model.Product;
import org.test.app.model.ProductPackage;
import org.test.app.service.CurrencyService;
import org.test.app.service.OrderProcessingService;

import static java.util.Arrays.asList;
import static org.test.app.model.Currency.CAD;
import static org.test.app.model.Currency.EUR;
import static org.test.app.model.Currency.UAH;

public class Application {

    public static void main(String[] args) {
        CurrencyService currencyService = new CurrencyService();
        OrderProcessingService processingService = new OrderProcessingService(currencyService);

        OrderRequest order = new OrderRequest(
            "order-1",
            asList(
                new ProductPackage(new Product("p-1", "Milk 1L", UAH, 27.00), 2),
                new ProductPackage(new Product("p-2", "Bread", CAD, 1.01), 3),
                new ProductPackage(new Product("p-3", "Butter Selianske", EUR, 2.00), 1)
            ),
            UAH
        );

        OrderTotalWithDiscount result = processingService
            .functionalProcessingPipeline()
            .apply(order);

        System.out.println("Order with discount: \n" + result);
    }
}
