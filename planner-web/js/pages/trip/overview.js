import { countdownLabel, daysUntil, money, category } from '../../format.js';
import { t } from '../../i18n/index.js';

/**
 * The Overview tab. Everything here is derived from the already-loaded trip
 * bundle — no extra requests.
 */
export function overviewTab() {
  return {
    stats() {
      const done = this.scopedChecklist.filter((item) => item.status === 'COMPLETED').length;
      return [
        { label: t('overview.destinations'), value: this.scopedDestinations.length },
        { label: t('overview.checklistDone'), value: `${done} / ${this.scopedChecklist.length}` },
        { label: t('overview.itineraryEntries'), value: this.scopedItinerary.length },
        // The signed-in member's own charged total, matching what the Budget
        // tab opens on. It moved into `charged` when the rollup was split in
        // two, and reading the old flat `budget.total` here left this stat
        // silently blank — money() answers '' for undefined, so the fallback
        // below swallowed it rather than anything failing loudly.
        { label: t('overview.travelCost'),
          value: money(this.budget.charged?.total, this.budget.totalsCurrency) || '—' },
        { label: t('overview.departure'), value: this.departureLabel() },
      ];
    },

    departureLabel() {
      const days = daysUntil(this.trip.startDate);
      if (days == null) return '—';
      return days >= 0 ? countdownLabel(this.trip.startDate) : t('overview.past');
    },

    /** The next few dated plans, so the tab opens on something useful. */
    upNext() {
      const today = new Date().toISOString().slice(0, 10);
      return this.scopedItinerary
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
        this.scopedItinerary.map((item) => item.checklistItemId).filter(Boolean));
      return this.scopedChecklist
        .filter((item) => item.status === 'TODO' && !planned.has(item.id))
        .slice(0, 8);
    },

    categoryOf: (value) => category(value),
  };
}
