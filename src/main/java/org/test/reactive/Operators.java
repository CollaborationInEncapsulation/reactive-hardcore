package org.test.reactive;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.util.annotation.Nullable;

public class Operators {
    public static <T> long addCap(AtomicLongFieldUpdater<T> updater, T instance, long toAdd) {
        long r, u;
        for (;;) {
            r = updater.get(instance);
            if (r == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            u = addCap(r, toAdd);
            if (updater.compareAndSet(instance, r, u)) {
                return r;
            }
        }
    }

    public static long addCap(long a, long b) {
        long res = a + b;
        if (res < 0L) {
            return Long.MAX_VALUE;
        }
        return res;
    }

    public static boolean validate(long n, boolean cancelled, Subscription current, Subscriber<?> subscriber) {
        if (n <= 0 && !cancelled) {
            current.cancel();
            subscriber.onError(new IllegalArgumentException(
                "§3.9 violated: positive request amount required but it was " + n
            ));
            return false;
        }

        return true;
    }
}
