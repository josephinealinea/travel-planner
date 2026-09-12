package com.josephinealinea.planner.storage;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * One read/write lock per storage key (a trip slug, or the users / trip-index
 * files). The file store is process-local, so this is the only concurrency
 * control there is — which is exactly why the README says single-instance until
 * the database flag grows a real implementation.
 */
@Component
public class TripLocks {

    /** Leading space keeps these from colliding with any real trip slug. */
    public static final String USERS = " users";
    public static final String TRIP_INDEX = " trip-index";

    private final Map<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public <T> T read(String key, Supplier<T> action) {
        ReentrantReadWriteLock lock = lockFor(key);
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T write(String key, Supplier<T> action) {
        ReentrantReadWriteLock lock = lockFor(key);
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void writeVoid(String key, Runnable action) {
        write(key, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Takes two write locks in a stable order, so operations spanning users.yml
     * and a trip file cannot deadlock against each other.
     */
    public <T> T write(String first, String second, Supplier<T> action) {
        if (first.equals(second)) return write(first, action);
        String low = first.compareTo(second) <= 0 ? first : second;
        String high = low.equals(first) ? second : first;
        return write(low, () -> write(high, action));
    }

    private ReentrantReadWriteLock lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    }
}
