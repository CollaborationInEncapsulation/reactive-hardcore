package org.test.app;

import java.util.concurrent.ThreadLocalRandom;

import org.reactivestreams.Subscription;
import org.test.app.model.Currency;
import org.test.app.model.OrderRequest;
import org.test.app.model.OrderTotalWithDiscount;
import org.test.app.model.Product;
import org.test.app.model.ProductPackage;
import org.test.app.service.CurrencyService;
import org.test.app.service.OrderProcessingService;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

import static java.util.Arrays.asList;
import static org.test.app.model.Currency.UAH;

public class Application {

    public static void main(String[] args) {
        CurrencyService currencyService = new CurrencyService();
        OrderProcessingService processingService = new OrderProcessingService(currencyService);
        Application application = new Application();

        for (int i = 0; i < 100_000_000; i++) {
            application.doWork(generateRandom(), generateRandom(), processingService);
        }

        for (int iteration = 0; iteration < 100; iteration++) {
            System.out.println("Start Iteration " + iteration);
            long startTime = System.nanoTime();

            for (int i = 0; i < 1_000_000; i++) {
                application.doWork(generateRandom(), generateRandom(), processingService);
            }

            System.out.println("End iteration. Took " + (System.nanoTime() - startTime));
        }

        System.out.println("Done");
    }

    void doWork(OrderRequest order1, OrderRequest order2, OrderProcessingService processingService) {
        Flux<OrderTotalWithDiscount> resultPublisher = processingService
            .process(Flux.just(order1, order2));

        resultPublisher.subscribe(new BaseSubscriber<OrderTotalWithDiscount>() {
            @Override
            public void hookOnSubscribe(Subscription s) {
                s.request(1);
            }

            @Override
            public void hookOnNext(OrderTotalWithDiscount result) {
//                System.out.println("Order with discount: \n" + result);
                request(1);
            }
        });
    }

    static OrderRequest generateRandom() {
        ThreadLocalRandom current = ThreadLocalRandom.current();
        return new OrderRequest(
            "order-" + current.nextInt(),
            asList(
                new ProductPackage(
                    new Product("p-1", "Milk 1L", Currency.values()[current.nextInt(0, 4)], current.nextDouble() * 100),
                    2
                ),
                new ProductPackage(
                    new Product("p-2", "Bread", Currency.values()[current.nextInt(0, 4)], current.nextDouble() * 10),
                    3
                ),
                new ProductPackage(
                    new Product("p-3", "Butter Selianske", Currency.values()[current.nextInt(0, 4)], current.nextDouble() * 5),
                    1
                )
            ),
            UAH
        );
    }
}
