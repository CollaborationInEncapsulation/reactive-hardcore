package org.test.reactive;

import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class FilterPublisher<T> implements Publisher<T> {

    private final Publisher<T>      parent;
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
            Subscription s = this.subscription;
            Subscriber<? super T> inner = this.inner;

            boolean valid = filter.test(t);

            if (valid) {
                inner.onNext(t);
            } else {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            inner.onError(t);
        }

        @Override
        public void onComplete() {
            inner.onComplete();
        }
    }
}
