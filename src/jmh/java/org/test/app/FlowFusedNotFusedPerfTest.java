package org.test.app;

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
@Warmup(iterations = 3)
@Measurement(iterations = 4, time = 4, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
public class FlowFusedNotFusedPerfTest {
    @Param({ "5" })
    public int times;

    Flow<OrderTotalWithDiscount> notFused;
    Flow<OrderTotalWithDiscount> fused;

    Flow<String> simpleNotFused;
    Flow<String> simpleFused;

    @Setup
    public void setup() {
        OrderRequest[] dataToProcess = new OrderRequest[times];
        Arrays.fill(dataToProcess, genOrderRequest());

        CurrencyService currencyService = new CurrencyService();
        OrderProcessingService processingService = new OrderProcessingService(currencyService);

        notFused = processingService.flowProcessingPipelineNotFused(Flow.fromArray(dataToProcess));
        fused = processingService.flowProcessingPipelineFused(Flow.fromArray(dataToProcess));

        Integer[] array = new Integer[times];
        Arrays.fill(array, 777);

        simpleNotFused = Flow.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> i.toString())
            .map(s -> Integer.parseInt(s) + 1)
            .map(i -> i.toString())
            .map(s -> Integer.parseInt(s) + 3)
            .map(i -> "[" + i + "]");

        simpleFused = Flow.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> {
                String s1 = i.toString();
                Integer i1 = Integer.parseInt(s1) + 1;
                String s2 = i1.toString();
                Integer i2 = Integer.parseInt(s2) + 3;
                return "[" + i2 + "]";
            });
    }

    // --- Order Processing ----------------------------------------------------

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

    // --- Integers ------------------------------------------------------------

    @Benchmark
    public Object fusedInteger(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        simpleFused.subscribe(lo);
        return lo;
    }

    @Benchmark
    public Object notFusedInteger(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        simpleNotFused.subscribe(lo);
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
