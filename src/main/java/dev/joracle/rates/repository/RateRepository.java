package dev.joracle.rates.repository;

import dev.joracle.rates.model.RateSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Storage contract for rate snapshots.
 * Allows transparent swapping between InMemory and future Oracle Database implementations.
 */
public interface RateRepository {

    /**
     * Persists a new snapshot of exchange rates.
     */
    void save(RateSnapshot snapshot);

    /**
     * Retrieves the most recent snapshot available.
     */
    Optional<RateSnapshot> findLatest();

    /**
     * Retrieves recent snapshots in descending chronological order.
     *
     * @param limit maximum number of items to return
     */
    List<RateSnapshot> findRecent(int limit);
}
