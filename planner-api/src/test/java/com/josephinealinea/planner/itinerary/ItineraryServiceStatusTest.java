package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItineraryServiceStatusTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String USER_ID = "user-1";

    private ItineraryService service;
    private TripRepository trips;
    private Trip trip;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        DestinationRepository destinations = new YamlDestinationRepository(store, paths, locks);
        TripCountries tripCountries = new TripCountries(destinations);
        ChecklistRepository checklistRepository = new YamlChecklistRepository(store, paths, locks);
        ItineraryRepository itineraryRepository = new YamlItineraryRepository(store, paths, locks);
        BudgetRepository budgetRepository = new YamlBudgetRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.getMembers().add(new TripMember(USER_ID, TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        ChecklistService checklistService =
                new ChecklistService(checklistRepository, itineraryRepository, access, tripCountries);

        service = new ItineraryService(itineraryRepository, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budgetRepository), new CountryCatalog(RestClient.create()), tripCountries);
    }

    @Test
    void aNewEntryWithNoStatusDefaultsToFinal() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));
        assertThat(plan.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void aNewEntryCanBeCreatedPending() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));
        assertThat(plan.getStatus()).isEqualTo(ItineraryStatus.PENDING);
    }

    @Test
    void savingAStaysOwningRowPendingMarksEveryNightOfItPendingToo() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        service.create(trip.getId(), USER_ID, stay);

        List<ItineraryItem> all = service.list(trip.getId(), USER_ID);
        assertThat(all).allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItineraryStatus.PENDING));
        assertThat(all.size()).isGreaterThan(1);
    }

    @Test
    void planGroupOfASingleNightWithNoStayIsJustItself() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));
        assertThat(service.planGroupOf(trip, plan)).extracting(ItineraryItem::getId).containsExactly(plan.getId());
    }

    @Test
    void planGroupOfAStayIsTheOwningRowAndEveryNight() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, null);
        ItineraryItem checkIn = service.create(trip.getId(), USER_ID, stay);

        List<ItineraryItem> group = service.planGroupOf(trip, checkIn);
        assertThat(group).hasSize(3); // check-in + two more nights
        assertThat(group).extracting(ItineraryItem::getId).contains(checkIn.getId());

        // Asking from a later night's own row finds the same group.
        ItineraryItem night = group.stream().filter(i -> !i.getId().equals(checkIn.getId())).findFirst().orElseThrow();
        assertThat(service.planGroupOf(trip, night)).hasSize(3);
    }

    // ---- setStatus ----

    @Test
    void theCreatorCanFinalizeTheirOwnPendingEntry() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem updated = service.setStatus(trip.getId(), USER_ID, plan.getId(), ItineraryStatus.FINAL);

        assertThat(updated.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void someoneElseCannotFinalizeAnotherMembersPendingEntry() {
        trip.getMembers().add(new TripMember("user-2", TripRole.MEMBER, USER_ID));
        trips.save(trip);
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        assertThatThrownBy(() -> service.setStatus(trip.getId(), "user-2", plan.getId(), ItineraryStatus.FINAL))
                .isInstanceOf(com.josephinealinea.planner.shared.ApiException.class)
                .satisfies(ex -> assertThat(((com.josephinealinea.planner.shared.ApiException) ex).status().value()).isEqualTo(403));
    }

    @Test
    void setStatusRefusesToGoBackToPending() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));

        assertThatThrownBy(() -> service.setStatus(trip.getId(), USER_ID, plan.getId(), ItineraryStatus.PENDING))
                .isInstanceOf(com.josephinealinea.planner.shared.ApiException.class)
                .satisfies(ex -> assertThat(((com.josephinealinea.planner.shared.ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void finalizingAStaysOwningRowFinalizesEveryNightOfIt() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        ItineraryItem checkIn = service.create(trip.getId(), USER_ID, stay);

        service.setStatus(trip.getId(), USER_ID, checkIn.getId(), ItineraryStatus.FINAL);

        assertThat(service.list(trip.getId(), USER_ID))
                .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItineraryStatus.FINAL));
    }

    // ---- approve / unapprove ----

    @Test
    void approvingTwiceIsANoOp() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        service.approve(trip.getId(), USER_ID, plan.getId());
        ItineraryItem again = service.approve(trip.getId(), USER_ID, plan.getId());

        assertThat(again.getApprovedByUserIds()).containsExactly(USER_ID);
    }

    @Test
    void unapprovingSomeoneWhoNeverApprovedIsANoOp() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem result = service.unapprove(trip.getId(), USER_ID, plan.getId());

        assertThat(result.getApprovedByUserIds()).isEmpty();
    }

    @Test
    void approvingWithNoOtherParticipantNeverAutoFinalizes() {
        // Solo trip: the only resolved participant is the creator.
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem result = service.approve(trip.getId(), USER_ID, plan.getId());

        assertThat(result.getStatus()).isEqualTo(ItineraryStatus.PENDING);
    }

    @Test
    void onceEveryNonCreatorParticipantApprovesItAutoFinalizes() {
        trip.getMembers().add(new TripMember("user-2", TripRole.MEMBER, USER_ID));
        trips.save(trip);
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));

        ItineraryItem result = service.approve(trip.getId(), "user-2", plan.getId());

        assertThat(result.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void aDepartedApproverIsIgnoredByTheThresholdCheck() {
        trip.getMembers().add(new TripMember("user-2", TripRole.MEMBER, USER_ID));
        trip.getMembers().add(new TripMember("user-3", TripRole.MEMBER, USER_ID));
        trips.save(trip);
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));
        service.approve(trip.getId(), "user-2", plan.getId());

        // user-3 leaves; only user-2 (already approved) remains besides the creator.
        trip.getMembers().removeIf(m -> m.getUserId().equals("user-3"));
        trips.save(trip);

        // Re-approving user-2 (a no-op on their own vote) should still resolve
        // "everyone but the creator has approved" against current membership.
        ItineraryItem result = service.approve(trip.getId(), "user-2", plan.getId());

        assertThat(result.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    // ---- update()'s own status field (the form's "This itinerary plan is Final" checkbox) ----

    @Test
    void someoneElseCannotChangeStatusThroughAPlainUpdate() {
        trip.getMembers().add(new TripMember("user-2", TripRole.MEMBER, USER_ID));
        trips.save(trip);
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithStatus("Dinner", null, null));

        ItineraryService.Input patch = new ItineraryService.Input(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        assertThatThrownBy(() -> service.update(trip.getId(), "user-2", plan.getId(), patch))
                .isInstanceOf(com.josephinealinea.planner.shared.ApiException.class)
                .satisfies(ex -> assertThat(((com.josephinealinea.planner.shared.ApiException) ex).status().value()).isEqualTo(403));
        assertThat(reload(plan.getId()).getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void editingAStaysOwningRowThroughUpdateChangesStatusOnTheWholeStay() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                LocalDateTime.of(2026, 10, 24, 15, 0), LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null, null);
        ItineraryItem checkIn = service.create(trip.getId(), USER_ID, stay);

        ItineraryService.Input patch = new ItineraryService.Input(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        service.update(trip.getId(), USER_ID, checkIn.getId(), patch);

        assertThat(service.list(trip.getId(), USER_ID))
                .allSatisfy(i -> assertThat(i.getStatus()).isEqualTo(ItineraryStatus.PENDING));
    }

    @Test
    void reopeningAFinalizedEntryAsPendingThroughUpdateClearsStaleApprovals() {
        trip.getMembers().add(new TripMember("user-2", TripRole.MEMBER, USER_ID));
        trips.save(trip);
        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                inputWithStatus("Dinner", null, ItineraryStatus.PENDING));
        // user-2 is the only other participant, so their approval auto-finalizes it.
        service.approve(trip.getId(), "user-2", plan.getId());
        assertThat(reload(plan.getId()).getStatus()).isEqualTo(ItineraryStatus.FINAL);

        // The creator reopens it as Pending — a stale approval must not survive
        // to immediately re-finalize a plan nobody has seen the new version of.
        ItineraryService.Input reopen = new ItineraryService.Input(null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, ItineraryStatus.PENDING);
        service.update(trip.getId(), USER_ID, plan.getId(), reopen);

        ItineraryItem reloaded = reload(plan.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ItineraryStatus.PENDING);
        assertThat(reloaded.getApprovedByUserIds()).isEmpty();
    }

    private ItineraryItem reload(String id) {
        return service.list(trip.getId(), USER_ID).stream()
                .filter(i -> i.getId().equals(id)).findFirst().orElseThrow();
    }

    private ItineraryService.Input inputWithStatus(String description, ChecklistCategory category, ItineraryStatus status) {
        return new ItineraryService.Input(null, category, description, null, null, null, null, null,
                null, null, null, null, null, null, null, null, status);
    }
}
