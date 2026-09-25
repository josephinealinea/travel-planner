package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PlanTemplatesTest {

    private final PlanTemplates templates = new PlanTemplates();

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private static Destination place(String id, String name) {
        Destination d = new Destination();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static ChecklistItem item(ChecklistCategory category) {
        ChecklistItem item = new ChecklistItem();
        item.setCategory(category);
        item.setDescription("whatever the member wrote");
        return item;
    }

    private String suggest(ChecklistCategory category) {
        Destination cusco = place("d1", "Cusco");
        return templates.forChecklistItem(item(category), cusco, List.of(cusco), "PEN").description();
    }

    @Test
    void englishSuggestionsAreUnchanged() {
        assertThat(suggest(ChecklistCategory.TRANSPORTATION)).isEqualTo("Transport to Cusco");
        assertThat(suggest(ChecklistCategory.LODGING)).isEqualTo("Hotel accommodation in Cusco");
        assertThat(suggest(ChecklistCategory.ACTIVITIES)).isEqualTo("Activity in Cusco");
        assertThat(suggest(ChecklistCategory.SHOPPING)).isEqualTo("Shopping in Cusco");
        assertThat(suggest(ChecklistCategory.FOOD)).isEqualTo("Dinner at Cusco");
    }

    @Test
    void aJourneyFromTheStopBeforeNamesBothEnds() {
        Destination lima = place("d0", "Lima");
        Destination cusco = place("d1", "Cusco");
        cusco.setSortOrder(1);

        assertThat(templates.forChecklistItem(item(ChecklistCategory.TRANSPORTATION), cusco,
                List.of(lima, cusco), "PEN").description())
                .isEqualTo("Flight (XX 000) from Lima to Cusco");
    }

    @Test
    void suggestionsAreWrittenInTheRequestsLanguage() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("xx"));

        assertThat(suggest(ChecklistCategory.FOOD)).isEqualTo("[xx] Dinner at Cusco");
    }

    @Test
    void aPlaceNameWithPunctuationIsPrintedAsItIs() {
        Destination odd = place("d1", "St. John's {old}");
        assertThat(templates.forChecklistItem(item(ChecklistCategory.ACTIVITIES), odd, List.of(odd), "PEN")
                .description()).isEqualTo("Activity in St. John's {old}");
    }
}
