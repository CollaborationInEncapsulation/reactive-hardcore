package org.test.reactive;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Consumer;

final class FlowLambdaSubscriber<T> implements Subscriber<T>, Disposable {

    final Consumer<? super T> consumer;
    final Consumer<? super Throwable> errorConsumer;
    final Runnable completeConsumer;
    final Consumer<? super Subscription> subscriptionConsumer;

    volatile Subscription subscription;

    static final AtomicReferenceFieldUpdater<FlowLambdaSubscriber, Subscription> S =
        AtomicReferenceFieldUpdater.newUpdater(
            FlowLambdaSubscriber.class,
            Subscription.class,
            "subscription");

    public FlowLambdaSubscriber(
        Consumer<? super T> consumer,
        Consumer<? super Throwable> errorConsumer,
        Runnable completeConsumer,
        Consumer<? super org.reactivestreams.Subscription> subscriptionConsumer
    ) {
        this.consumer = consumer;
        this.errorConsumer = errorConsumer;
        this.completeConsumer = completeConsumer;
        this.subscriptionConsumer = subscriptionConsumer;
    }

    // --- Subscriber Contract -------------------------------------------------

    @Override
    public void onSubscribe(Subscription s) {
        this.subscription = s;
        if (subscriptionConsumer != null) {
            try {
                subscriptionConsumer.accept(s);
            }
            catch (Throwable t) {
                Exceptions.throwIfFatal(t);
                s.cancel();
                onError(t);
            }
        }
        else {
            s.request(Long.MAX_VALUE);
        }

    }

    @Override
    public void onNext(T t) {
        try {
            if (consumer != null) {
                consumer.accept(t);
            }
        }
        catch (Throwable ex) {
            this.subscription.cancel();
            onError(ex);
        }
    }

    @Override
    public void onError(Throwable t) {
        Subscription prevSubscription = S.getAndSet(this, CancelledSubscription.INSTANCE);
        if (prevSubscription == CancelledSubscription.INSTANCE) {
            // do nothing, already unsubscribed
            return;
        }
        if (errorConsumer != null) {
            errorConsumer.accept(t);
        }
        else {
            throw new UnsupportedOperationException("Missing onError() handle", t);
        }

    }

    @Override
    public void onComplete() {
        Subscription prevSubscription = S.getAndSet(this, CancelledSubscription.INSTANCE);
        if (prevSubscription == CancelledSubscription.INSTANCE) {
            // do nothing, already unsubscribed
            return;
        }

        if (completeConsumer != null) {
            try {
                completeConsumer.run();
            }
            catch (Throwable t) {
                onError(t);
            }
        }
    }

    // --- Disposable Contract -------------------------------------------------

    @Override
    public boolean isDisposed() {
        return this.subscription == CancelledSubscription.INSTANCE;
    }

    @Override
    public void dispose() {
        Subscription prevSubscription = S.getAndSet(this, CancelledSubscription.INSTANCE);
        if (prevSubscription != null && prevSubscription != CancelledSubscription.INSTANCE) {
            prevSubscription.cancel();
        }
    }

    final static class CancelledSubscription implements Subscription {
        static final CancelledSubscription INSTANCE = new CancelledSubscription();

        @Override
        public void cancel() {
            // deliberately no op
        }

        @Override
        public void request(long n) {
            // deliberately no op
        }
    }
}
