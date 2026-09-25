package dev.pushkin.jvmresearch.enterprise.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** Bounded locks for the single application replica used by this experiment. */
@Component
public class OrderLocks {
    private final ReentrantLock[] locks = new ReentrantLock[1024];

    public OrderLocks() {
        for (int i = 0; i < locks.length; i++) locks[i] = new ReentrantLock();
    }

    public <T> T withOrder(String id, Supplier<T> action) {
        ReentrantLock lock = locks[Math.floorMod(id.hashCode(), locks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
