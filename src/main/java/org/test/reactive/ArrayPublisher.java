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
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;

            @Override
            public void request(long n) {
                for (int i = 0; i < n && index < array.length; i++, index++) {
                    subscriber.onNext(array[index]);
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }
            }

            @Override
            public void cancel() {

            }
        });
    }
}
