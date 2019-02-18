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
import org.test.reactive.Flow;
import org.test.reactive.PerfSubscriber;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5)
@Measurement(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2)
@State(Scope.Thread)
public class FlowFusedNotFusedPerfTest {
    @Param({ "5" })
    public int times;

    Flow<String> flowNotFused;
    Flow<String> flowFused;
    Flow<String> flowManuallyOptimized;

    Flux<String> fluxNotFused;
    Flux<String> fluxFused;
    Flux<String> fluxManuallyOptimized;

    @Setup
    public void setup() {
        Integer[] array = new Integer[times];
        Arrays.fill(array, 777);

        flowNotFused = Flow.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> i.toString())
            .map(s -> Integer.parseInt(s) + 1)
            .map(i -> i.toString())
            .map(s -> Integer.parseInt(s) + 3)
            .map(i -> "[" + i + "]");

        flowFused = Flow.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> {
                String s1 = i.toString();
                Integer i1 = Integer.parseInt(s1) + 1;
                String s2 = i1.toString();
                Integer i2 = Integer.parseInt(s2) + 3;
                return "[" + i2 + "]";
            });

        flowManuallyOptimized = Flow.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> "[" + (i + 4) + "]");

        fluxNotFused = Flux.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> i.toString())
            .map(s -> Integer.parseInt(s) + 1)
            .map(i -> i.toString())
            .map(s -> Integer.parseInt(s) + 3)
            .map(i -> "[" + i + "]");

        fluxFused = Flux.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> {
                String s1 = i.toString();
                Integer i1 = Integer.parseInt(s1) + 1;
                String s2 = i1.toString();
                Integer i2 = Integer.parseInt(s2) + 3;
                return "[" + i2 + "]";
            });

        fluxManuallyOptimized = Flux.fromArray(array)
            .filter(i -> i != 500)
            .map(i -> "[" + (i + 4) + "]");
    }

    // --- Integers ------------------------------------------------------------

    @Benchmark
    public Object flowFusedInteger(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        flowFused.subscribe(lo);
        return lo;
    }

    @Benchmark
    public Object flowNotFusedInteger(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        flowNotFused.subscribe(lo);
        return lo;
    }

    @Benchmark
    public Object flowManuallyOptimized(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        flowManuallyOptimized.subscribe(lo);
        return lo;
    }

    @Benchmark
    public Object fluxFusedInteger(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        fluxFused.subscribe(lo);
        return lo;
    }

    @Benchmark
    public Object fluxNotFusedInteger(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        fluxNotFused.subscribe(lo);
        return lo;
    }

    @Benchmark
    public Object fluxManuallyOptimized(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);
        fluxManuallyOptimized.subscribe(lo);
        return lo;
    }

    public static void main(String[] args) throws IOException, RunnerException {
        org.openjdk.jmh.Main.main(args);
    }
}
