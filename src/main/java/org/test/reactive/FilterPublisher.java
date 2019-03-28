package org.test.reactive;

import java.util.function.Predicate;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class FilterPublisher<T> implements Publisher<T> {

    final Publisher<? extends T> source;
    final Predicate<? super T> filter;

    public FilterPublisher(Publisher<? extends T> source,
        Predicate<? super T> filter) {

        this.source = source;
        this.filter = filter;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new FilterOperator<>(s, filter));
    }

    private static final class FilterOperator<T> implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;
        final Predicate<? super T>  filter;

        Subscription s;
        boolean done;

        private FilterOperator(
            Subscriber<? super T> actual,
            Predicate<? super T> filter
        ) {
            this.actual = actual;
            this.filter = filter;
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.s = s;
            actual.onSubscribe(this);
        }

        @Override
        public void onNext(T element) {
            if (done) {
                return;
            }

            boolean result;

            try {
                result = filter.test(element);
            } catch (Throwable t) {
                s.cancel();
                onError(t);
                return;
            }

            if (result) {
                actual.onNext(element);
            }
            else {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                return;
            }

            done = true;
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }

            done = true;
            actual.onComplete();
        }

        @Override
        public void request(long n) {
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
