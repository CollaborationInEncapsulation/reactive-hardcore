package org.test.playground;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class FlatMapSample {

    public static void main(String[] args) {

        for (int _i = 0; _i < 10; _i++) {
            Flux.range(1, 100)
                .hide()
                .flatMap(i -> Flux.range(i, i * 100)
                                  .subscribeOn(Schedulers.parallel())
                                  .hide())
                .blockLast();
        }

        long start = System.nanoTime();

        Flux.range(1, 1000)
            .hide()
            .flatMap(i -> Flux.range(i, i * 100)
                              .subscribeOn(Schedulers.parallel())
                              .hide())
            .blockLast();

        System.out.println("Execution took: " + (System.nanoTime() - start) / 1000000);
    }
}
