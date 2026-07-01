package dev.pushkin.jvmresearch;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(2)
public class AllocationBenchmark {

    @Param({"1000", "10000", "100000"})
    public int size;

    @Benchmark
    public long allocateAndScanObjects() {
        List<Payload> payloads = new ArrayList<>(size);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (int i = 0; i < size; i++) {
            payloads.add(new Payload(i, "order-" + i, random.nextLong()));
        }

        long checksum = 0;
        for (Payload payload : payloads) {
            checksum += payload.id() * 31L + payload.name().length() + payload.value();
        }
        return checksum;
    }

    private record Payload(int id, String name, long value) {
    }
}
