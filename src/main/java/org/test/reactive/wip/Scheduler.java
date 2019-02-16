package org.test.reactive.wip;

import org.test.reactive.Disposable;

import javax.annotation.Nonnull;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Scheduler {

    public interface Worker extends Executor, Disposable {
        // disposable to free resources
    }

    private final String name;

    public Scheduler(String name) {
        this.name = name;
    }

    public Worker createWorker() {
        ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread();
            thread.setDaemon(true);
            thread.setName(name);
            return thread;
        });

        return new Worker() {
            @Override
            public void execute(@Nonnull Runnable command) {
                executorService.execute(command);
            }

            @Override
            public boolean isDisposed() {
                return executorService.isShutdown();
            }

            @Override
            public void dispose() {
                executorService.shutdownNow();
            }
        };
    }
}
