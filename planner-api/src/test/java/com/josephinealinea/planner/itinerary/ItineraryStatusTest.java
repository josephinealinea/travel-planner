package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.domain.ItineraryStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ItineraryStatusTest {

    @Test
    void aFreshItemDefaultsToFinal() {
        assertThat(new ItineraryItem().getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void settingNullReadsAsFinal() {
        ItineraryItem item = new ItineraryItem();
        item.setStatus(ItineraryStatus.PENDING);
        item.setStatus(null);
        assertThat(item.getStatus()).isEqualTo(ItineraryStatus.FINAL);
    }

    @Test
    void approvedByUserIdsDefaultsToAnEmptyMutableCopy() {
        ItineraryItem item = new ItineraryItem();
        assertThat(item.getApprovedByUserIds()).isEmpty();

        item.setApprovedByUserIds(List.of("user-ana"));
        assertThat(item.getApprovedByUserIds()).containsExactly("user-ana");

        item.setApprovedByUserIds(null);
        assertThat(item.getApprovedByUserIds()).isEmpty();
    }
}
