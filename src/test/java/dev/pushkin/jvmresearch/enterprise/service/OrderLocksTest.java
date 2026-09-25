package dev.pushkin.jvmresearch.enterprise.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.*;

class OrderLocksTest {
    @Test
    void concurrentWritersPreserveAllUpdates() throws Exception {
        var locks = new OrderLocks();
        int[] state = {0};
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 1000; i++)
                futures.add(pool.submit(() -> locks.withOrder("same-order", () -> ++state[0])));
            for (var f : futures) f.get(5, TimeUnit.SECONDS);
        }
        assertEquals(1000, state[0]);
    }
}
