import { countdownLabel, daysUntil, money, category } from '../../format.js';
import { t } from '../../i18n/index.js';
import { setAmountsMasked } from '../../format.js';
import { saveAmountsMasked } from '../../amount-mask.js';

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
          value: money(this.budget.charged?.total, this.budget.totalsCurrency) || '—',
          maskToggle: true },
        { label: t('overview.departure'), value: this.departureLabel() },
      ];
    },

    /**
     * Hides or shows every amount. money() reads one module flag, but Alpine
     * only re-runs an expression when reactive data it read changes, so the
     * bundle is re-read afterwards: reload() hands every row a new object and
     * every amount on every tab redraws.
     */
    async toggleAmounts() {
      this.amountsMasked = !this.amountsMasked;
      setAmountsMasked(this.amountsMasked);
      saveAmountsMasked(this.amountsMasked);
      await this.reload();
    },

    departureLabel() {
      const days = daysUntil(this.trip.startDate);
      if (days == null) return '—';
      return days >= 0 ? countdownLabel(this.trip.startDate) : t('overview.past');
    },

    /**
     * The next dated plans, so the tab opens on something useful. Ten rather
     * than five: Needs planning and Needs review stack in the other column, so
     * this one has the room to run down beside them.
     */
    upNext() {
      const today = new Date().toISOString().slice(0, 10);
      return this.scopedItinerary
        .filter((item) => item.startAt && item.startAt.slice(0, 10) >= today)
        .slice(0, 10);
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

    /**
     * Itinerary entries still waiting for approval (status PENDING), soonest
     * first and undated last — the other half of what the Overview exists to
     * surface. Nothing here is stored: it is read off the entries as they are.
     */
    needsReview() {
      // A stay spread over several nights is one plan and several rows; only
      // the plan's own row (the one with no planId) is listed, so a four-night
      // booking is one thing to review rather than five.
      return this.scopedItinerary
        .filter((item) => item.status === 'PENDING' && !item.planId)
        .sort((a, b) => (a.startAt || '9999').localeCompare(b.startAt || '9999'))
        .slice(0, 8);
    },

    /** When an entry is, with "4N" after it for a plan that spans nights. */
    reviewWhen(item) {
      const nights = this.planNights(item);
      return nights ? `${this.planWhen(item)} · ${nights}` : this.planWhen(item);
    },

    categoryOf: (value) => category(value),
  };
}
