package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

public class WrapPublisher<T> extends Flow<T> {
    private final Publisher<? extends T> source;

    public WrapPublisher(Publisher<? extends T> source) {
        this.source = source;
    }

    @Override
    public void subscribe(Subscriber<? super T> actual) {
        source.subscribe(actual);
    }
}
