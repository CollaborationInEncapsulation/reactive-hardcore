package org.test.reactive;

import java.util.Objects;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class MapPublisher<T, R> implements Publisher<R> {

    final Publisher<? extends T> source;
    final Function<? super T, ? extends R> mapper;

    public MapPublisher(Publisher<? extends T> source,
        Function<? super T, ? extends R> mapper) {

        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Subscriber<? super R> s) {
        source.subscribe(new MapOperator<>(s, mapper));
    }

    private static final class MapOperator<T, R> implements Subscriber<T>, Subscription {

        final Subscriber<? super R>            actual;
        final Function<? super T, ? extends R> mapper;

        Subscription s;
        boolean done;

        private MapOperator(
            Subscriber<? super R> actual,
            Function<? super T, ? extends R> mapper
        ) {
            this.actual = actual;
            this.mapper = mapper;
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

            R mappedElement;

            try {
                mappedElement = Objects.requireNonNull(mapper.apply(element));
            } catch (Throwable t) {
                s.cancel();
                onError(t);
                return;
            }

            actual.onNext(mappedElement);
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
