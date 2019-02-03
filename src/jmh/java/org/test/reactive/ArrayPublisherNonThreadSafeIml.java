package org.test.reactive;

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
public class ArrayPublisherNonThreadSafeIml<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisherNonThreadSafeIml(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;
            long requested;
            boolean cancelled;

            @Override
            public void request(long n) {
                if (n <= 0 && !cancelled) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException(
                            "§3.9 violated: positive request amount required but it was " + n));
                    return;
                }


                long initialRequested = requested;

                requested += n;

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                for (; sent < requested && index < array.length; sent++, index++) {
                    if (cancelled) {
                        return;
                    }

                    T element = array[index];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (cancelled) {
                    return;
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }

                requested -= sent;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
