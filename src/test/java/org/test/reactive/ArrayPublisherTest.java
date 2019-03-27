package org.test.reactive;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.assertj.core.api.Assertions;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.testng.annotations.Test;

public class ArrayPublisherTest {

    @Test
    public void signalsShouldBeEmittedInTheRightOrder() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ArrayList<Long> collected = new ArrayList<>();
        ArrayList<Integer> order = new ArrayList<>();
        long toRequest = 5L;
        Long[] array = generate(toRequest);
        ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

        publisher.subscribe(new Subscriber<Long>() {
            @Override
            public void onSubscribe(Subscription s) {
                order.add(0);
                s.request(toRequest);
            }

            @Override
            public void onNext(Long aLong) {
                collected.add(aLong);

                if (!order.contains(1)) {
                    order.add(1);
                }
            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onComplete() {
                order.add(2);
                latch.countDown();
            }
        });

        latch.await(1, TimeUnit.SECONDS);

        Assertions.assertThat(order).containsExactly(0, 1, 2);
        Assertions.assertThat(collected).containsExactly(array);
    }

    static Long[] generate(long num) {
        return LongStream.range(0, num >= Integer.MAX_VALUE ? 1000000 : num)
                         .boxed()
                         .toArray(Long[]::new);
    }
}