# Setup

Checkout the project -> `git@github.com:CollaborationInEncapsulation/reactive-hardcore.git`
Open it in the IDEA. 
Checkout the first commit in the history

# Simple Implementation

In this section, you can find all required tests snippets to tests the initial implementation of the `Publisher<?>`.

## Step 1. Calls order test

Reactive-Streams specification mandates that all method of `Subscriber` MUST be executed in particular order. Past the following code snippet into the `org.test.reactive.ArrayPublisherTest` file to tests expected behavior:

```java
@Test
public void signalsShouldBeEmittedInTheRightOrder() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    ArrayList<Long> collected = new ArrayList<>();
    ArrayList<Integer> order = new ArrayList<>();
    long toRequest = 5L;
    Long[] array = generate(toRequest);
    ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

    publisher.subscribe(new Subscriber<Long>() {
        @Override
        public void onSubscribe(Subscription s) {
            order.add(0);
            s.request(toRequest);
        }

        @Override
        public void onNext(Long aLong) {
            collected.add(aLong);

            if (!order.contains(1)) {
                order.add(1);
            }
        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onComplete() {
            order.add(2);
            latch.countDown();
        }
    });

    latch.await(1, TimeUnit.SECONDS);

    Assert.assertEquals(order, Arrays.asList(0, 1, 2));
    Assert.assertEquals(collected, Arrays.asList(array));
}
```

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Call everything one by one:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(long n) {

            }

            @Override
            public void cancel() {

            }
        });

        for (int i = 0; i < array.length; i++) {
            subscriber.onNext(array[i]);
        }

        subscriber.onComplete();
    }
}
```
</p>
</details>

## Step 2. Backpressure support test

Reactive-Streams specification states that `Publisher` MUST produce less or equal to the specified number of elements in `Subscription#request`. Past the following code snippet into the `org.test.reactive.ArrayPublisherTest` file to tests expected behavior:

```java
@Test
public void mustSupportBackpressureControl() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    ArrayList<Long> collected = new ArrayList<>();
    long toRequest = 5L;
    Long[] array = generate(toRequest);
    ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);
    Subscription[] subscription = new Subscription[1];

    publisher.subscribe(new Subscriber<Long>() {
        @Override
        public void onSubscribe(Subscription s) {
            subscription[0] = s;
        }

        @Override
        public void onNext(Long aLong) {
            collected.add(aLong);
        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onComplete() {
            latch.countDown();
        }
    });


    assertEquals(collected, Collections.emptyList());

    subscription[0].request(1);
    assertEquals(collected, asList(0L));

    subscription[0].request(1);
    assertEquals(collected, asList(0L, 1L));

    subscription[0].request(2);
    assertEquals(collected, asList(0L, 1L, 2L, 3L));

    subscription[0].request(20);

    latch.await(1, SECONDS);

    assertEquals(collected, asList(array));
}
```

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Store sending logic inside the request method and create an additional field in order to keep the state:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;

            @Override
            public void request(long n) {
                for (int i = 0; i < n && index < array.length; i++, index++) {
                    subscriber.onNext(array[index]);
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }
            }

            @Override
            public void cancel() {

            }
        });
    }
}
```
</p>
</details>

## Step 3. NullPointer protection test

Reactive-Streams specification states that null must be avoided in sending to `Subscriber`. Thus, in case `null` element is found inside an array, `Publisher` MUST interrupt by sending `onError` signal to its subscriber. Past the following code snippet into the `org.test.reactive.ArrayPublisherTest` file to tests expected behavior:

```java
@Test
public void mustSendNPENormally() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    Long[] array = new Long[] { null };
    AtomicReference<Throwable> error = new AtomicReference<>();
    ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

    publisher.subscribe(new Subscriber<Long>() {
        @Override
        public void onSubscribe(Subscription s) {
            s.request(4);
        }

        @Override
        public void onNext(Long aLong) {
        }

        @Override
        public void onError(Throwable t) {
            error.set(t);
            latch.countDown();
        }

        @Override
        public void onComplete() {
        }
    });

    latch.await(1, SECONDS);

    Assert.assertTrue(error.get() instanceof NullPointerException);
}
```

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Just check each element on `null` before sending it downstream. In case of null - send NPE over `Subscriber#onError`:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;

            @Override
            public void request(long n) {
                for (int i = 0; i < n && index < array.length; i++, index++) {
                    T element = array[index];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }
            }

            @Override
            public void cancel() {

            }
        });
    }
}
```
</p>
</details>

## Step 4. Infinite recursion protection test

It is common that each `Subscriber#onNext` call can end up with subsequent `Subscription#request`. Reactive-Streams specification clearly states that Subscriber can synchronously call `Subscription` and `Publisher` MUST be protected from recursive calls in the same stack. Past the following code snippet into the `org.test.reactive.ArrayPublisherTest` file to tests expected behavior:

```java
@Test
public void shouldNotDieInStackOverflow() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    ArrayList<Long> collected = new ArrayList<>();
    long toRequest = 1000L;
    Long[] array = generate(toRequest);
    ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

    publisher.subscribe(new Subscriber<Long>() {
        Subscription s;

        @Override
        public void onSubscribe(Subscription s) {
            this.s = s;
            s.request(1);
        }

        @Override
        public void onNext(Long aLong) {
            collected.add(aLong);

            s.request(1);
        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onComplete() {
            latch.countDown();
        }
    });

    latch.await(5, SECONDS);

    assertEquals(collected, asList(array));
}
```

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Add work in progress check-in in the form of the `requested` field. It allows checking whether someone sends data or not. In the case of recursion we will protect ourselves since decrement is done at the very end:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;
            long requested;

            @Override
            public void request(long n) {

                // if requested > 0, then - someone works
                long initialRequested = requested;

                requested += n;

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                for (; sent < requested && index < array.length; sent++, index++) {
                    T element = array[index];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }

                requested -= sent;
            }

            @Override
            public void cancel() {

            }
        });
    }
}
```
</p>
</details>

## Step 5. Cancellation support test

Reactive-Streams specification states that in case of cancellation, `Publisher` MUST stop sending data eventually. Past the following code snippet into the `org.test.reactive.ArrayPublisherTest` file to tests expected behavior:

```java
@Test
public void shouldBePossibleToCancelSubscription() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    ArrayList<Long> collected = new ArrayList<>();
    long toRequest = 1000L;
    Long[] array = generate(toRequest);
    ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

    publisher.subscribe(new Subscriber<>() {

        @Override
        public void onSubscribe(Subscription s) {
            s.cancel();
            s.request(toRequest);
        }

        @Override
        public void onNext(Long aLong) {
            collected.add(aLong);
        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onComplete() {
            latch.countDown();
        }
    });

    latch.await(1, SECONDS);

    assertEquals(collected, Collections.emptyList());
}
```

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Add a single `boolean` flag which can be used during the looping:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;
            long requested;
            boolean cancelled;

            @Override
            public void request(long n) {

                // if requested > 0, then - someone works
                long initialRequested = requested;

                requested += n;

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                for (; sent < requested && index < array.length; sent++, index++) {
                    if (cancelled) {
                        return;
                    }

                    T element = array[index];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (cancelled) {
                    return;
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }

                requested -= sent;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
```
</p>
</details>

## Step 6. TCK tests and missed implementation parts

Reactive-Streams spec includes more than 40 rules, and it is challenging to verify all corner cases. Fortunately, Reactive-Streams umbrella provides a set of built-in tests that check `Publisher` against all corner cases. Modify the `org.test.reactive.ArrayPublisherTest` file according to the following code snippet:

```java
public class ArrayPublisherTest extends PublisherVerification<Long> {
    
    public ArrayPublisherTest() {
        super(new TestEnvironment());
    }
    
    @Override
    public Publisher<Long> createPublisher(long elements) {
        return new ArrayPublisher<>(generate(elements));
    }
    
    @Override
    public Publisher<Long> createFailedPublisher() {
        return null;
    }

    ... // unchanged
}
```

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Add missed verification on negative|zero `request(-1|0)`:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;
            long requested;
            boolean cancelled;

            @Override
            public void request(long n) {

                // if requested > 0, then - someone works
                long initialRequested = requested;

                requested += n;

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                for (; sent < requested && index < array.length; sent++, index++) {
                    if (cancelled) {
                        return;
                    }

                    T element = array[index];

                    if (element == null) {
                        subscriber.onError(new NullPointerException());
                        return;
                    }

                    subscriber.onNext(element);
                }

                if (cancelled) {
                    return;
                }

                if (index == array.length) {
                    subscriber.onComplete();
                    return;
                }

                requested -= sent;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        });
    }
}
```
</p>
</details>

# Multi-threading implementation

## Step 1. Concurrency and Racing test

The reactive-streams spec states that execution can occur in a multi-threading environment. Thus Publisher should be prepared to handle concurrency on it. Past the following code snippet into the `org.test.reactive.ArrayPublisherTest` file to tests expected behavior:

```java
@Test
public void multithreadingTest() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    ArrayList<Long> collected = new ArrayList<>();
    final int n = 5000;
    Long[] array = generate(n);
    ArrayPublisher<Long> publisher = new ArrayPublisher<>(array);

    publisher.subscribe(new Subscriber<Long>() {
        private Subscription s;

        @Override
        public void onSubscribe(Subscription s) {
            this.s = s;
            for (int i = 0; i < n; i++) {
                commonPool().execute(() -> s.request(1));
            }
        }

        @Override
        public void onNext(Long aLong) {
            collected.add(aLong);
        }

        @Override
        public void onError(Throwable t) {

        }

        @Override
        public void onComplete() {
            latch.countDown();
        }
    });

    latch.await(2, SECONDS);

    assertEquals(collected, asList(array));
}
```


<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Use `Atomic` primitives in order to replace non-thread-safe one. Use an infinite loop in order to avoid the racing issue and dead execution case in the highly concurrent environment:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            AtomicInteger index = new AtomicInteger();
            AtomicLong requested = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void request(long n) {
                if (n <= 0 && !cancelled.get()) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException(
                        "§3.9 violated: positive request amount required but it was " + n
                    ));
                    return;
                }

                long initialRequested = requested.getAndAdd(n);

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                while (true) {
                    for (; sent < requested.get() && index.get() < array.length; sent++, index.incrementAndGet()) {
                        if (cancelled.get()) {
                            return;
                        }

                        T element = array[index.get()];

                        if (element == null) {
                            subscriber.onError(new NullPointerException());
                            return;
                        }

                        subscriber.onNext(element);
                    }

                    if (cancelled.get()) {
                        return;
                    }

                    if (index.get() == array.length) {
                        subscriber.onComplete();
                        return;
                    }

                    if (requested.addAndGet(-sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }
}
```
</p>
</details>

## Step 2. Fixes failed tests.
Once the tests suite is run against latest code, a hanging test related to `request` + `Long.MAX_VALUE` + `Long.MAX_VALUE` case. Reactive-Streams clearly states that MUST support demand up to `Long.MAX_VALUE - 1` and everything above that line MAST do not fail execution. Find a proper fix for that solution.

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Provide a handmade implementation of `Atomic#addAndGet` which checks/prevents the `long` overflow case:

```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            AtomicInteger index = new AtomicInteger();
            AtomicLong requested = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void request(long n) {
                if (n <= 0 && !cancelled.get()) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException(
                        "§3.9 violated: positive request amount required but it was " + n
                    ));
                    return;
                }

                long initialRequested;

                do {
                    initialRequested = requested.get();

                    if (initialRequested == Long.MAX_VALUE) {
                        return;
                    }

                    n = initialRequested + n;

                    if (n <= 0) {
                        n = Long.MAX_VALUE;
                    }

                } while (!requested.weakCompareAndSetVolatile(initialRequested, n));

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                while (true) {
                    for (; sent < requested.get() && index.get() < array.length; sent++, index.incrementAndGet()) {
                        if (cancelled.get()) {
                            return;
                        }

                        T element = array[index.get()];

                        if (element == null) {
                            subscriber.onError(new NullPointerException());
                            return;
                        }

                        subscriber.onNext(element);
                    }

                    if (cancelled.get()) {
                        return;
                    }

                    if (index.get() == array.length) {
                        subscriber.onComplete();
                        return;
                    }

                    if (requested.addAndGet(-sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }
}
```
</p>
</details>


# Optimized Implementation

In this section, we are going to push to the possible max the performance of the constructed `Publisher`. However, there are no optimizations without the proved fact of it. As a result of this, we MUST use the proper benchmark solution for that. This time we are going to use JMH microbenchmark test suite. Uncomment `org.test.reactive.UnoptimizedArrayPublisher` source code which is a copy of the current implementation. Remove all commented lines of code inside `org.test.reactive.ArrayPublisherPerfTest`.

## Step 1. Eliminate redundant thread-safety.

There is a field that does not require thread-safety at all. Find it and simplify the solution.


<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

The `index` is inside WIP (work in progress) loop which guarantees exclusive access to the execution within its bounds. In turn, JMM guarantees that all changes that happens-before the write to a volatile field will be visible once that field is read. That means that changes on the `index` field will be available for any reader for `requested` field.

The following is a raw benchamarks of the changes available in the code below the results summary (about +32% ☝️in performance):
  

```console
# JMH version: 1.21
# VM version: JDK 11.0.1, OpenJDK 64-Bit Server VM, 11.0.1+13
# VM invoker: /Library/Java/JavaVirtualMachines/jdk-11.0.1.jdk/Contents/Home/bin/java
# Warmup: 2 iterations, 10 s each
# Measurement: 3 iterations, 3 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Throughput, ops/time

# Benchmark: org.test.reactive.ArrayPublisherPerfTest.publisherPerformance
# Benchmark: org.test.reactive.ArrayPublisherPerfTest.unoptimizedPublisherPerformance
# Parameters: (times = 1000000)

Benchmark                                               (times)   Mode  Cnt   Score    Error  Units
ArrayPublisherPerfTest.publisherPerformance             1000000  thrpt    3  84.141 ± 20.202  ops/s
ArrayPublisherPerfTest.unoptimizedPublisherPerformance  1000000  thrpt    3  63.593 ±  2.014  ops/s
```


```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            AtomicInteger index = new AtomicInteger();
            AtomicLong requested = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void request(long n) {
                if (n <= 0 && !cancelled.get()) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException(
                        "§3.9 violated: positive request amount required but it was " + n
                    ));
                    return;
                }

                long initialRequested;

                do {
                    initialRequested = requested.get();

                    if (initialRequested == Long.MAX_VALUE) {
                        return;
                    }

                    n = initialRequested + n;

                    if (n <= 0) {
                        n = Long.MAX_VALUE;
                    }

                } while (!requested.weakCompareAndSetVolatile(initialRequested, n));

                if (initialRequested > 0) {
                    return;
                }

                int sent = 0;

                while (true) {
                    for (; sent < requested.get() && index.get() < array.length; sent++, index.incrementAndGet()) {
                        if (cancelled.get()) {
                            return;
                        }

                        T element = array[index.get()];

                        if (element == null) {
                            subscriber.onError(new NullPointerException());
                            return;
                        }

                        subscriber.onNext(element);
                    }

                    if (cancelled.get()) {
                        return;
                    }

                    if (index.get() == array.length) {
                        subscriber.onComplete();
                        return;
                    }

                    if (requested.addAndGet(-sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }
}
```
</p>
</details>

## Step 2. Eliminate redundant volatile operations. Fields access optimizations

Copy current code to the `org.test.reactive.UnoptimizedArrayPublisher` in order to observe optimization after each measurement.

The code contains redundant volatile operations that can be reduced. Analyze which operations can be done in the bulk (bulk access includes movement to local stack, etc).


<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Reading of the `requested` during looping is redundant. Once the worker entered the critical section, the given `n` is equal to current `requested`. Thus we can rely on its value and update it at the end of the loop.
General bulk writes to index field + optimizing access to `index`, `array`, `array.lenght` from the local stack can save from 4 to 8 (or even more) CPU instructions which can optimize performance in turn.

The following is a raw benchamarks of the changes available in the code below the results summary (about +(1-5)% grows in performance depends on the first request size):

```console
# JMH version: 1.21
# VM version: JDK 11.0.1, OpenJDK 64-Bit Server VM, 11.0.1+13
# VM invoker: /Library/Java/JavaVirtualMachines/jdk-11.0.1.jdk/Contents/Home/bin/java
# Warmup: 2 iterations, 10 s each
# Measurement: 5 iterations, 10 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Throughput, ops/time

# Benchmark: org.test.reactive.ArrayPublisherPerfTest.publisherPerformance
# Benchmark: org.test.reactive.ArrayPublisherPerfTest.unoptimizedPublisherPerformance
# Parameters: (times = 1000000)

Benchmark                                               (times)   Mode  Cnt   Score   Error  Units
ArrayPublisherPerfTest.publisherPerformance             1000000  thrpt    5  97.334 ± 6.454  ops/s
ArrayPublisherPerfTest.unoptimizedPublisherPerformance  1000000  thrpt    5  93.842 ± 1.397  ops/s
```


```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            int index;
            AtomicLong requested = new AtomicLong();
            AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void request(long n) {
                if (n <= 0 && !cancelled.get()) {
                    cancel();
                    subscriber.onError(new IllegalArgumentException(
                        "§3.9 violated: positive request amount required but it was " + n
                    ));
                    return;
                }

                long initialRequested;

                do {
                    initialRequested = requested.get();

                    if (initialRequested == Long.MAX_VALUE) {
                        return;
                    }

                    n = initialRequested + n;

                    if (n <= 0) {
                        n = Long.MAX_VALUE;
                    }

                } while (!requested.weakCompareAndSetVolatile(initialRequested, n));

                if (initialRequested > 0) {
                    return;
                }

                final T[] arr = ArrayPublisher.this.array;
                int sent = 0;
                int i = index;
                int length = arr.length;

                while (true) {
                    for (; sent < n && i < length; sent++, i++) {
                        if (cancelled.get()) {
                            return;
                        }

                        T element = arr[i];

                        if (element == null) {
                            subscriber.onError(new NullPointerException());
                            return;
                        }

                        subscriber.onNext(element);
                    }

                    if (cancelled.get()) {
                        return;
                    }

                    if (i == length) {
                        subscriber.onComplete();
                        return;
                    }

                    n = requested.get();
                    if (n == sent) {
                        index = i;
                        if (requested.addAndGet(-sent) == 0) {
                            return;
                        }
                        sent = 0;
                    }
                }
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }
}
```
</p>
</details>

## Step 3. Optimize a number of produced objects for GC.

Work done by GC impacts performance a lot. Let's reduce the number of object in order to keep garbage clean. Copy latest source of actual `ArrayPublisher` to the `org.test.reactive.UnoptimizedArrayPublisher` again.

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Reducing the number of a produced object by replacing `cancelled` field with a plain volatile (no write racing - thus can be just a plain volatile) and replacing `AtomicLong` with an identical `AtomicLongFieldUpdater` + `volatile requested` we can win another few % of performance.

The following is a raw benchamarks of the changes available in the code below the results summary (about +(2-5)% grows in performance depends on the elements in the `Publisher`):

```console
# JMH version: 1.21
# VM version: JDK 11.0.1, OpenJDK 64-Bit Server VM, 11.0.1+13
# VM invoker: /Library/Java/JavaVirtualMachines/jdk-11.0.1.jdk/Contents/Home/bin/java
# Warmup: 2 iterations, 10 s each
# Measurement: 5 iterations, 10 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Throughput, ops/time

# Benchmark: org.test.reactive.ArrayPublisherPerfTest.publisherPerformance
# Benchmark: org.test.reactive.ArrayPublisherPerfTest.unoptimizedPublisherPerformance
# Parameters: (times = 10)
# Parameters: (times = 1000000)

Benchmark                                               (times)   Mode  Cnt        Score        Error  Units
ArrayPublisherPerfTest.publisherPerformance                  10  thrpt    5  8784517.598 ± 526494.471  ops/s
ArrayPublisherPerfTest.publisherPerformance             1000000  thrpt    5      100.878 ±      2.055  ops/s
ArrayPublisherPerfTest.unoptimizedPublisherPerformance       10  thrpt    5  8414885.985 ± 514022.929  ops/s
ArrayPublisherPerfTest.unoptimizedPublisherPerformance  1000000  thrpt    5       98.818 ±      1.076  ops/s
```


```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new ArraySubscription<T>(array, subscriber));
    }

    private static class ArraySubscription<T> implements Subscription {

        final T[] array;
        final Subscriber<? super T> subscriber;

        int index;

        volatile long requested;
        static final AtomicLongFieldUpdater<ArraySubscription> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(ArraySubscription.class, "requested");

        volatile boolean cancelled;

        public ArraySubscription(T[] array, Subscriber<? super T> subscriber) {
            this.array = array;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0 && !cancelled) {
                cancel();
                subscriber.onError(new IllegalArgumentException(
                    "§3.9 violated: positive request amount required but it was " + n
                ));
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

            } while (!REQUESTED.compareAndSet(this, initialRequested, n));

            if (initialRequested > 0) {
                return;
            }

            final Subscriber<? super T> s = subscriber;
            final T[] arr = array;
            int sent = 0;
            int i = index;
            int length = arr.length;

            while (true) {
                for (; sent < n && i < length; sent++, i++) {
                    if (cancelled) {
                        return;
                    }

                    T element = arr[i];

                    if (element == null) {
                        s.onError(new NullPointerException());
                        return;
                    }

                    s.onNext(element);
                }

                if (cancelled) {
                    return;
                }

                if (i == length) {
                    s.onComplete();
                    return;
                }

                n = requested;
                if (n == sent) {
                    index = i;
                    if (REQUESTED.addAndGet(this, -sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
```
</p>
</details>

## Step 4. Optimize execution path

Reactive-Streams states that if request size is equal to `Long.MAX_VALUE` we can simply turn execution into the pure push model without counting/decreasing `requested` field. Optimize the code according to the suggestion.

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

Introducing the fast path can significantly improve performance in case of `Long.MAX_VALUE` request!

The following is a raw benchamarks of the changes available in the code below the results summary (about +(7-25)% grows in performance depends on the elements in the `Publisher`):

```console
# JMH version: 1.21
# VM version: JDK 11.0.1, OpenJDK 64-Bit Server VM, 11.0.1+13
# VM invoker: /Library/Java/JavaVirtualMachines/jdk-11.0.1.jdk/Contents/Home/bin/java
# Warmup: 2 iterations, 10 s each
# Measurement: 5 iterations, 10 s each
# Timeout: 10 min per iteration
# Threads: 1 thread, will synchronize iterations
# Benchmark mode: Throughput, ops/time

# Benchmark: org.test.reactive.ArrayPublisherPerfTest.publisherPerformance
# Benchmark: org.test.reactive.ArrayPublisherPerfTest.unoptimizedPublisherPerformance
# Parameters: (times = 10)
# Parameters: (times = 1000000)

Benchmark                                               (times)   Mode  Cnt         Score        Error  Units
ArrayPublisherPerfTest.publisherPerformance                  10  thrpt    5  26441416.821 ± 879311.200  ops/s
ArrayPublisherPerfTest.publisherPerformance             1000000  thrpt    5       330.283 ±     12.811  ops/s
ArrayPublisherPerfTest.unoptimizedPublisherPerformance       10  thrpt    5  24602782.049 ± 541028.105  ops/s
ArrayPublisherPerfTest.unoptimizedPublisherPerformance  1000000  thrpt    5       263.150 ±      4.910  ops/s
```


```java
public class ArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public ArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new ArraySubscription<T>(array, subscriber));
    }

    private static class ArraySubscription<T> implements Subscription {

        final T[] array;
        final Subscriber<? super T> subscriber;

        int index;

        volatile long requested;
        static final AtomicLongFieldUpdater<ArraySubscription> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(ArraySubscription.class, "requested");

        volatile boolean cancelled;

        public ArraySubscription(T[] array, Subscriber<? super T> subscriber) {
            this.array = array;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0 && !cancelled) {
                cancel();
                subscriber.onError(new IllegalArgumentException(
                    "§3.9 violated: positive request amount required but it was " + n
                ));
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

            } while (!REQUESTED.compareAndSet(this, initialRequested, n));

            if (initialRequested > 0) {
                return;
            }

            if (n == Long.MAX_VALUE) {
                fastPath();
            }
            else {
                slowPath(n);
            }
        }

        void fastPath() {
            final Subscriber<? super T> s = subscriber;
            final T[] arr = array;
            int i = index;
            int length = arr.length;

            for (; i < length; i++) {
                if (cancelled) {
                    return;
                }

                T element = arr[i];

                if (element == null) {
                    s.onError(new NullPointerException());
                    return;
                }

                s.onNext(element);
            }

            if (cancelled) {
                return;
            }

            s.onComplete();
        }

        void slowPath(long n) {
            final Subscriber<? super T> s = subscriber;
            final T[] arr = array;
            int sent = 0;
            int i = index;
            int length = arr.length;

            while (true) {
                for (; sent < n && i < length; sent++, i++) {
                    if (cancelled) {
                        return;
                    }

                    T element = arr[i];

                    if (element == null) {
                        s.onError(new NullPointerException());
                        return;
                    }

                    s.onNext(element);
                }

                if (cancelled) {
                    return;
                }

                if (i == length) {
                    s.onComplete();
                    return;
                }

                n = requested;
                if (n == sent) {
                    index = i;
                    if (REQUESTED.addAndGet(this, -sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
```
</p>
</details>

# Dive deeper. Solving Orders Processing case.


Switch to the `part-2-...` brunch. Checkout the commit with `Part 2 message`

It is time to apply what we built in order to solve the real problem of processing users orders. To solve our problem, we need a few operators:

* Map
* Filter
* Take
* PublishOn

## Step 1. Building Map operator

The next challenge is to implement an intermediate operator. Let's start with the simplest one. Try to find out what is the required structure for the intermediate operator, how to chain it with the main source, etc.


<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

```java
public class MapPublisher<T, R> implements Publisher<R> {

    final Publisher<? extends T> source;
    final Function<? super T, ? extends R> mapper;

    public MapPublisher(Publisher<? extends T> source,
        Function<? super T, ? extends R> mapper) {

        this.source = source;
        this.mapper = mapper;
    }

    @Override
    public void subscribe(Subscriber<? super R> s) {
        source.subscribe(new MapOperator<>(s, mapper));
    }

    private static final class MapOperator<T, R> implements Subscriber<T>, Subscription {

        final Subscriber<? super R>            actual;
        final Function<? super T, ? extends R> mapper;

        Subscription s;
        boolean done;

        private MapOperator(
            Subscriber<? super R> actual,
            Function<? super T, ? extends R> mapper
        ) {
            this.actual = actual;
            this.mapper = mapper;
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

            R mappedElement;

            try {
                mappedElement = Objects.requireNonNull(mapper.apply(element));
            } catch (Throwable t) {
                s.cancel();
                onError(t);
                return;
            }

            actual.onNext(mappedElement);
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
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
```
</p>
</details>

## Step 2. Building Rest of the operators

Following the same code convention, implement the rest of the operators

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

```java
public class FilterPublisher<T> implements Publisher<T> {

    final Publisher<? extends T> source;
    final Predicate<? super T> filter;

    public FilterPublisher(Publisher<? extends T> source,
        Predicate<? super T> filter) {

        this.source = source;
        this.filter = filter;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new FilterOperator<>(s, filter));
    }

    private static final class FilterOperator<T> implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;
        final Predicate<? super T>  filter;

        Subscription s;
        boolean done;

        private FilterOperator(
            Subscriber<? super T> actual,
            Predicate<? super T> filter
        ) {
            this.actual = actual;
            this.filter = filter;
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

            boolean result;

            try {
                result = filter.test(element);
            } catch (Throwable t) {
                s.cancel();
                onError(t);
                return;
            }

            if (result) {
                actual.onNext(element);
            }
            else {
                s.request(1);
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
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
```

```java
public class FilterPublisher<T> implements Publisher<T> {

    final Publisher<? extends T> source;
    final Predicate<? super T> filter;

    public FilterPublisher(Publisher<? extends T> source,
        Predicate<? super T> filter) {

        this.source = source;
        this.filter = filter;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new FilterOperator<>(s, filter));
    }

    private static final class FilterOperator<T> implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;
        final Predicate<? super T>  filter;

        Subscription s;
        boolean done;

        private FilterOperator(
            Subscriber<? super T> actual,
            Predicate<? super T> filter
        ) {
            this.actual = actual;
            this.filter = filter;
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

            boolean result;

            try {
                result = filter.test(element);
            } catch (Throwable t) {
                s.cancel();
                onError(t);
                return;
            }

            if (result) {
                actual.onNext(element);
            }
            else {
                s.request(1);
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
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
```

</p>
</details>

# Step 3 implement it in a reactive way

Use created operators in order to implement the business logic of the app.

<details><summary><b>Solution (Don't cheat. In case of Emergency only)</b></summary>
<p>

```java
public class FilterPublisher<T> implements Publisher<T> {

    final Publisher<? extends T> source;
    final Predicate<? super T> filter;

    public FilterPublisher(Publisher<? extends T> source,
        Predicate<? super T> filter) {

        this.source = source;
        this.filter = filter;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new FilterOperator<>(s, filter));
    }

    private static final class FilterOperator<T> implements Subscriber<T>, Subscription {

        final Subscriber<? super T> actual;
        final Predicate<? super T>  filter;

        Subscription s;
        boolean done;

        private FilterOperator(
            Subscriber<? super T> actual,
            Predicate<? super T> filter
        ) {
            this.actual = actual;
            this.filter = filter;
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

            boolean result;

            try {
                result = filter.test(element);
            } catch (Throwable t) {
                s.cancel();
                onError(t);
                return;
            }

            if (result) {
                actual.onNext(element);
            }
            else {
                s.request(1);
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
            s.request(n);
        }

        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
```

</p>
</details>