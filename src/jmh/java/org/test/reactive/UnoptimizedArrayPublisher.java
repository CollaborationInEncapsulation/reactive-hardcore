package org.test.reactive;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

//public class UnoptimizedArrayPublisher<T> implements Publisher<T> {
//
//    private final T[] array;
//
//    public UnoptimizedArrayPublisher(T[] array) {
//        this.array = array;
//    }
//
//    @Override
//    public void subscribe(Subscriber<? super T> subscriber) {
//        subscriber.onSubscribe(new Subscription() {
//            AtomicInteger index = new AtomicInteger();
//            AtomicLong requested = new AtomicLong();
//            AtomicBoolean canceled = new AtomicBoolean();
//
//            @Override
//            public void request(long n) {
//                if (n <= 0 && !canceled.get()) {
//                    cancel();
//                    subscriber.onError(new IllegalArgumentException(
//                        "§3.9 violated: positive request amount required but it was " + n
//                    ));
//                    return;
//                }
//
//                long initialRequested;
//
//                do {
//                    initialRequested = requested.get();
//
//                    if (initialRequested == Long.MAX_VALUE) {
//                        return;
//                    }
//
//                    n = initialRequested + n;
//
//                    if (n <= 0) {
//                        n = Long.MAX_VALUE;
//                    }
//
//                } while (!requested.compareAndSet(initialRequested, n));
//
//                if (initialRequested > 0) {
//                    return;
//                }
//
//                int sent = 0;
//
//                while (true) {
//
//                    for (;
//                         sent < requested.get() && index.get() < array.length;
//                         sent++, index.incrementAndGet()) {
//
//                        if (canceled.get()) {
//                            return;
//                        }
//
//                        T nextElement = array[index.get()];
//
//                        if (nextElement == null) {
//                            subscriber.onError(new NullPointerException());
//
//                            return;
//                        }
//
//                        subscriber.onNext(nextElement);
//                    }
//
//                    if (canceled.get()) {
//                        return;
//                    }
//
//                    if (index.get() == array.length) {
//                        subscriber.onComplete();
//                        return;
//                    }
//
//                    if (requested.addAndGet(-sent) == 0) {
//                        return;
//                    }
//
//                    sent = 0;
//                }
//            }
//
//            @Override
//            public void cancel() {
//                canceled.set(true);
//            }
//        });
//    }
//}
