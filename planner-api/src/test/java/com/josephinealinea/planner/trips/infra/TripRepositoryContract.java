package com.josephinealinea.planner.trips.infra;

import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.storage.EveryField;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.domain.TripStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every behaviour {@link YamlTripRepository} has, as assertions any
 * {@link TripRepository} must pass. Run against a temp directory by
 * {@code YamlTripRepositoryContractTest} and against PostgreSQL by
 * {@code JdbcTripRepositoryContractTest}.
 *
 * The two stores differ in exactly one field on purpose —
 * {@code Trip.exchangeRates}, which the database does not keep — and a
 * subclass names it in {@link #unstoredFields()} rather than the contract
 * pretending otherwise.
 */
public abstract class TripRepositoryContract {

    protected abstract TripRepository repository();

    /**
     * A user row for this id, where the store needs one: the database's
     * {@code trips.owner_user_id} and {@code trip_members.user_id} are
     * foreign keys to {@code users}, and in the app the account always exists
     * first because accounts are only created by inviting someone.
     */
    protected abstract void givenUser(String userId);

    /** Stores something in every per-trip place the trip's deletion must clear. */
    protected abstract void givenDataInEveryPerTripStore(Trip trip);

    /** How many of those things are still there. */
    protected abstract long perTripDataRemaining(Trip trip);

    /** Trip fields this store deliberately does not keep; empty for YAML. */
    protected Set<String> unstoredFields() {
        return Set.of();
    }

    // ── every field ─────

    /**
     * A trip, a member and a publish request, each with every field away from
     * its default, saved and read back whole. {@link EveryField} makes a field
     * added to any of the three later fail here until it is mapped.
     */
    @Test
    void everyFieldSurvivesARoundTrip() {
        Trip trip = everyFieldSet();
        // exchangeRates is set, so the YAML round trip proves it; the database
        // excludes it through unstoredFields() and asserts it comes back empty.
        EveryField.assertEverySet(trip, Trip::new, Set.of());
        EveryField.assertEverySet(trip.getMembers().get(0), TripMember::new, Set.of());
        EveryField.assertEverySet(trip.getPublishRequests().get(0), PublishRequest::new, Set.of());

        Trip saved = repository().save(trip);

        Trip read = repository().findById(saved.getId()).orElseThrow();
        assertThat(read)
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .ignoringFields(unstoredFields().toArray(String[]::new))
                .isEqualTo(saved);
    }

    @Test
    void absentValuesReadBackAsTheDomainDefaults() {
        givenUser("owner");
        Trip trip = trip("t-1", "latam", "owner");
        trip.setStatus(null);
        trip.setDisplayCurrency(null);
        TripMember member = new TripMember();
        member.setUserId("owner");
        member.setRole(null);
        trip.setMembers(new ArrayList<>(List.of(member)));
        PublishRequest request = new PublishRequest();
        request.setId("r-1");
        request.setStatus(null);
        trip.setPublishRequests(new ArrayList<>(List.of(request)));

        repository().save(trip);

        Trip read = repository().findById("t-1").orElseThrow();
        assertThat(read.getStatus()).isEqualTo(TripStatus.DRAFT);
        assertThat(read.getDisplayCurrency()).isEqualTo("EUR");
        assertThat(read.getMembers().get(0).getRole()).isEqualTo(TripRole.MEMBER);
        assertThat(read.getPublishRequests().get(0).getStatus()).isEqualTo(PublishRequest.Status.PENDING);
        assertThat(read.getTitle()).isNull();
        assertThat(read.getStartDate()).isNull();
    }

    // ── members and requests ─────

    /** Member order decides who takes the odd cent, so it is data. */
    @Test
    void membersKeepTheirOrderIncludingAReorder() {
        for (String id : List.of("owner", "b", "c", "d")) givenUser(id);
        Trip trip = trip("t-1", "latam", "owner");
        trip.setMembers(new ArrayList<>(List.of(
                member("owner", TripRole.OWNER), member("c", TripRole.MEMBER),
                member("b", TripRole.MEMBER), member("d", TripRole.MEMBER))));
        repository().save(trip);

        assertThat(memberIds("t-1")).containsExactly("owner", "c", "b", "d");

        Trip read = repository().findById("t-1").orElseThrow();
        List<TripMember> reordered = new ArrayList<>(read.getMembers());
        reordered.add(0, reordered.remove(3));   // d to the front
        read.setMembers(reordered);
        repository().save(read);

        assertThat(memberIds("t-1")).containsExactly("d", "owner", "c", "b");
    }

    @Test
    void aMemberRemovedFromTheListIsGoneAfterSaving() {
        for (String id : List.of("owner", "b", "c")) givenUser(id);
        Trip trip = trip("t-1", "latam", "owner");
        trip.setMembers(new ArrayList<>(List.of(
                member("owner", TripRole.OWNER), member("b", TripRole.MEMBER), member("c", TripRole.MEMBER))));
        repository().save(trip);

        Trip read = repository().findById("t-1").orElseThrow();
        read.getMembers().removeIf(m -> m.getUserId().equals("b"));
        repository().save(read);

        assertThat(memberIds("t-1")).containsExactly("owner", "c");
        assertThat(repository().findAllForUser("b")).isEmpty();
    }

    @Test
    void publishRequestsRoundTripInOrder() {
        givenUser("owner");
        Trip trip = trip("t-1", "latam", "owner");
        PublishRequest pending = request("r-2", PublishRequest.Status.PENDING);
        PublishRequest rejected = request("r-1", PublishRequest.Status.REJECTED);
        rejected.setDecidedAt(Instant.parse("2026-09-02T10:00:00Z"));
        rejected.setDecidedByUserId("owner");
        trip.setPublishRequests(new ArrayList<>(List.of(pending, rejected)));
        repository().save(trip);

        Trip read = repository().findById("t-1").orElseThrow();
        assertThat(read.getPublishRequests()).extracting(PublishRequest::getId).containsExactly("r-2", "r-1");
        assertThat(read.getPublishRequests().get(1).getStatus()).isEqualTo(PublishRequest.Status.REJECTED);
        assertThat(read.getPublishRequests().get(1).getDecidedAt()).isEqualTo(rejected.getDecidedAt());

        read.getPublishRequests().get(0).setStatus(PublishRequest.Status.CANCELLED);
        read.getPublishRequests().remove(1);
        repository().save(read);

        assertThat(repository().findById("t-1").orElseThrow().getPublishRequests())
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getId()).isEqualTo("r-2");
                    assertThat(r.getStatus()).isEqualTo(PublishRequest.Status.CANCELLED);
                });
    }

    // ── timestamps ─────

    /** Trip is the one record whose repository stamps its own times (CLAUDE.md). */
    @Test
    void saveStampsUpdatedAtEveryTimeAndCreatedAtOnce() {
        givenUser("owner");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Trip trip = trip("t-1", "latam", "owner");

        Trip saved = repository().save(trip);

        assertThat(saved.getCreatedAt()).isNotNull().isAfterOrEqualTo(before.minusMillis(1));
        assertThat(saved.getUpdatedAt()).isNotNull();
        Trip first = repository().findById("t-1").orElseThrow();
        assertThat(first.getCreatedAt()).isEqualTo(saved.getCreatedAt());

        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        first.setUpdatedAt(old);
        repository().save(first);

        Trip second = repository().findById("t-1").orElseThrow();
        assertThat(second.getCreatedAt()).as("created once").isEqualTo(saved.getCreatedAt());
        assertThat(second.getUpdatedAt()).as("stamped on every save").isAfter(old);
    }

    @Test
    void aCreatedAtAlreadySetIsKept() {
        givenUser("owner");
        Trip trip = trip("t-1", "latam", "owner");
        Instant created = Instant.parse("2025-03-01T08:00:00Z");
        trip.setCreatedAt(created);

        repository().save(trip);

        assertThat(repository().findById("t-1").orElseThrow().getCreatedAt()).isEqualTo(created);
    }

    // ── finding ─────

    @Test
    void findByIdAndBySlugAgree() {
        givenUser("owner");
        repository().save(trip("t-1", "latam", "owner"));

        assertThat(repository().findById("t-1")).get().extracting(Trip::getSlug).isEqualTo("latam");
        assertThat(repository().findBySlug("latam")).get().extracting(Trip::getId).isEqualTo("t-1");
        assertThat(repository().findById("nope")).isEmpty();
        assertThat(repository().findBySlug("nope")).isEmpty();
    }

    @Test
    void slugExistsOnlyForASavedTrip() {
        givenUser("owner");
        assertThat(repository().slugExists("latam")).isFalse();

        repository().save(trip("t-1", "latam", "owner"));

        assertThat(repository().slugExists("latam")).isTrue();
        assertThat(repository().slugExists("latam-2026")).isFalse();
    }

    @Test
    void savingATripTwiceKeepsOneTrip() {
        givenUser("owner");
        repository().save(trip("t-1", "latam", "owner"));
        Trip read = repository().findById("t-1").orElseThrow();
        read.setTitle("LATAM, revised");
        repository().save(read);

        assertThat(repository().findAllForUser("owner")).singleElement()
                .extracting(Trip::getTitle).isEqualTo("LATAM, revised");
    }

    /**
     * Latest start date first, undated last — and only trips the user is in
     * the member list of, which in the app always includes the owner.
     */
    @Test
    void findAllForUserListsTheUsersTripsLatestFirst() {
        for (String id : List.of("owner", "ana", "ben")) givenUser(id);
        Trip autumn = trip("t-1", "autumn", "owner");
        autumn.setStartDate(LocalDate.of(2026, 10, 24));
        autumn.getMembers().add(member("ana", TripRole.MEMBER));
        Trip undated = trip("t-2", "someday", "owner");
        undated.getMembers().add(member("ana", TripRole.MEMBER));
        Trip spring = trip("t-3", "spring", "owner");
        spring.setStartDate(LocalDate.of(2027, 4, 1));
        spring.getMembers().add(member("ana", TripRole.MEMBER));
        Trip notAnas = trip("t-4", "elsewhere", "ben");
        notAnas.setStartDate(LocalDate.of(2028, 1, 1));
        for (Trip trip : List.of(autumn, undated, spring, notAnas)) repository().save(trip);

        assertThat(repository().findAllForUser("ana")).extracting(Trip::getId)
                .containsExactly("t-3", "t-1", "t-2");
        assertThat(repository().findAllForUser("owner")).extracting(Trip::getId)
                .containsExactly("t-3", "t-1", "t-2");
        assertThat(repository().findAllForUser("ben")).extracting(Trip::getId)
                .containsExactly("t-4");
        assertThat(repository().findAllForUser("nobody")).isEmpty();
    }

    /** What the YAML filter (Trip.isMember) does, pinned so the database agrees. */
    @Test
    void owningATripWithoutBeingInItsMemberListDoesNotListIt() {
        givenUser("owner");
        givenUser("ana");
        Trip trip = trip("t-1", "latam", "owner");
        trip.setMembers(new ArrayList<>(List.of(member("ana", TripRole.MEMBER))));
        repository().save(trip);

        assertThat(repository().findAllForUser("owner")).isEmpty();
        assertThat(repository().findAllForUser("ana")).hasSize(1);
    }

    @Test
    void findAllForUserReturnsMembersAndRequestsWithEachTrip() {
        for (String id : List.of("owner", "ana")) givenUser(id);
        Trip one = trip("t-1", "one", "owner");
        one.getMembers().add(member("ana", TripRole.MEMBER));
        one.getPublishRequests().add(request("r-1", PublishRequest.Status.PENDING));
        Trip two = trip("t-2", "two", "owner");
        repository().save(one);
        repository().save(two);

        List<Trip> trips = repository().findAllForUser("owner");

        Trip readOne = trips.stream().filter(t -> t.getId().equals("t-1")).findFirst().orElseThrow();
        Trip readTwo = trips.stream().filter(t -> t.getId().equals("t-2")).findFirst().orElseThrow();
        assertThat(readOne.getMembers()).extracting(TripMember::getUserId).containsExactly("owner", "ana");
        assertThat(readOne.getPublishRequests()).extracting(PublishRequest::getId).containsExactly("r-1");
        assertThat(readTwo.getMembers()).extracting(TripMember::getUserId).containsExactly("owner");
        assertThat(readTwo.getPublishRequests()).isEmpty();
    }

    // ── deleting ─────

    /** "Deleting a trip must delete everything it put anywhere" (CLAUDE.md). */
    @Test
    void deletingATripRemovesItAndEverythingStoredAgainstIt() {
        givenUser("owner");
        Trip doomed = trip("t-1", "latam", "owner");
        Trip kept = trip("t-2", "asia", "owner");
        repository().save(doomed);
        repository().save(kept);
        givenDataInEveryPerTripStore(doomed);
        givenDataInEveryPerTripStore(kept);
        assertThat(perTripDataRemaining(doomed)).isPositive();
        long keptBefore = perTripDataRemaining(kept);

        repository().delete(doomed);

        assertThat(repository().findById("t-1")).isEmpty();
        assertThat(repository().findBySlug("latam")).isEmpty();
        assertThat(repository().slugExists("latam")).isFalse();
        assertThat(perTripDataRemaining(doomed)).isZero();

        assertThat(repository().findById("t-2")).isPresent();
        assertThat(perTripDataRemaining(kept)).as("another trip's data is untouched")
                .isEqualTo(keptBefore);
    }

    // ── fixtures ─────

    private List<String> memberIds(String tripId) {
        return repository().findById(tripId).orElseThrow()
                .getMembers().stream().map(TripMember::getUserId).toList();
    }

    protected static Trip trip(String id, String slug, String ownerUserId) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setSlug(slug);
        trip.setOwnerUserId(ownerUserId);
        trip.getMembers().add(member(ownerUserId, TripRole.OWNER));
        return trip;
    }

    protected static TripMember member(String userId, TripRole role) {
        TripMember member = new TripMember();
        member.setUserId(userId);
        member.setRole(role);
        member.setInvitedByUserId("owner");
        member.setInvitedAt(Instant.parse("2026-08-01T12:00:00Z"));
        return member;
    }

    protected static PublishRequest request(String id, PublishRequest.Status status) {
        PublishRequest request = new PublishRequest();
        request.setId(id);
        request.setStatus(status);
        request.setRequestedByUserId("ana");
        request.setRequestedAt(Instant.parse("2026-09-01T09:00:00Z"));
        return request;
    }

    /** Every field of the trip, its first member and first request away from the default. */
    private Trip everyFieldSet() {
        for (String id : List.of("owner", "ana")) givenUser(id);
        Trip trip = new Trip();
        trip.setId("t-every");
        trip.setSlug("every-field");
        trip.setTitle("Every field");
        trip.setStartDate(LocalDate.of(2026, 10, 24));
        trip.setEndDate(LocalDate.of(2026, 11, 20));
        trip.setOwnerUserId("owner");
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setDisplayCurrency("SGD");
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("PEN", new BigDecimal("3.891723"));
        trip.setExchangeRates(rates);
        trip.setPublishedAt(Instant.parse("2026-09-10T11:12:13.456789Z"));
        trip.setCreatedAt(Instant.parse("2026-01-02T03:04:05.123456Z"));
        trip.setUpdatedAt(Instant.parse("2026-01-03T03:04:05.654321Z"));
        trip.setCreatedByUserId("owner");
        trip.setUpdatedByUserId("ana");

        TripMember owner = new TripMember();
        owner.setUserId("owner");
        owner.setRole(TripRole.OWNER);
        owner.setInvitedByUserId("owner");
        owner.setInvitedAt(Instant.parse("2026-01-02T03:04:05.123456Z"));
        trip.setMembers(new ArrayList<>(List.of(owner, member("ana", TripRole.MEMBER))));

        PublishRequest request = new PublishRequest();
        request.setId("r-1");
        request.setRequestedByUserId("ana");
        request.setStatus(PublishRequest.Status.APPROVED);
        request.setNote("Ready for the family");
        request.setTheme("dark");
        request.setRequestedAt(Instant.parse("2026-09-09T09:09:09.090909Z"));
        request.setDecidedAt(Instant.parse("2026-09-10T11:12:13.456789Z"));
        request.setDecidedByUserId("owner");
        trip.setPublishRequests(new ArrayList<>(List.of(request)));
        return trip;
    }
}
