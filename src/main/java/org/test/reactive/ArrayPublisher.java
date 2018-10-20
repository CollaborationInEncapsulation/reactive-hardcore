package org.test.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

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
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new ArraySubscription<T>(array, subscriber));
    }

    private static class ArraySubscription<T> implements Subscription {

        private final T[] array;
        private final Subscriber<? super T> subscriber;
        int index;

        volatile boolean canceled;

        volatile long requested;
        static final AtomicLongFieldUpdater<ArraySubscription> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(ArraySubscription.class, "requested");

        public ArraySubscription(T[] array, Subscriber<? super T> subscriber) {
            this.array = array;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {

            long initialRequested;

            for (;;) {
                initialRequested = requested;

                if (initialRequested == Long.MAX_VALUE) {
                    return;
                }

                n = initialRequested + n;

                if (n <= 0) {
                    n = Long.MAX_VALUE;
                }

                if (REQUESTED.compareAndSet(this, initialRequested, n)) {
                    break;
                }
            }

            if (initialRequested > 0) {
                return;
            }

            int sent = 0;

            while (true) {

                for (; sent < n && index < array.length; sent++, index++) {
                    if (canceled) {
                        return;
                    }

                    T element = array[index];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (canceled) {
                    return;
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }

                n = requested;

                if (n == sent) {
                    n = REQUESTED.addAndGet(this, -sent);
                    if (n == 0) {
                        return;
                    }
                }

                sent = 0;
            }
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    }
}
