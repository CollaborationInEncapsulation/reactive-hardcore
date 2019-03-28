package org.test.reactive;

import java.util.function.Predicate;
import java.util.stream.LongStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;

public class FilterPublisherTest extends PublisherVerification<Long> {

    public FilterPublisherTest() {
        super(new TestEnvironment());
    }

    @Override
    public Publisher<Long> createPublisher(long elements) {
        ArrayPublisher<Long> arrayPublisher = new ArrayPublisher<>(generate(elements + 1));
        FilterPublisher<Long> filterPublisher = new FilterPublisher<>(arrayPublisher,
            new Predicate<Long>() {
                boolean first = true;
                @Override
                public boolean test(Long aLong) {
                    if (first) {
                        first = false;
                        return false;
                    }

                    return true;
                }
            });

        return filterPublisher;
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