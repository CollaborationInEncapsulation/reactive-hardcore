package org.test.reactive;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class TakePublisher<T> implements Publisher<T> {

    final Publisher<? extends T> source;

    final long take;

    public TakePublisher(Publisher<? extends T> source, long take) {
        this.source = source;
        this.take = take;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new TakeOperator<>(s, take));
    }

    private static final class TakeOperator<T> implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;
        final long take;

        int produced;

        Subscription s;
        boolean done;

        private TakeOperator(
            Subscriber<? super T> actual,
            long take
        ) {
            this.actual = actual;
            this.take = take;
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

            actual.onNext(element);

            if (++produced == take) {
                s.cancel();
                onComplete();
            }
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
            if (n > take) {
                s.request(Long.MAX_VALUE);
                return;
            }

            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
