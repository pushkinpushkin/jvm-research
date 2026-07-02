package dev.pushkin.jvmresearch.enterprise.synthetic;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SyntheticRuntimeService {

    public SyntheticRuntimeResponse run(int iterations, int payloadSize) {
        log.info("Synthetic runtime workload started iterations={} payloadSize={}", iterations, payloadSize);
        Instant startedAt = Instant.now();
        List<Long> samples = new ArrayList<>(iterations);
        long checksum = 0L;

        for (int i = 0; i < iterations; i++) {
            long before = System.nanoTime();
            checksum += doCpuAndAllocationWork(payloadSize);
            long elapsedNanos = System.nanoTime() - before;
            samples.add(elapsedNanos);
        }

        samples.sort(Comparator.naturalOrder());
        SyntheticRuntimeResponse response = new SyntheticRuntimeResponse(
                iterations,
                payloadSize,
                Duration.between(startedAt, Instant.now()).toMillis(),
                toMillis(percentile(samples, 0.50)),
                toMillis(percentile(samples, 0.95)),
                toMillis(samples.getLast()),
                heapUsedMb(),
                gcCount(),
                checksum
        );
        log.info("Synthetic runtime workload finished iterations={} payloadSize={} durationMs={} p95Ms={}",
                iterations,
                payloadSize,
                response.uptimeMs(),
                response.p95Ms()
        );
        return response;
    }

    private long doCpuAndAllocationWork(int payloadSize) {
        List<String> values = new ArrayList<>(payloadSize);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < payloadSize; i++) {
            values.add("order-" + i + "-" + random.nextInt(10_000));
        }

        return values.stream()
                .filter(value -> value.hashCode() % 3 == 0)
                .mapToLong(value -> value.length() * 31L + value.charAt(value.length() - 1))
                .sum();
    }

    private long heapUsedMb() {
        MemoryUsage heapMemoryUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heapMemoryUsage.getUsed() / 1024 / 1024;
    }

    private long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value > 0)
                .sum();
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
