package org.test.app.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.RunnerException;
import org.test.app.model.OrderRequest;
import org.test.app.model.OrderTotalWithDiscount;
import org.test.app.model.Product;
import org.test.app.model.ProductPackage;
import org.test.reactive.FastPerfSubscriber;
import org.test.reactive.SlowPerfSubscriber;
import reactor.core.publisher.Flux;

import static java.util.Arrays.asList;
import static org.test.app.model.Currency.CAD;
import static org.test.app.model.Currency.EUR;
import static org.test.app.model.Currency.UAH;

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5, time = 20)
@Measurement(iterations = 10, time = 60)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
public class ImperativeVsReactivePerfTest {
    @Param({ "10", "1000000" })
    public int times;

    Flux<OrderTotalWithDiscount> flow;
    OrderProcessingService orderProcessingService;
    OrderRequest[] orderRequests;

    @Setup
    public void setup() {
        OrderRequest order = new OrderRequest(
            "order-1",
            asList(
                new ProductPackage(new Product("p-1", "Milk 1L", UAH, 27.00), 2),
                new ProductPackage(new Product("p-2", "Bread", CAD, 1.01), 3),
                new ProductPackage(new Product("p-3", "Butter Selianske", EUR, 2.00), 1)
            ),
            UAH
        );
        CurrencyService currencyService = new CurrencyService();
        OrderRequest[] array = new OrderRequest[times];
        Arrays.fill(array, order);

        orderRequests = array;
        orderProcessingService = new OrderProcessingService(currencyService);
        flow = orderProcessingService.process(Flux.fromArray(array));
    }

    @Benchmark
    public Object reactiveSlowPathPerformance(Blackhole bh) {
        SlowPerfSubscriber lo = new SlowPerfSubscriber(bh);

        flow.subscribe(lo);

        return lo;
    }

    @Benchmark
    public Object reactiveFastPathPerformance(Blackhole bh) {
        FastPerfSubscriber lo = new FastPerfSubscriber(bh);

        flow.subscribe(lo);

        return lo;
    }

    @Benchmark
    public void imperativePerformance(Blackhole bh) {
        for (OrderRequest orderRequest : orderRequests) {
            bh.consume(orderProcessingService.imperativeProcessing(orderRequest));
        }
    }

    @Benchmark
    public void synchronousFunctionalPerformance(Blackhole bh) {
        for (OrderRequest orderRequest : orderRequests) {
            bh.consume(orderProcessingService.synchronousFunctionalProcessing(orderRequest));
        }
    }
//
//    public static void main(String[] args) throws IOException, RunnerException {
//        org.openjdk.jmh.Main.main(args);
//    }
}
