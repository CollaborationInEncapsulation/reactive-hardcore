package org.test.reactive;

import java.util.stream.LongStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

public class TakePublisherTest extends PublisherVerification<Long> {

    public TakePublisherTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<Long> createPublisher(long elements) {
        elements = elements >= Integer.MAX_VALUE ? 1000000 / 2 : elements;
        ArrayPublisher<Long> arrayPublisher = new ArrayPublisher<>(generate(elements * 2));
        TakePublisher<Long> takePublisher = new TakePublisher<>(arrayPublisher, elements);

        return takePublisher;
    }

    @Override
    public Publisher<Long> createFailedPublisher() {
        return null;
    }


    static Long[] generate(long num) {
        return LongStream.range(0, num >= Integer.MAX_VALUE ? 1000000 : num)
                         .boxed()
                         .toArray(Long[]::new);
    }
}