package org.test.playground;

import java.util.Arrays;
import java.util.function.Function;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class ArraySample {

    public static void main(String[] args) {
//        Arrays.stream(new Integer[] {1, 2, 3, 4, 5})
//              .forEach(System.out::println);

        Flux.fromArray(new Integer[] {1, 2, 3, 4, 5})
            .map(Function.identity())
            .map(Function.identity())
            .map(Function.identity())
            .map(Function.identity())
            .map(Function.identity())
            .filter(__ -> true)
            .subscribe();
    }
}
