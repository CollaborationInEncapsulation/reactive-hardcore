package org.test.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
        subscriber.onSubscribe(new Subscription() {
            int index;
            AtomicLong requested = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void request(long n) {
                if (n <= 0 && !cancelled.get()) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException(
                        "§3.9 violated: positive request amount required but it was " + n
                    ));
                    return;
                }

                long initialRequested;

                do {
                    initialRequested = requested.get();

                    if (initialRequested == Long.MAX_VALUE) {
                        return;
                    }

                    n = initialRequested + n;

                    if (n <= 0) {
                        n = Long.MAX_VALUE;
                    }

                } while (!requested.compareAndSet(initialRequested, n));

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                while (true) {
                    for (; sent < requested.get() && index < array.length; sent++, index++) {
                        if (cancelled.get()) {
                            return;
                        }

                        T element = array[index];

                        if (element == null) {
                            subscriber.onError(new NullPointerException());
                            return;
                        }

                        subscriber.onNext(element);
                    }

                    if (cancelled.get()) {
                        return;
                    }

                    if (index == array.length) {
                        subscriber.onComplete();
                        return;
                    }

                    if (requested.addAndGet(-sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }
}
