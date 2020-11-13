package org.test.reactive;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

public class QueuePublisherTest extends PublisherVerification<Long> {

  public QueuePublisherTest() {
    super(new TestEnvironment());
  }
//
//  @Test
//  public void signalsShouldBeEmittedInTheRightOrder() throws InterruptedException {
//    CountDownLatch latch = new CountDownLatch(1);
//    ArrayList<Long> collected = new ArrayList<>();
//    ArrayList<String> receivedSignals = new ArrayList<>();
//    long toRequest = 5L;
//    var queue = generate(toRequest);
//    var expectedResult = queue.toArray(Long[]::new);
//    QueuePublisher<Long> publisher = new QueuePublisher<>(queue);
//
//    publisher.subscribe(new Subscriber<Long>() {
//      @Override
//      public void onSubscribe(Subscription s) {
//        receivedSignals.add("onSubscribe");
//        s.request(toRequest);
//      }
//
//      @Override
//      public void onNext(Long aLong) {
//        collected.add(aLong);
//        receivedSignals.add("onNext");
//      }
//
//      @Override
//      public void onError(Throwable t) {
//        receivedSignals.add("onError");
//        latch.countDown();
//      }
//
//      @Override
//      public void onComplete() {
//        receivedSignals.add("onComplete");
//        latch.countDown();
//      }
//    });
//
//    latch.await(1, SECONDS);
//
//    assertThat(receivedSignals)
//        .containsExactly(
//            "onSubscribe",
//            "onNext", "onNext", "onNext", "onNext", "onNext",
//            "onComplete"
//        );
//    assertThat(collected).containsExactly(expectedResult);
//  }
//
//  @Test
//  public void mustSupportBackpressureControl() throws InterruptedException {
//    CountDownLatch latch = new CountDownLatch(1);
//    ArrayList<Long> collected = new ArrayList<>();
//    long toRequest = 5L;
//    var queue = generate(toRequest);
//    var expectedResult = queue.toArray(Long[]::new);
//    QueuePublisher<Long> publisher = new QueuePublisher<>(queue);
//    Subscription[] subscription = new Subscription[1];
//
//    publisher.subscribe(new Subscriber<Long>() {
//      @Override
//      public void onSubscribe(Subscription s) {
//        subscription[0] = s;
//      }
//
//      @Override
//      public void onNext(Long aLong) {
//        collected.add(aLong);
//      }
//
//      @Override
//      public void onError(Throwable t) {
//
//      }
//
//      @Override
//      public void onComplete() {
//        latch.countDown();
//      }
//    });
//
//    assertThat(collected).isEmpty();
//
//    subscription[0].request(1);
//    assertThat(collected).containsExactly(0L);
//
//    subscription[0].request(1);
//    assertThat(collected).containsExactly(0L, 1L);
//
//    subscription[0].request(2);
//    assertThat(collected).containsExactly(0L, 1L, 2L, 3L);
//
//    subscription[0].request(20);
//
//    assertThat(latch.await(1, SECONDS)).isTrue();
//
//    assertThat(collected).containsExactly(expectedResult);
//  }
//
//  @Test
//  public void mustSendNPENormally() throws InterruptedException {
//    CountDownLatch latch = new CountDownLatch(1);
//    Long[] expectedResult = new Long[]{null};
//
//    var array = new LinkedList<Long>();
//    array.add(null);
//
//    AtomicReference<Throwable> error = new AtomicReference<>();
//    var publisher = new QueuePublisher<>(array);
//
//    publisher.subscribe(new Subscriber<Long>() {
//      @Override
//      public void onSubscribe(Subscription s) {
//        s.request(4);
//      }
//
//      @Override
//      public void onNext(Long aLong) {
//        throw new RuntimeException("Unexpected onNext");
//      }
//
//      @Override
//      public void onError(Throwable t) {
//        error.set(t);
//        latch.countDown();
//      }
//
//      @Override
//      public void onComplete() {
//      }
//    });
//
//    latch.await(1, SECONDS);
//
//    assertThat(error.get()).isInstanceOf(NullPointerException.class);
//  }
//
//  @Test
//  public void shouldNotDieInStackOverflow() throws InterruptedException {
//    CountDownLatch latch = new CountDownLatch(1);
//    ArrayList<Long> collected = new ArrayList<>();
//    long toRequest = 100000L;
//    var queue = generate(toRequest);
//    var expectedResult = queue.toArray(Long[]::new);
//
//    AtomicReference<Throwable> error = new AtomicReference<>();
//    var publisher = new QueuePublisher<>(queue);
//
//    publisher.subscribe(new Subscriber<Long>() {
//      Subscription s;
//
//      @Override
//      public void onSubscribe(Subscription s) {
//        this.s = s;
//        s.request(1);
//      }
//
//      @Override
//      public void onNext(Long aLong) {
//        collected.add(aLong);
//
//        s.request(1);
//      }
//
//      @Override
//      public void onError(Throwable t) {
//          error.set(t);
//          latch.countDown();
//      }
//
//      @Override
//      public void onComplete() {
//        latch.countDown();
//      }
//    });
//
//    assertThat(latch.await(5, SECONDS)).isTrue();
//    assertThat(collected).containsExactly(expectedResult);
//  }
//
//  @Test
//  public void shouldBePossibleToCancelSubscription() throws InterruptedException {
//      CountDownLatch latch = new CountDownLatch(1);
//      ArrayList<Long> collected = new ArrayList<>();
//      long toRequest = 1000L;
//      var queue = generate(toRequest);
//      var expectedResult = queue.toArray(Long[]::new);
//      var publisher = new QueuePublisher<>(queue);
//
//      publisher.subscribe(new Subscriber<Long>() {
//
//          @Override
//          public void onSubscribe(Subscription s) {
//              s.cancel();
//              s.request(toRequest);
//          }
//
//          @Override
//          public void onNext(Long aLong) {
//              collected.add(aLong);
//          }
//
//          @Override
//          public void onError(Throwable t) {
//              latch.countDown();
//          }
//
//          @Override
//          public void onComplete() {
//              latch.countDown();
//          }
//      });
//
//      assertThat(latch.await(1, SECONDS)).isFalse();
//      assertThat(collected).isEmpty();
//  }

  static Long[] generate(long num) {
    return
        LongStream.range(0, num >= Integer.MAX_VALUE ? 1000000 : num)
            .boxed()
            .toArray(Long[]::new);
  }

  @Override
  public Publisher<Long> createPublisher(long elements) {
    final Long[] generate = generate(elements);
    return new QueuePublisher<Long>(() -> new ArrayDeque<Long>(Arrays.asList(generate)));
  }

  @Override
  public Publisher<Long> createFailedPublisher() {
    return null;
  }
}