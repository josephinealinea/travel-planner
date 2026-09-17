import { countdownLabel, daysUntil, money, category } from '../../format.js';

/**
 * The Overview tab. Everything here is derived from the already-loaded trip
 * bundle — no extra requests.
 */
export function overviewTab() {
  return {
    stats() {
      const done = this.checklist.filter((item) => item.status === 'COMPLETED').length;
      return [
        { label: 'Destinations', value: this.destinations.length },
        { label: 'Checklist done', value: `${done} / ${this.checklist.length}` },
        { label: 'Itinerary entries', value: this.itinerary.length },
        // The signed-in member's own charged total, matching what the Budget
        // tab opens on. It moved into `charged` when the rollup was split in
        // two, and reading the old flat `budget.total` here left this stat
        // silently blank — money() answers '' for undefined, so the fallback
        // below swallowed it rather than anything failing loudly.
        { label: 'Travel cost',
          value: money(this.budget.charged?.total, this.budget.totalsCurrency) || '—' },
        { label: 'Departure', value: this.departureLabel() },
      ];
    },

    departureLabel() {
      const days = daysUntil(this.trip.startDate);
      if (days == null) return '—';
      return days >= 0 ? countdownLabel(this.trip.startDate) : 'Past';
    },

    /** The next few dated plans, so the tab opens on something useful. */
    upNext() {
      const today = new Date().toISOString().slice(0, 10);
      return this.itinerary
        .filter((item) => item.startAt && item.startAt.slice(0, 10) >= today)
        .slice(0, 5);
    },

    /**
     * TODO items with no plan against them yet. This is the tab's real job:
     * showing what still needs doing, with a shortcut straight into the Plan
     * form for each one.
     */
    needsPlanning() {
      const planned = new Set(
        this.itinerary.map((item) => item.checklistItemId).filter(Boolean));
      return this.checklist
        .filter((item) => item.status === 'TODO' && !planned.has(item.id))
        .slice(0, 8);
    },

    categoryOf: (value) => category(value),
  };
}
