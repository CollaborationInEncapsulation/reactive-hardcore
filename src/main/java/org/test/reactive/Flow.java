package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A simple reactive type with a primitive DSL, similar to Reactor'subscription Flux type.
 *
 * @param <T> type of elements in stream
 */
public abstract class Flow<T> implements Publisher<T> {

    // --- Static Generators ---------------------------------------------------

    public static <T> Flow<T> fromArray(T[] array) {
        return new ArrayPublisher<>(array);
    }

    public static <T> Flow<T> fromPublisher(Publisher<T> publisher) {
        return new WrapPublisher<>(publisher);
    }

    // --- Flow Transformations ------------------------------------------------

    public Flow<T> filter(Predicate<T> filter) {
        return new FilterPublisher<>(this, filter);
    }

    public <R> Flow<R> map(Function<T, R> mapper) {
        return new MapPublisher<>(this, mapper);
    }

    public Flow<T> take(long n) {
        return new TakePublisher<>(this, n);
    }

    public Flow<T> publishOn(String threadName, int prefetch) {
        return new PublishOnPublisher<>(this, threadName, prefetch);
    }

    // --- Subscribing to Events -----------------------------------------------
    public final Disposable subscribe() {
        FlowLambdaSubscriber<Object> flowLambdaSubscriber = new FlowLambdaSubscriber<>(
            null,
            null,
            null,
            null
        );
        this.subscribe(flowLambdaSubscriber);
        return flowLambdaSubscriber;
    }


    public final Disposable subscribe(
        Consumer<? super T> consumer
    ) {
        FlowLambdaSubscriber<? super T> flowLambdaSubscriber = new FlowLambdaSubscriber<>(
            consumer,
            null,
            null,
            null
        );
        this.subscribe(flowLambdaSubscriber);
        return flowLambdaSubscriber;
    }

    public final Disposable subscribe(
        Consumer<? super T> consumer,
        Consumer<? super Throwable> errorConsumer
    ) {
        FlowLambdaSubscriber<? super T> flowLambdaSubscriber = new FlowLambdaSubscriber<>(
            consumer,
            errorConsumer,
            null,
            null
        );
        this.subscribe(flowLambdaSubscriber);
        return flowLambdaSubscriber;
    }

    public final Disposable subscribe(
        Consumer<? super T> consumer,
        Consumer<? super Throwable> errorConsumer,
        Runnable completeConsumer
    ) {
        FlowLambdaSubscriber<? super T> flowLambdaSubscriber = new FlowLambdaSubscriber<>(
            consumer,
            errorConsumer,
            completeConsumer,
            null
        );
        this.subscribe(flowLambdaSubscriber);
        return flowLambdaSubscriber;
    }

    public final Disposable subscribe(
        Consumer<? super T> consumer,
        Consumer<? super Throwable> errorConsumer,
        Runnable completeConsumer,
        Consumer<? super Subscription> subscriptionConsumer
    ) {
        FlowLambdaSubscriber<? super T> flowLambdaSubscriber = new FlowLambdaSubscriber<>(
            consumer,
            errorConsumer,
            completeConsumer,
            subscriptionConsumer
        );
        this.subscribe(flowLambdaSubscriber);
        return flowLambdaSubscriber;
    }

}
