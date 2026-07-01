package dev.pushkin.jvmresearch;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(2)
public class StringProcessingBenchmark {

    private static final Pattern SEPARATOR = Pattern.compile("[-:]");

    @Param({"1000", "10000", "100000"})
    public int size;

    private List<String> values;

    @Setup
    public void setUp() {
        values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add("CLIENT-" + i + ":ORDER-" + (i * 17) + ":STATUS-RESOLVED-COMPLETED");
        }
    }

    @Benchmark
    public long splitAndNormalize() {
        long checksum = 0;
        for (String value : values) {
            String[] parts = SEPARATOR.split(value);
            for (String part : parts) {
                checksum += part.toLowerCase(Locale.ROOT).hashCode();
            }
        }
        return checksum;
    }

    @Benchmark
    public long manualScan() {
        long checksum = 0;
        for (String value : values) {
            int tokenStart = 0;
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (current == '-' || current == ':') {
                    checksum += hashLowerCaseAscii(value, tokenStart, i);
                    tokenStart = i + 1;
                }
            }
            checksum += hashLowerCaseAscii(value, tokenStart, value.length());
        }
        return checksum;
    }

    private static int hashLowerCaseAscii(String value, int startInclusive, int endExclusive) {
        int hash = 0;
        for (int i = startInclusive; i < endExclusive; i++) {
            char current = value.charAt(i);
            if (current >= 'A' && current <= 'Z') {
                current = (char) (current + 32);
            }
            hash = 31 * hash + current;
        }
        return hash;
    }
}
