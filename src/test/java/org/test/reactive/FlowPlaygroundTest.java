package org.test.reactive;

import org.reactivestreams.Publisher;
import org.testng.annotations.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class FlowPlaygroundTest {

    @Test
    public void simpleFlowUsage() {
        Flow.fromArray(new Integer[]{ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13 })
            .map(i -> i + 1)
            .map(i -> 13 - i)
            //.map(i -> 13 % i)
            .filter(i -> i % 3 == 0)
            //.filter(i -> 3 % i == 0)
            .map(i -> "id-" + i)
            .map(i -> "[" + i + "]")
            .subscribe(
                s -> System.out.println("onNext: " + s),
                e -> System.out.println("onError: " + e.getMessage()),
                () -> System.out.println("onComplete")
            );
    }

    @Test
    public void adaptingExternalPublisher() throws InterruptedException {
        Publisher<Integer> externalPublisher = Flux
            .range(1, 100)
            .subscribeOn(Schedulers.elastic());

        Flow.fromPublisher(externalPublisher)
            .filter(i -> i % 5 == 0)
            .map(i -> "[" + i + "]")
            .take(10)
            .subscribe(n -> System.out.println(Thread.currentThread().getName() + " | onNext: " + n));

        Thread.sleep(1000);
    }

    @Test
    public void disposingSubscription() throws InterruptedException {
        Disposable[] disposables = new Disposable[1];

        Publisher<Integer> externalPublisher = Flux
            .range(1, 100)
            .subscribeOn(Schedulers.elastic());

        disposables[0] = Flow.fromPublisher(externalPublisher)
            .map(Object::toString)
            .subscribe(
                s -> {
                    System.out.println("onNext: " + s);
                    disposables[0].dispose();
                },
                e -> {
                    System.out.println("onError: " + e.getMessage());
                    e.printStackTrace();
                },
                () -> System.out.println("onComplete"),
                subscription -> subscription.request(1)
            );

        Thread.sleep(1000);
    }
}
