package org.test.reactive;

import java.util.function.Function;
import java.util.function.Predicate;

import org.reactivestreams.Publisher;

public abstract class Flow<T> implements Publisher<T> {

    public static <T> Flow<T> just(T... elements) {
        return fromArray(elements);
    }

    public static <T> Flow<T> fromArray(T[] array) {
        return new ArrayPublisher<T>(array);
    }

    public <R> Flow<R> map(Function<T, R> mapper) {
        return new MapPublisher<T, R>(this, mapper);
    }

    public Flow<T> filter(Predicate<T> predicate) {
        return new FilterPublisher<T>(this, predicate);
    }

    public Flow<T> take(long take) {
        return new TakePublisher<T>(this, take);
    }
}
