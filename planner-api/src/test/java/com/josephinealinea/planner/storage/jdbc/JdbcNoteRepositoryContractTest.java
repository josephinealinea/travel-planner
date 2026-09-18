package com.josephinealinea.planner.storage.jdbc;

import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import org.junit.jupiter.api.BeforeAll;

/**
 * The per-trip contract against PostgreSQL, through the test-only
 * {@link NoteRepository}. The same assertions run against YAML in
 * YamlDestinationRepositoryContractTest, so this is the proof that
 * TripScopedJdbcRepository behaves as a YAML list does.
 *
 * This is also the template for Phase 2: a module's contract test is this
 * class with its own repository and entity.
 */
@PostgresTest
class JdbcNoteRepositoryContractTest extends TripScopedRepositoryContract<NoteRepository.Note> {

    @BeforeAll
    static void table() {
        NoteRepository.createTable(PostgresTestDatabase.jdbc());
    }

    private final NoteRepository repository =
            new NoteRepository(PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected TripScopedRepository<NoteRepository.Note> repository() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip("trip-" + slug, slug);
    }

    @Override
    protected NoteRepository.Note entity(String id, String label) {
        return new NoteRepository.Note(id, label);
    }

    @Override
    protected String idOf(NoteRepository.Note entity) {
        return entity.id;
    }

    @Override
    protected String labelOf(NoteRepository.Note entity) {
        return entity.text;
    }
}
