package org.test.app;

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
import org.reactivestreams.Publisher;
import org.test.app.model.OrderRequest;
import org.test.app.model.OrderTotalWithDiscount;
import org.test.app.model.Product;
import org.test.app.model.ProductPackage;
import org.test.app.service.CurrencyService;
import org.test.app.service.OrderProcessingService;
import org.test.reactive.Flow;
import org.test.reactive.PerfSubscriber;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;
import static org.test.app.model.Currency.CAD;
import static org.test.app.model.Currency.EUR;
import static org.test.app.model.Currency.UAH;

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
public class OrderProcessingFusedPerfTest {
    @Param({ "5" })
    public int times;

    Publisher<OrderTotalWithDiscount> decorator;
    Flow<OrderTotalWithDiscount> notFused;
    Flow<OrderTotalWithDiscount> fused;

    @Setup
    public void setup() {
        OrderRequest[] dataToProcess = new OrderRequest[times];
        Arrays.fill(dataToProcess, genOrderRequest());

        CurrencyService currencyService = new CurrencyService();
        OrderProcessingService processingService = new OrderProcessingService(currencyService);

        decorator = processingService.publisherProcessingPipeline(dataToProcess);
        notFused = processingService.flowProcessingPipelineNotFused(Flow.fromArray(dataToProcess));
        fused = processingService.flowProcessingPipelineFused(Flow.fromArray(dataToProcess));
    }

    // --- Order Processing ----------------------------------------------------

    //@Benchmark
    public Object decorator(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        decorator.subscribe(lo);
        return lo;
    }

    //@Benchmark
    public Object notFused(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        notFused.subscribe(lo);
        return lo;
    }

    //@Benchmark
    public Object fused(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        fused.subscribe(lo);
        return lo;
    }

    private OrderRequest genOrderRequest() {
        return new OrderRequest(
            "order-1",
            asList(
                new ProductPackage(new Product("p-1", "Milk 1L", UAH, 25.00), 2),
                new ProductPackage(new Product("p-2", "Bread", CAD, 1.31), 2),
                new ProductPackage(new Product("p-3", "Butter Selianske", EUR, 2.00), 1)
            ),
            UAH
        );
    }

    public static void main(String[] args) throws IOException, RunnerException {
        org.openjdk.jmh.Main.main(args);
    }
}
