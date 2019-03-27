package org.test.reactive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.Assert;
import org.testng.annotations.Test;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;

public class ArrayPublisherTest {

    @Test
    public void signalsShouldBeEmittedInTheRightOrder() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ArrayList<Long> collected = new ArrayList<>();
        ArrayList<Integer> order = new ArrayList<>();
        long toRequest = 5L;
        Long[] array = generate(toRequest);
        ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

        publisher.subscribe(new Subscriber<>() {
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

        Assert.assertEquals(order, Arrays.asList(0, 1, 2));
        Assert.assertEquals(collected, Arrays.asList(array));
    }

    @Test
    public void mustSupportBackpressureControl() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ArrayList<Long> collected = new ArrayList<>();
        long toRequest = 5L;
        Long[] array = generate(toRequest);
        ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);
        Subscription[] subscription = new Subscription[1];

        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                subscription[0] = s;
            }

            @Override
            public void onNext(Long aLong) {
                collected.add(aLong);
            }

            @Override
            public void onError(Throwable t) {

            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });


        assertEquals(collected, Collections.emptyList());

        subscription[0].request(1);
        assertEquals(collected, asList(0L));

        subscription[0].request(1);
        assertEquals(collected, asList(0L, 1L));

        subscription[0].request(2);
        assertEquals(collected, asList(0L, 1L, 2L, 3L));

        subscription[0].request(20);

        latch.await(1, SECONDS);

        assertEquals(collected, asList(array));
    }

    @Test
    public void mustSendNPENormally() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Long[] array = new Long[] { null };
        AtomicReference<Throwable> error = new AtomicReference<>();
        ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(4);
            }

            @Override
            public void onNext(Long aLong) {
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }

            @Override
            public void onComplete() {
            }
        });

        latch.await(1, SECONDS);

        Assert.assertTrue(error.get() instanceof NullPointerException);
    }


    static Long[] generate(long num) {
        return LongStream.range(0, num >= Integer.MAX_VALUE ? 1000000 : num)
                         .boxed()
                         .toArray(Long[]::new);
    }
}