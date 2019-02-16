package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class TakePublisher<T> extends Flow<T> {

    private final Publisher<T> parent;

    final long n;

    public TakePublisher(Publisher<T> parent, long n) {
        this.parent = parent;
        this.n = n;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        parent.subscribe(
            new TakeOperator<>(s, n)
        );
    }

    private final static class TakeOperator<T> implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> inner;

        final long n;

        long remaining;

        Subscription subscription;

        private boolean done = false;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<TakeOperator> WIP =
            AtomicIntegerFieldUpdater.newUpdater(TakeOperator.class, "wip");

        private TakeOperator(
            Subscriber<? super T> inner,
            long n
        ) {
            this.inner = inner;
            this.n = n;
            this.remaining = n;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (n == 0) {
                s.cancel();
                done = true;
                inner.onComplete();
            }
            else {
                this.subscription = s;
                inner.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                // exit
                return;
            }

            long r = remaining;

            if (r == 0) {
                subscription.cancel();
                onComplete();
                return;
            }

            remaining = --r;
            boolean stop = r == 0L;

            inner.onNext(t);

            if (stop) {
                subscription.cancel();
                onComplete();
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                // exit
                return;
            }
            done = true;
            inner.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                // exit
                return;
            }
            done = true;
            inner.onComplete();
        }

        @Override
        public void cancel() {
            subscription.cancel();
        }

        @Override
        public void request(long n) {
            if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
                if (n >= this.n) {
                    subscription.request(Long.MAX_VALUE);
                } else {
                    subscription.request(n);
                }
                return;
            }

            subscription.request(n);
        }
    }
}
