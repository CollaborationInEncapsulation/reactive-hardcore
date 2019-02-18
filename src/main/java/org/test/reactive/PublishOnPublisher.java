package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * Create Publisher that sends elements of a given array to each new subscriber
 * <p>
 * Acceptance Criteria: As a developer
 * I want to subscribe to the ArrayPublisher
 * So by doing that, receive elements of that publisher
 *
 * @param <T>
 */
public class PublishOnPublisher<T> extends Flow<T> {

    private final Publisher<T> parent;
    private final int prefetch;
    private final String threadName;

    public PublishOnPublisher(Publisher<T> parent, String threadName, int prefetch) {
        this.parent = parent;
        this.prefetch = prefetch;
        this.threadName = threadName;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        parent.subscribe(new PublishOnInner<>(
            Executors.newSingleThreadExecutor(
                r -> {
                    Thread thread = new Thread(r);
                    thread.setName(threadName);
                    thread.setDaemon(true);
                    return thread;
                }
            ),
            new ConcurrentLinkedQueue<>(),
            prefetch,
            subscriber
        ));

    }

    private static class PublishOnInner<T> implements Subscriber<T>, Subscription, Runnable {

        private final ExecutorService executorService;
        private final Queue<T> queue;
        private final int prefetch;

        private final Subscriber<? super T> subscriber;

        volatile boolean canceled;

        volatile long requested;
        static final AtomicLongFieldUpdater<PublishOnInner> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublishOnInner.class, "requested");


        volatile int wip;
        static final AtomicIntegerFieldUpdater<PublishOnInner> WIP =
            AtomicIntegerFieldUpdater.newUpdater(PublishOnInner.class, "wip");

        private volatile boolean done;
        private Throwable throwable;
        private Subscription subscription;

        public PublishOnInner(ExecutorService executorService, Queue<T> queue, int prefetch, Subscriber<? super T> subscriber) {
            this.executorService = executorService;
            this.queue = queue;
            this.prefetch = prefetch;
            this.subscriber = subscriber;
        }


        @Override
        public void request(long n) {

            if (n <= 0) {
                return;
            }

            long initialRequested;

            do {
                initialRequested = requested;

                if (initialRequested == Long.MAX_VALUE) {
                    return;
                }

                n = initialRequested + n;

                if (n <= 0) {
                    n = Long.MAX_VALUE;
                }

            } while (!REQUESTED.weakCompareAndSet(this, initialRequested, n));

            trySchedule();
        }

        void trySchedule() {
            if (WIP.getAndIncrement(this) > 0) {
                return;
            }

            executorService.execute(this);
        }

        @Override
        public void run() {
            drainMyQueue(requested);
        }

        void drainMyQueue(long n) {
            int inProgress = 1;
            int sent = 0;
            Queue<T> q = queue;
            Subscriber<? super T> subscriber = this.subscriber;

            while (true) {
                while (true) {
                    boolean empty = false;
                    for (; sent < n; sent++) {
                        if (canceled) {
                            return;
                        }

                        T element = q.poll();

                        empty = element == null;

                        if (empty) {
                            break;
                        }

                        subscriber.onNext(element);
                    }

                    if (canceled) {
                        return;
                    }

                    if (q.isEmpty() && done) {
                        if (throwable != null) {
                            subscriber.onError(throwable);
                        } else {
                            subscriber.onComplete();
                        }
                        return;
                    }

                    n = requested;

                    if (empty) {
                        if (sent > 0) {
                            REQUESTED.addAndGet(this, -sent);
                            subscription.request(sent);
                            break;
                        }
                    }

                    if (n == sent) {
                        n = REQUESTED.addAndGet(this, -sent);
                        if (n == 0) {
                            if(sent > 0) {
                                subscription.request(sent);
                            }
                            break;
                        }
                        sent = 0;
                    }
                }

                inProgress = WIP.addAndGet(this, -inProgress);
                if (inProgress == 0) {
                    return;
                }
            }
        }

        @Override
        public void cancel() {
            canceled = true;
            subscription.cancel();
        }

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
            s.request(prefetch);
            subscriber.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            queue.offer(t);

            trySchedule();
        }

        @Override
        public void onError(Throwable t) {
            throwable = t;
            done = true;

            trySchedule();
        }

        @Override
        public void onComplete() {
            done = true;

            trySchedule();
        }
    }
}
