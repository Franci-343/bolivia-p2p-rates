package dev.joracle.rates.repository;

import dev.joracle.rates.model.RateSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, thread-safe in-memory implementation of {@link RateRepository}.
 * Maintains an atomic reference to the latest snapshot and a bounded FIFO history.
 */
public class InMemoryRateRepository implements RateRepository {

    private final int maxHistorySize;
    private final AtomicReference<RateSnapshot> latestSnapshot = new AtomicReference<>();
    private final ConcurrentLinkedDeque<RateSnapshot> history = new ConcurrentLinkedDeque<>();

    public InMemoryRateRepository() {
        this(100);
    }

    public InMemoryRateRepository(int maxHistorySize) {
        this.maxHistorySize = maxHistorySize;
    }

    @Override
    public void save(RateSnapshot snapshot) {
        if (snapshot == null) return;

        latestSnapshot.set(snapshot);
        history.addFirst(snapshot);

        // Trim older history if exceeding capacity
        while (history.size() > maxHistorySize) {
            history.pollLast();
        }
    }

    @Override
    public Optional<RateSnapshot> findLatest() {
        return Optional.ofNullable(latestSnapshot.get());
    }

    @Override
    public List<RateSnapshot> findRecent(int limit) {
        int target = Math.max(0, limit);
        List<RateSnapshot> result = new ArrayList<>();
        for (RateSnapshot snapshot : history) {
            if (result.size() >= target) break;
            result.add(snapshot);
        }
        return List.copyOf(result);
    }
}
