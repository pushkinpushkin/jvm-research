package dev.pushkin.jvmresearch;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class SandboxApp {

    private SandboxApp() {
    }

    public static void main(String[] args) throws InterruptedException {
        Instant startedAt = Instant.now();
        int iterations = readIntEnv("SANDBOX_ITERATIONS", 20);
        int payloadSize = readIntEnv("SANDBOX_PAYLOAD_SIZE", 100_000);
        long sleepMillis = readLongEnv("SANDBOX_SLEEP_MILLIS", 250L);

        System.out.printf(Locale.ROOT,
                "SandboxApp started: java=%s, vm=%s, vendor=%s, iterations=%d, payloadSize=%d%n",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                System.getProperty("java.vendor"),
                iterations,
                payloadSize);

        List<Long> samples = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long before = System.nanoTime();
            long checksum = doCpuAndAllocationWork(payloadSize);
            long elapsedNanos = System.nanoTime() - before;
            samples.add(elapsedNanos);

            System.out.printf(Locale.ROOT,
                    "iteration=%d elapsedMs=%.3f checksum=%d heapUsedMb=%d gcCount=%d%n",
                    i + 1,
                    elapsedNanos / 1_000_000.0,
                    checksum,
                    heapUsedMb(),
                    gcCount());

            Thread.sleep(sleepMillis);
        }

        samples.sort(Comparator.naturalOrder());
        System.out.printf(Locale.ROOT,
                "SandboxApp finished: uptimeMs=%d p50Ms=%.3f p95Ms=%.3f maxMs=%.3f heapUsedMb=%d gcCount=%d%n",
                Duration.between(startedAt, Instant.now()).toMillis(),
                percentile(samples, 0.50) / 1_000_000.0,
                percentile(samples, 0.95) / 1_000_000.0,
                samples.getLast() / 1_000_000.0,
                heapUsedMb(),
                gcCount());
    }

    private static long doCpuAndAllocationWork(int payloadSize) {
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

    private static long heapUsedMb() {
        MemoryUsage heapMemoryUsage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heapMemoryUsage.getUsed() / 1024 / 1024;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount)
                .filter(value -> value > 0)
                .sum();
    }

    private static long percentile(List<Long> sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private static int readIntEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static long readLongEnv(String name, long defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }
}
