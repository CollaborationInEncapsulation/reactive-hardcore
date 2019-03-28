package org.test.reactive;

import java.util.stream.LongStream;

import org.assertj.core.api.Assertions;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

public class MapPublisherTest extends PublisherVerification<String> {

    public MapPublisherTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<String> createPublisher(long elements) {
        ArrayPublisher<Long> arrayPublisher = new ArrayPublisher<>(generate(elements));
        MapPublisher<Long, String> mapPublisher = new MapPublisher<>(arrayPublisher, String::valueOf);
        return mapPublisher;
    }

    @Override
    public Publisher<String> createFailedPublisher() {
        return null;
    }


    static Long[] generate(long num) {
        return LongStream.range(0, num >= Integer.MAX_VALUE ? 1000000 : num)
                         .boxed()
                         .toArray(Long[]::new);
    }

    @Test
    public void shouldWorkCorrectlyInCaseOfErrorInMap() {
        ArrayPublisher<Long> arrayPublisher = new ArrayPublisher<>(generate(10));
        MapPublisher<Long, String> mapPublisher = new MapPublisher<>(arrayPublisher,
            obj -> {
                throw new RuntimeException("for test");
            }
        );

        mapPublisher.subscribe(new Subscriber<String>() {
            private boolean done;
            @Override
            public void onSubscribe(Subscription s) {
                s.request(100);
            }

            @Override
            public void onNext(String s) {
                Assertions.fail("Should not be ever called");
            }

            @Override
            public void onError(Throwable t) {
                Assertions.assertThat(t)
                          .isExactlyInstanceOf(RuntimeException.class)
                          .hasMessage("for test");
                Assertions.assertThat(done).isFalse();
                done = true;
            }

            @Override
            public void onComplete() {
                Assertions.fail("Should not be called");
            }
        });
    }
}