package org.test.reactive;

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

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 3)
@Measurement(iterations = 4, time = 4, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
public class ArrayPublisherPerfTest {
    @Param({ "10" })
    public int times;

    ArrayPublisherNonThreadSafeIml<Integer> nonThreadSafeArrayPublisher;
    UnoptimizedArrayPublisher<Integer>      unoptimizedArrayPublisher;
    ArrayPublisher<Integer>                 arrayPublisher;
    Publisher<Integer>                      wrappedPublishers;

    @Setup
    public void setup() {
        Integer[] array = new Integer[times];
        Arrays.fill(array, 777);
        unoptimizedArrayPublisher = new UnoptimizedArrayPublisher<>(array);
        nonThreadSafeArrayPublisher = new ArrayPublisherNonThreadSafeIml<>(array);
        arrayPublisher = new ArrayPublisher<>(array);
        wrappedPublishers = new FilterPublisher<>(
            new MapPublisher<>(
                new FilterPublisher<>(
                    new MapPublisher<>(
                            new ArrayPublisher<>(array),
                            Function.identity()
                    ),
                    (__) -> true
                ),
                Function.identity()
            ),
            (__) -> true
        );
    }

    //@Benchmark
    public Object unoptimizedPublisherPerformance(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);

        unoptimizedArrayPublisher.subscribe(lo);

        return lo;
    }

    //@Benchmark
    public Object nonThreadSafePublisherPerformance(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);

        nonThreadSafeArrayPublisher.subscribe(lo);

        return lo;
    }

    //@Benchmark
    public Object wrappedPublisherPerformance(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);

        wrappedPublishers.subscribe(lo);

        return lo;
    }

    //@Benchmark
    public Object publisherPerformance(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);

        arrayPublisher.subscribe(lo);

        return lo;
    }

    public static void main(String[] args) throws IOException, RunnerException {
        org.openjdk.jmh.Main.main(args);
    }
}
