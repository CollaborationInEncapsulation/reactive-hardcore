package org.test.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            AtomicInteger index = new AtomicInteger();
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

                long initialRequested = requested.getAndAdd(n);

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                while (true) {
                    for (; sent < requested.get() && index.get() < array.length; sent++, index.incrementAndGet()) {
                        if (cancelled.get()) {
                            return;
                        }

                        T element = array[index.get()];

                        if (element == null) {
                            subscriber.onError(new NullPointerException());
                            return;
                        }

                        subscriber.onNext(element);
                    }

                    if (cancelled.get()) {
                        return;
                    }

                    if (index.get() == array.length) {
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
