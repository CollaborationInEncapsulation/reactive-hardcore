package org.test.reactive;

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

@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 2)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
public class ArrayPublisherPerfTest {
    @Param({ "2", "1000000" })
    public int times;

    UnoptimizedArrayPublisher<Integer> unoptimizedArrayPublisher;
    ArrayPublisher<Integer> arrayPublisher;

    @Setup
    public void setup() {
        Integer[] array = new Integer[times];
        Arrays.fill(array, 777);
        unoptimizedArrayPublisher = new UnoptimizedArrayPublisher<>(array);
        arrayPublisher = new ArrayPublisher<>(array);
    }

    @Benchmark
    public Object publisherPerformance(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);

        arrayPublisher.subscribe(lo);

        return lo;
    }

    @Benchmark
    public Object unoptimizedPublisherPerformance(Blackhole bh) {
        PerfSubscriber lo = new PerfSubscriber(bh);

        unoptimizedArrayPublisher.subscribe(lo);

        return lo;
    }

    public static void main(String[] args) throws IOException, RunnerException {
        org.openjdk.jmh.Main.main(args);
    }
}
