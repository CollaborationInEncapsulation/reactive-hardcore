package org.test.playground;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class PublishOnSample {

    public static void main(String[] args) {
        Flux.range(1, 500)
            .hide()
            .publishOn(Schedulers.parallel())
            .blockLast();
    }
}
