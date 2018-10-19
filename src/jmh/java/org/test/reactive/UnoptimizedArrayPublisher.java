package org.test.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class UnoptimizedArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public UnoptimizedArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            AtomicInteger index = new AtomicInteger();
            AtomicLong requested = new AtomicLong();
            AtomicBoolean canceled = new AtomicBoolean();

            @Override
            public void request(long n) {

                long initialRequested;
                long newValue;

                for (; ; ) {
                    initialRequested = requested.get();

                    if (initialRequested == Long.MAX_VALUE) {
                        return;
                    }

                    newValue = initialRequested + n;

                    if (newValue <= 0) {
                        newValue = Long.MAX_VALUE;
                    }

                    if (requested.compareAndSet(initialRequested, newValue)) {
                        break;
                    }
                }

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                while (true) {

                    for (;
                         sent < requested.get() && index.get() < array.length;
                         sent++, index.incrementAndGet()) {

                        if (canceled.get()) {
                            return;
                        }

                        T nextElement = array[index.get()];

                        if (nextElement == null) {
                            subscriber.onError(new NullPointerException());

                            return;
                        }

                        subscriber.onNext(nextElement);
                    }

                    if (canceled.get()) {
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
                canceled.set(true);
            }
        });
    }
}
