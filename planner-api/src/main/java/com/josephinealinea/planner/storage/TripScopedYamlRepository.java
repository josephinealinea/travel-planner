package com.josephinealinea.planner.storage;

import com.fasterxml.jackson.core.type.TypeReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Destinations, checklist items, itinerary entries and budget rows are all
 * stored the same way: one YAML list per trip, replaced wholesale on every
 * write under that trip's lock. This holds that behaviour once.
 *
 * Subclasses supply the file path and how to read an entity's id.
 *
 * The YAML half of {@link TripScopedRepository}; database mode has the other.
 */
public abstract class TripScopedYamlRepository<T> implements TripScopedRepository<T> {

    protected final YamlStore store;
    protected final YamlPaths paths;
    protected final TripLocks locks;

    private final TypeReference<List<T>> listType;
    private final Function<T, String> idOf;

    protected TripScopedYamlRepository(YamlStore store,
                                      YamlPaths paths,
                                      TripLocks locks,
                                      TypeReference<List<T>> listType,
                                      Function<T, String> idOf) {
        this.store = store;
        this.paths = paths;
        this.locks = locks;
        this.listType = listType;
        this.idOf = idOf;
    }

    /** Where this entity's list lives for the given trip slug. */
    protected abstract Path fileFor(String tripSlug);

    @Override
    public List<T> findAll(String tripSlug) {
        return locks.read(tripSlug, () -> store.readList(fileFor(tripSlug), listType));
    }

    @Override
    public Optional<T> findById(String tripSlug, String id) {
        return findAll(tripSlug).stream().filter(item -> idOf.apply(item).equals(id)).findFirst();
    }

    /** Inserts or replaces by id, preserving list order. */
    @Override
    public T save(String tripSlug, T entity) {
        return locks.write(tripSlug, () -> {
            List<T> items = new ArrayList<>(store.readList(fileFor(tripSlug), listType));
            String id = idOf.apply(entity);
            int existing = indexOf(items, id);
            if (existing >= 0) items.set(existing, entity);
            else items.add(entity);
            store.write(fileFor(tripSlug), items);
            return entity;
        });
    }

    /** One write for a batch — used when seeding a destination's three checklist items. */
    @Override
    public List<T> saveAll(String tripSlug, List<T> entities) {
        if (entities.isEmpty()) return entities;
        return locks.write(tripSlug, () -> {
            List<T> items = new ArrayList<>(store.readList(fileFor(tripSlug), listType));
            for (T entity : entities) {
                int existing = indexOf(items, idOf.apply(entity));
                if (existing >= 0) items.set(existing, entity);
                else items.add(entity);
            }
            store.write(fileFor(tripSlug), items);
            return entities;
        });
    }

    @Override
    public void delete(String tripSlug, String id) {
        locks.writeVoid(tripSlug, () -> {
            List<T> remaining = store.readList(fileFor(tripSlug), listType).stream()
                    .filter(item -> !idOf.apply(item).equals(id))
                    .toList();
            store.write(fileFor(tripSlug), remaining);
        });
    }

    /**
     * Replaces the whole list in one write. Used for reordering and for the
     * cascades that touch several rows at once, so they cannot half-apply.
     */
    @Override
    public void replaceAll(String tripSlug, List<T> items) {
        locks.writeVoid(tripSlug, () -> store.write(fileFor(tripSlug), items));
    }

    private int indexOf(List<T> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (idOf.apply(items.get(i)).equals(id)) return i;
        }
        return -1;
    }
}
