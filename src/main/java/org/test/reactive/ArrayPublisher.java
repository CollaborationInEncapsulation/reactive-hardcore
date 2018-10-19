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
        subscriber.onSubscribe(new ArraySubscription<>(array, subscriber));

    }

    private static class ArraySubscription<T> implements Subscription {

        private final Subscriber<? super T> subscriber;

        private final T[] array;

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
            long newValue;

            for (;;) {
                initialRequested = REQUESTED.get(this);

                if (initialRequested == Long.MAX_VALUE) {
                    return;
                }

                newValue = initialRequested + n;

                if (newValue <= 0) {
                    newValue = Long.MAX_VALUE;
                }

                if (REQUESTED.compareAndSet(this, initialRequested, newValue)) {
                    break;
                }
            }

            if (initialRequested > 0) {
                return;
            }

            if (newValue > (array.length - index)) {
                fastPath();
            } else {
                slowPath(newValue);
            }

        }

        void slowPath(long newValue) {
            int sent = 0;
            int idx = this.index;
            T[] array = this.array;
            int length = array.length;
            Subscriber<? super T> subscriber = this.subscriber;

            while (true) {
                for (; sent < newValue && idx < length; sent++, idx++) {
                    if (canceled) {
                        return;
                    }

                    T element = array[idx];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (canceled) {
                    return;
                }

                if (idx == length) {
                    subscriber.onComplete();
                    return;
                }

                newValue = requested;

                if (newValue == sent) {
                    index = idx;
                    newValue = REQUESTED.addAndGet(this, -sent);
                    if (newValue == 0) {
                        return;
                    }
                }

                sent = 0;
            }
        }

        void fastPath() {
            int idx = this.index;
            T[] array = this.array;
            int length = array.length;
            Subscriber<? super T> subscriber = this.subscriber;

            for (; idx < length; idx++) {
                if (canceled) {
                    return;
                }

                T element = array[idx];

                if (element == null) {
                    subscriber.onError(new NullPointerException());
                    return;
                }

                subscriber.onNext(element);
            }

            if (canceled) {
                return;
            }

            if (idx == length) {
                subscriber.onComplete();
                return;
            }
        }

        @Override
        public void cancel() {
            canceled = true;
        }
    }
}
