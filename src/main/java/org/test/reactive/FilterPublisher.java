package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.function.Predicate;

public class FilterPublisher<T> extends Flow<T> {

    private final Publisher<T> parent;
    private final Predicate<T> filter;

    public FilterPublisher(Publisher<T> parent, Predicate<T> filter) {
        this.parent = parent;
        this.filter = filter;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        parent.subscribe(
            new FilterOperator<>(s, filter)
        );
    }

    private final static class FilterOperator<T> implements Subscriber<T> {

        private final Subscriber<? super T> inner;
        private final Predicate<T> filter;

        private boolean done = false;

        private Subscription subscription;

        private FilterOperator(
            Subscriber<? super T> inner,
            Predicate<T> filter
        ) {
            this.inner = inner;
            this.filter = filter;
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;
            inner.onSubscribe(s);
        }

        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }

            Subscription s = this.subscription;
            Subscriber<? super T> inner = this.inner;

            boolean valid;
            try {
                 valid = filter.test(t);
            } catch (Exception ex) {
                subscription.cancel();
                onError(ex);
                return;
            }

            if (valid) {
                inner.onNext(t);
            } else {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                return;
            }
            done = true;
            inner.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            inner.onComplete();
        }
    }
}
