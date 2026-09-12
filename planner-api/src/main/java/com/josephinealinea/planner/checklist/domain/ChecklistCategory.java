package com.josephinealinea.planner.checklist.domain;

/**
 * The enumerated category on every checklist and itinerary row. The lowercase
 * key matches the category names already used by the Jekyll site's
 * _data/travels/budget_categories.yml, so published pages reuse its icons and
 * colours.
 */
public enum ChecklistCategory {

    TRANSPORTATION("transport", "Transportation"),
    LODGING("lodging", "Lodging"),
    ACTIVITIES("activities", "Activities"),
    OTHERS("other", "Others");

    private final String dataKey;
    private final String label;

    ChecklistCategory(String dataKey, String label) {
        this.dataKey = dataKey;
        this.label = label;
    }

    public String dataKey() { return dataKey; }
    public String label() { return label; }
}
