package org.test.reactor_new_api;

import java.util.Queue;
import org.reactivestreams.Subscription;

public interface CoreSubscription<T> extends Subscription {

  Queue<T> requestFusion(int fusionMode);
}
