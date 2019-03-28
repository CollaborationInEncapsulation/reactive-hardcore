/*
 * Copyright 2017 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.test.reactive;

import org.openjdk.jmh.infra.Blackhole;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public final class PerfSubscriber implements Subscriber<Object> {
    final  Blackhole      bh;

    Subscription subscription;

    public PerfSubscriber(Blackhole bh) {
        this.bh = bh;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(10);
    }

    @Override
    public void onNext(Object item) {
        bh.consume(item);
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        bh.consume(throwable);
    }

    @Override
    public void onComplete() {
        bh.consume(true);
    }

}
