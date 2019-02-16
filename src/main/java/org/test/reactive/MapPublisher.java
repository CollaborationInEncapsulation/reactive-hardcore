package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.function.Function;

public class MapPublisher<IN, OUT> extends Flow<OUT> {

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

        private boolean done = false;

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
            if (done) {
                return;
            }

            Subscriber<? super OUT> inner = this.inner;

            OUT result;
            try {
                result = mapper.apply(in);
            } catch (Exception e) {
                onError(e);
                return;
            }

            inner.onNext(result);
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
