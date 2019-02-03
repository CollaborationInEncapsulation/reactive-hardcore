package org.test.reactive;

import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class MapPublisher<IN, OUT> implements Publisher<OUT> {

    private final Publisher<IN> parent;
    private final Function<IN, OUT> mapper;

    public MapPublisher(Publisher<IN> parent, Function<IN, OUT> mapper) {
        this.parent = parent;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Subscriber<? super OUT> s) {
        parent.subscribe(
            new MapOperator<>(s, mapper)
        );
    }

    private final static class MapOperator<IN, OUT> implements Subscriber<IN> {

        private final Subscriber<? super OUT> inner;
        private final Function<IN, OUT> mapper;

        private MapOperator(
            Subscriber<? super OUT> inner,
            Function<IN, OUT> mapper
        ) {
            this.inner = inner;
            this.mapper = mapper;
        }

        @Override
        public void onSubscribe(Subscription s) {
            inner.onSubscribe(s);
        }

        @Override
        public void onNext(IN in) {
            Subscriber<? super OUT> inner = this.inner;

            OUT result = mapper.apply(in);

            inner.onNext(result);
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
