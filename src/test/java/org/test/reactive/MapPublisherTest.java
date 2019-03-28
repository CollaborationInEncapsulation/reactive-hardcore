package org.test.reactive;

import java.util.stream.LongStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

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
}