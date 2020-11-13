package org.test.reactive;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Create Publisher that sends elements of a given array to each new subscriber
 * <p>
 * Acceptance Criteria: As a developer
 * I want to subscribe to the ArrayPublisher
 * So by doing that, receive elements of that publisher
 *
 * @param <T>
 */
public class QueuePublisher<T> implements Publisher<T> {

    private final Supplier<Queue<T>> queueSupplier;

    public QueuePublisher(Supplier<Queue<T>> queueSupplier) {
        this.queueSupplier = queueSupplier;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        final Queue<T> queue = queueSupplier.get();
        subscriber.onSubscribe(new QueueSubscription(subscriber, queue));
    }

    public static void main(String[] args) {
        final ArrayDeque<Integer> queue = new ArrayDeque<>(
            IntStream.range(0, 800000).boxed().collect(Collectors.toList()));
        final QueuePublisher<Integer> publisher = new QueuePublisher<>(() -> queue);
        var es = Executors.newFixedThreadPool(1000);

        publisher.subscribe(new Subscriber<>() {

            Subscription s;

            @Override
            public void onSubscribe(Subscription s) {
                this.s = s;
                System.out.println("Received onSubscribe();");
                s.request(10);
            }

            @Override
            public void onNext(Integer value) {
                System.out.println("Received onNext(" + value + ");");
                es.submit(() -> {
//                    System.out.println("Do Work...");
//
//                    LockSupport.parkNanos(100);
//
//                    System.out.println("Work is Done on Element {" +value + "}");
                    s.request(1);
                });
            }

            @Override
            public void onError(Throwable t) {
                System.out.println("Received onError(" + t.getMessage() + ");");
            }

            @Override
            public void onComplete() {
                System.out.println("Received onComplete();");
            }
        });
    }

    private static class QueueSubscription<T> implements Subscription {

        private final Subscriber<? super T> subscriber;
        private final Queue<T> queue;

        volatile long requested; // accumulator == 0
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<QueueSubscription> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(QueueSubscription.class, "requested");

        volatile boolean cancelled;

        public QueueSubscription(Subscriber<? super T> subscriber, Queue<T> queue) {
            this.subscriber = subscriber;
            this.queue = queue;
            cancelled = false;
        }

        @Override
        public void request(long n) {
            if (n <= 0 && !cancelled) {
                this.cancelled = true;
                subscriber.onError(new IllegalArgumentException(
                    "§3.9 violated: positive request amount required but it was " + n
                ));
                return;
            }

            // Integer.MAX_VALUE + Long.MAX_VALUE
            long currentState;
            for (;;) {
                currentState = requested;

                if (currentState == Long.MAX_VALUE) {
                    break;
                }

                long nextState = currentState + n;
                if (nextState <= 0) {
                    nextState = Long.MAX_VALUE;
                }

                if (REQUESTED.compareAndSet(this, currentState, nextState))
                    break;
            }

            if (currentState != 0) {
                // if work is in progress then exit
                return;
            }

            if (n == Long.MAX_VALUE) {
                drainOptimized(n);
            } else {
                drain(n);
            }
        }

        private void drain(long n) {
            long toDeliver = n;

            int sent = 0;
            while (true) { // work stealing alg
                for (; sent < toDeliver && queue.size() != 0; sent++) {
                    if (cancelled) {
                        return;
                    }

                    final T value = queue.poll();

                    if (value == null) {
                        subscriber.onError(new NullPointerException("Received null value"));
                        return;
                    }

                    subscriber.onNext(value);
                }

                if (cancelled) {
                    return;
                }

                if (queue.size() == 0) {
                    subscriber.onComplete();
                    return;
                }

                toDeliver = requested;
                if (toDeliver == sent) {
                    toDeliver = REQUESTED.addAndGet(this, -sent);
                    if (toDeliver == 0) {
                        return;
                    }
                    sent = 0;
                }

                // we have to repeat actions
            }
        }

        private void drainOptimized() {
            for (;;) {
                if (cancelled) {
                    return;
                }

                if (queue.size() == 0) {
                    break;
                }

                final T value = queue.poll();

                if (value == null) {
                    subscriber.onError(new NullPointerException("Received null value"));
                    return;
                }

                subscriber.onNext(value);
            }

            if (cancelled) {
                return;
            }

            subscriber.onComplete();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
