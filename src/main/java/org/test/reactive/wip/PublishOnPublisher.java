package org.test.reactive.wip;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.test.reactive.Flow;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Supplier;

// TODO: Implement the publisher
public class PublishOnPublisher<T> extends Flow<T> {

    final Publisher<T> parent;
    final Scheduler scheduler;
    final int prefetch;

    final Supplier<? extends Queue<T>> queueSupplier = ConcurrentLinkedQueue::new;

    public PublishOnPublisher(Publisher<T> parent, Scheduler scheduler, int prefetch) {
        this.parent = parent;
        this.scheduler = scheduler;
        this.prefetch = prefetch;
    }

    @Override
    public void subscribe(Subscriber<? super T> actual) {
        Scheduler.Worker worker = scheduler.createWorker();
        parent.subscribe(new PublishOnOperator<>(actual, worker, prefetch, queueSupplier));
    }

    private final static class PublishOnOperator<T> implements Subscriber<T>, Subscription {

        private final Subscriber<? super T> inner;
        private final Scheduler.Worker worker;
        private final int prefetch;
        private final Supplier<? extends Queue<T>> queueSupplier;

        Subscription s;

        Queue<T> queue;

        volatile boolean cancelled;

        volatile boolean done;

        Throwable error;

        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<PublishOnOperator> WIP =
            AtomicIntegerFieldUpdater.newUpdater(PublishOnOperator.class, "wip");

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublishOnOperator> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(PublishOnOperator.class, "requested");

        private PublishOnOperator(
            Subscriber<? super T> inner,
            Scheduler.Worker worker,
            int prefetch,
            Supplier<? extends Queue<T>> queueSupplier
        ) {
            this.inner = inner;
            this.worker = worker;
            this.prefetch = prefetch;
            this.queueSupplier = queueSupplier;
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.s = s;
            this.queue = queueSupplier.get();

            this.inner.onSubscribe(this);

        }

        @Override
        public void onNext(T in) {
            Subscriber<? super T> inner = this.inner;

            inner.onNext(in);
        }

        @Override
        public void onError(Throwable t) {
            inner.onError(t);
        }

        @Override
        public void onComplete() {
            inner.onComplete();
        }

        @Override
        public void request(long n) {

        }

        @Override
        public void cancel() {

        }
    }
}
