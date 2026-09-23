package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who is on what, worked out in one place. See the travellers spec, section 2. */
class TravellersTest {

    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";
    private static final String KIM = "user-kim";

    private static Trip trip(String... memberIds) {
        Trip trip = new Trip();
        trip.setId("trip-1");
        for (String id : memberIds) {
            trip.getMembers().add(new TripMember(id, TripRole.MEMBER, null));
        }
        return trip;
    }

    private static Destination destination(String id, List<String> travellers) {
        Destination d = new Destination();
        d.setId(id);
        d.setTravellerIds(travellers);
        return d;
    }

    private static ChecklistItem item(String id, String seededFrom, List<String> travellers) {
        ChecklistItem c = new ChecklistItem();
        c.setId(id);
        c.setSeededFromDestinationId(seededFrom);
        c.setTravellerIds(travellers);
        return c;
    }

    private static ItineraryItem entry(String id, String checklistItemId, String planId, List<String> travellers) {
        ItineraryItem i = new ItineraryItem();
        i.setId(id);
        i.setChecklistItemId(checklistItemId);
        i.setPlanId(planId);
        i.setTravellerIds(travellers);
        return i;
    }

    private final Destination cusco = destination("cusco", List.of(ALEX, SAM));
    private final Destination uyuni = destination("uyuni", null);
    private final ChecklistItem cuscoHotel = item("cusco-hotel", "cusco", null);
    private final ChecklistItem kimsTour = item("kims-tour", "cusco", List.of(KIM));
    private final ChecklistItem visas = item("visas", null, null);
    private final ItineraryItem hotelPlan = entry("hotel-plan", "cusco-hotel", null, null);
    private final ItineraryItem hotelNight = entry("hotel-night", "cusco-hotel", "hotel-plan", List.of(KIM));

    private Travellers travellers() {
        return Travellers.of(trip(ALEX, SAM, KIM),
                List.of(cusco, uyuni), List.of(cuscoHotel, kimsTour, visas), List.of(hotelPlan, hotelNight));
    }

    @Test
    void aDestinationWithNoListIsTheWholeTrip() {
        assertThat(travellers().ofDestination(uyuni)).isEmpty();
        assertThat(travellers().ofDestination(cusco)).containsExactly(ALEX, SAM);
    }

    @Test
    void aChecklistItemFollowsTheDestinationItWasSeededFromUntilSet() {
        assertThat(travellers().ofChecklistItem(cuscoHotel)).containsExactly(ALEX, SAM);
        assertThat(travellers().ofChecklistItem(kimsTour)).containsExactly(KIM);
        assertThat(travellers().ofChecklistItem(visas)).isEmpty();
    }

    @Test
    void anExplicitlyEmptyListIsTheWholeTripEvenUnderANamedParent() {
        ChecklistItem everyone = item("everyone", "cusco", List.of());
        Travellers t = Travellers.of(trip(ALEX, SAM, KIM), List.of(cusco), List.of(everyone), List.of());
        assertThat(t.ofChecklistItem(everyone)).isEmpty();
    }

    @Test
    void aPlanFollowsItsChecklistItemAndALaterDayAlwaysFollowsItsPlan() {
        assertThat(travellers().ofItineraryItem(hotelPlan)).containsExactly(ALEX, SAM);
        // The night carries a list of its own, and it is ignored.
        assertThat(travellers().ofItineraryItem(hotelNight)).containsExactly(ALEX, SAM);
    }

    @Test
    void aDeletedDestinationFallsThroughToTheWholeTrip() {
        Travellers t = Travellers.of(trip(ALEX, SAM, KIM), List.of(), List.of(cuscoHotel), List.of(hotelPlan));
        assertThat(t.ofChecklistItem(cuscoHotel)).isEmpty();
        assertThat(t.ofItineraryItem(hotelPlan)).isEmpty();
    }

    @Test
    void aBuddyWhoLeftDropsOutAndNobodyLeftMeansTheWholeTrip() {
        Travellers withoutSam = Travellers.of(trip(ALEX, KIM), List.of(cusco), List.of(kimsTour), List.of());
        assertThat(withoutSam.ofDestination(cusco)).containsExactly(ALEX);

        Travellers withoutKim = Travellers.of(trip(ALEX, SAM), List.of(cusco), List.of(kimsTour), List.of());
        assertThat(withoutKim.ofChecklistItem(kimsTour)).isEmpty();
    }

    @Test
    void mineListsWhatIncludesTheBuddyOrIsTheWholeTrip() {
        Travellers.Mine kims = travellers().mineFor(KIM);
        assertThat(kims.destinationIds()).containsExactly("uyuni");
        assertThat(kims.checklistItemIds()).containsExactly("kims-tour", "visas");
        assertThat(kims.itineraryItemIds()).isEmpty();

        Travellers.Mine sams = travellers().mineFor(SAM);
        assertThat(sams.destinationIds()).containsExactly("cusco", "uyuni");
        assertThat(sams.itineraryItemIds()).containsExactly("hotel-plan", "hotel-night");
    }

    @Test
    void namedListsOnlyWhatIsNotTheWholeTrip() {
        Travellers.Named named = travellers().named();
        assertThat(named.destinations()).containsOnlyKeys("cusco");
        assertThat(named.checklist()).containsOnlyKeys("cusco-hotel", "kims-tour");
        assertThat(named.itinerary()).containsOnlyKeys("hotel-plan", "hotel-night");
    }

    @Test
    void aChangeCanFollowTheParentSetTheWholeTripNameBuddiesOrLeaveItAlone() {
        Trip trip = trip(ALEX, SAM);
        assertThat(Travellers.change(trip, List.of(ALEX), null, true)).isNull();
        assertThat(Travellers.change(trip, List.of(ALEX), List.of(), null)).isNotNull().isEmpty();
        assertThat(Travellers.change(trip, null, List.of(SAM, ALEX), null)).containsExactly(SAM, ALEX);
        assertThat(Travellers.change(trip, List.of(ALEX), null, null)).containsExactly(ALEX);
        assertThat(Travellers.change(trip, null, null, null)).isNull();
        assertThatThrownBy(() -> Travellers.change(trip, null, List.of(KIM), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("travel buddies");
    }
}
