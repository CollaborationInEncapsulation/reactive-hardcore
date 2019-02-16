package org.test.reactive;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Create Publisher that sends elements of a given queue to each new subscriber
 * <p>
 * Acceptance Criteria: As a developer
 * I want to subscribe to the ArrayPublisher
 * So by doing that, receive elements of that publisher
 *
 * @param <T>
 */
public class PublishOnPublisher<T> implements Publisher<T> {

    private final Publisher<T> parent;
    private final int prefetch;

    public PublishOnPublisher(Publisher<T> parent, int prefetch) {

        this.parent = parent;
        this.prefetch = prefetch;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        parent.subscribe(new PublishOnSubscription<>(new ConcurrentLinkedQueue<>(), subscriber, Executors.newSingleThreadExecutor(), prefetch));
    }

    private static class PublishOnSubscription<T> implements Subscriber<T>,
                                                             Subscription, Runnable {

        private final Subscriber<? super T> subscriber;
        private final Queue<T> queue;
        private final ExecutorService executorService;
        private final int prefetch;

        Throwable throwable;

        volatile boolean done;

        volatile boolean canceled;

        Subscription subscription;


        volatile long requested;
        static final AtomicLongFieldUpdater<PublishOnSubscription> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(PublishOnSubscription.class, "requested");


        volatile int wip;
        static final AtomicIntegerFieldUpdater<PublishOnSubscription> WIP =
            AtomicIntegerFieldUpdater.newUpdater(PublishOnSubscription.class, "wip");

        public PublishOnSubscription(Queue<T> queue,
            Subscriber<? super T> subscriber,
            ExecutorService executorService, int prefetch) {
            this.queue = queue;
            this.subscriber = subscriber;
            this.executorService = executorService;
            this.prefetch = prefetch;
        }

        @Override
        public void onSubscribe(Subscription s) {
            this.subscription = s;

            s.request(prefetch);
            subscriber.onSubscribe(this);
        }

        @Override
        public void onNext(T t) {
            if (canceled) {
                return;
            }

            queue.offer(t);

            trySchedule();
        }

        @Override
        public void onError(Throwable t) {
            if (canceled) {
                return;
            }

            done = true;
            throwable = t;
            trySchedule();
        }

        @Override
        public void onComplete() {
            if (canceled) {
                return;
            }

            done = true;
        }


        void trySchedule() {
            if (WIP.getAndIncrement(this) > 0) {
                return;
            }

            executorService.execute(this);
        }

        @Override
        public void run() {
            slowPath(requested);
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

        void slowPath(long n) {
            int inProgress = 1;
            int sent = 0;
            Queue<T> queue = this.queue;
            Subscriber<? super T> subscriber = this.subscriber;

            while (true) {
                while (true) {
                    boolean empty = false;
                    for (; sent < n; sent++) {
                        if (canceled) {
                            return;
                        }

                        T element = queue.poll();

                        empty = element == null;

                        if (empty) {
                            break;
                        }

                        subscriber.onNext(element);
                    }

                    if (canceled) {
                        return;
                    }

                    if (done && empty) {
                        if (throwable != null) {
                            subscriber.onError(throwable);
                        }
                        else {
                            subscriber.onComplete();
                        }
                        return;
                    }

                    if (empty) {
                        REQUESTED.addAndGet(this, -sent);
                        subscription.request(sent);
                        sent = 0;
                        break;
                    }

                    n = requested;

                    if (n == sent) {
                        n = REQUESTED.addAndGet(this, -sent);
                        if (n == 0) {
                            subscription.request(n);
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
            if (!canceled) {
                canceled = true;

                if (WIP.getAndIncrement(this) == 0) {
                    queue.clear();
                }
            }
        }
    }
}
