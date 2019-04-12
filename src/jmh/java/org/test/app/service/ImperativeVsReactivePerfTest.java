package org.test.app.service;

import java.util.Arrays;
import java.util.Spliterator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
@Warmup(iterations = 5, time = 180)
@Measurement(iterations = 20, time = 30)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
public class ImperativeVsReactivePerfTest {
    @Param({ "10", "1000000" })
    public int times;

    Flux<OrderTotalWithDiscount> flow;
    OrderProcessingService orderProcessingService;
    OrderRequest[] orderRequests;
    Spliterator<OrderRequest> orderRequestSpliterator;

    @Setup
    public void setup() {
        final OrderRequest order = new OrderRequest(
            "order-1",
            asList(
                new ProductPackage(new Product("p-1", "Milk 1L", UAH, 27.00), 2),
                new ProductPackage(new Product("p-2", "Bread", CAD, 1.01), 3),
                new ProductPackage(new Product("p-3", "Butter Selianske", EUR, 2.00), 1)
            ),
            UAH
        );
        final CurrencyService currencyService = new CurrencyService();
        final OrderRequest[] array = new OrderRequest[times];

        Arrays.fill(array, order);

        orderRequests = array;
        orderRequestSpliterator = Arrays.spliterator(array);
        orderProcessingService = new OrderProcessingService(currencyService);
        flow = orderProcessingService.process(Flux.fromArray(array));
    }

    @Benchmark
    public void imperativePerformance(Blackhole bh) {
        final OrderProcessingService orderProcessingService = this.orderProcessingService;
        final OrderRequest[] orderRequests = this.orderRequests;
        final int size = times;

        for (int i = 0; i < size; i++) {
            bh.consume(orderProcessingService.imperativeProcessing(orderRequests[i]));
        }
    }

    @Benchmark
    public Object javaStreamPerformance(Blackhole bh) {
        final Stream<OrderTotalWithDiscount> stream = orderProcessingService.javaStreamsProcessing(
            StreamSupport.stream(orderRequestSpliterator, false));

        stream.forEach(bh::consume);

        return stream;
    }

    @Benchmark
    public Object reactiveSlowPathPerformance(Blackhole bh) {
        final SlowPerfSubscriber lo = new SlowPerfSubscriber(bh);

        flow.subscribe(lo);

        return lo;
    }

    @Benchmark
    public Object reactiveFastPathPerformance(Blackhole bh) {
        final FastPerfSubscriber lo = new FastPerfSubscriber(bh);

        flow.subscribe(lo);

        return lo;
    }

//
//    public static void main(String[] args) throws IOException, RunnerException {
//        org.openjdk.jmh.Main.main(args);
//    }
}
