package org.test.playground;

import java.util.function.Function;

import reactor.core.publisher.Flux;

public class ArraySample {

    public static void main(String[] args) {
//        Arrays.stream(new Integer[] {1, 2, 3, 4, 5})
//              .forEach(System.out::println);

        ArraySample sample = new ArraySample();

        for (int i = 0; i < 100000000; i++) {
            sample.doWork();
        }

        System.out.println("Done");
    }


    public void doWork() {
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
