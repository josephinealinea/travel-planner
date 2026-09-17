import { toast } from '../../toast.js';
import { category, timeRange, longDate, money, dateOf, timeOf } from '../../format.js';
import { toggleId, selectedPresent, runBulkDelete } from '../../selection.js';
import { toggleLocation, locationNames, countriesOfTrip } from '../../location-picker.js';

/**
 * The Checklist tab and its detail drawer — the centre of the app.
 *
 * The drawer is where a checklist item is edited, planned and completed. Two
 * behaviours are deliberate:
 *
 *  - saving a plan never completes the item. "Plan another" exists because a
 *    job can take several bookings, and only the member knows when it is done;
 *  - "Set this Checklist to Complete" always works, plans or not, so nothing
 *    can get stuck.
 */
export function checklistTab() {
  const blankPlan = () => ({
    id: null,
    description: '',
    startDate: '',
    startTime: '',
    endDate: '',
    endTime: '',
    cost: '',
    currency: '',
    // Unticked for a new plan, the reverse of the budget's own form: a plan is
    // something you intend to do, and its cost is usually money still to
    // leave. See BudgetSync on the API.
    costCharged: false,
    // Nobody named means the whole trip — see TripMembers on the API.
    sharedByUserIds: [],
    countryCodes: [],
  });

  return {
    // filters
    checkStatusFilter: 'ALL',
    checkCategoryFilters: [],
    checkGroupBy: 'country',

    /** The Group by pills, in order — mirrors budgetShowOptions on the Budget tab. */
    checkGroupByOptions: [
      { value: 'country',  label: 'Country' },
      { value: 'category', label: 'Category' },
    ],

    // bulk selection
    checkSelectedIds: [],
    checkBulkOpen: false,
    checkBulkBusy: false,

    // add form
    addCheckOpen: false,
    newCheck: { category: 'OTHERS', description: '', note: '', countryCodes: [] },
    addCheckError: '',
    addCheckBusy: false,

    // drawer
    openItem: null,
    drawerForm: { description: '', note: '', category: 'OTHERS', countryCodes: [] },
    drawerError: '',
    drawerBusy: false,
    deletingCheck: false,
    // Raised when the drawer is asked to close with unsaved edits in it.
    drawerDiscardAsk: false,

    // The item a completion confirmation is pending for. Holds the item rather
    // than a flag because the confirmation is also raised from the list, where
    // there is no open drawer to read it from.
    completingItem: null,

    // plan form inside the drawer
    planOpen: false,
    planForm: blankPlan(),
    planError: '',
    planBusy: false,

    // ── filtering and grouping ──────────────────────
    get filteredChecklist() {
      return this.checklist.filter((item) => {
        if (this.checkStatusFilter === 'TODO' && item.status !== 'TODO') return false;
        if (this.checkStatusFilter === 'COMPLETED' && item.status !== 'COMPLETED') return false;
        if (this.checkCategoryFilters.length
            && !this.checkCategoryFilters.includes(item.category)) return false;
        return true;
      });
    },

    /**
     * Returns [{ title, items }] so the template stays simple.
     *
     * Grouping by destination is a fan-out, not a partition: an item linked to
     * several destinations appears once under each of them. "No destination"
     * is reserved for items linked to none.
     */
    get checklistGroups() {
      const items = this.filteredChecklist;

      const groups = new Map();
      const push = (key, item) => {
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(item);
      };

      items.forEach((item) => {
        if (this.checkGroupBy === 'category') {
          push(category(item.category).label, item);
          return;
        }
        const names = this.countryLabels(item.countryCodes);
        if (!names.length) push('No country', item);
        else names.forEach((name) => push(name, item));
      });

      // "No destination" reads better last.
      return [...groups.entries()]
        .sort(([a], [b]) => (a === 'No destination' ? 1 : b === 'No destination' ? -1 : 0))
        .map(([title, groupItems]) => ({ title, items: groupItems }));
    },

    toggleCategoryFilter(value) {
      const index = this.checkCategoryFilters.indexOf(value);
      if (index >= 0) this.checkCategoryFilters.splice(index, 1);
      else this.checkCategoryFilters.push(value);
    },

    checkProgress() {
      const done = this.checklist.filter((item) => item.status === 'COMPLETED').length;
      return { done, total: this.checklist.length };
    },

    // ── add ─────────────────────────────────────────
    openAddCheck() {
      this.newCheck = { category: 'OTHERS', description: '', note: '', countryCodes: [] };
      this.addCheckError = '';
      this.addCheckOpen = true;
      this.focusWhenShown('newCheckDescription');
    },

    async addCheck() {
      const description = this.newCheck.description.trim();
      if (!description) {
        this.addCheckError = 'Describe what needs doing.';
        return;
      }
      this.addCheckError = '';
      this.addCheckBusy = true;
      try {
        await this.api.addCheck(this.trip.id, {
          category: this.newCheck.category,
          description,
          note: this.newCheck.note.trim() || null,
          countryCodes: this.newCheck.countryCodes,
        });
        this.addCheckOpen = false;
        toast.success('Checklist item added');
        await this.reload();
      } catch (error) {
        this.addCheckError = error.fullMessage;
      } finally {
        this.addCheckBusy = false;
      }
    },

    // ── drawer ──────────────────────────────────────
    openChecklistItem(item) {
      this.openItem = item;
      this.drawerForm = {
        description: item.description || '',
        note: item.note || '',
        category: item.category,
        countryCodes: [...(item.countryCodes || [])],
      };
      this.drawerError = '';
      this.planOpen = false;
      this.deletingCheck = false;
      this.drawerDiscardAsk = false;
      this.completingItem = null;
    },

    /**
     * The unconditional close, for paths that have already saved, deleted or
     * navigated. Escape, the backdrop and ✕ go through requestCloseDrawer().
     */
    closeDrawer() {
      this.openItem = null;
      this.planOpen = false;
      this.drawerDiscardAsk = false;
      this.completingItem = null;
    },

    /**
     * True when closing now would lose something typed: a Details field that
     * no longer matches the stored item, or a Plan form left open. Compared
     * trimmed, because saveDrawer() trims on the way out — otherwise a saved
     * note with a trailing space would read as unsaved forever.
     */
    get drawerDirty() {
      const item = this.openItem;
      if (!item) return false;
      const form = this.drawerForm;
      const sameCountries = [...form.countryCodes].sort().join()
                         === [...(item.countryCodes || [])].sort().join();
      return this.planOpen
          || form.description.trim() !== (item.description || '')
          || form.note.trim() !== (item.note || '')
          || form.category !== item.category
          || !sameCountries;
    },

    /**
     * Escape, the backdrop and ✕ come through here. They used to discard a
     * half-written note without a word; now the first attempt asks, and a
     * second one (Escape again, or Discard) means it.
     */
    requestCloseDrawer() {
      // Escape meant for the completion confirm on top must not reach here.
      if (this.completingItem) return;
      if (this.drawerDirty && !this.drawerDiscardAsk) {
        this.drawerDiscardAsk = true;
        // The prompt sits at the top of the drawer, usually scrolled out of
        // view by the time someone presses Escape in the Note field. Focusing
        // its safe button scrolls it into view and answers "keep" on Enter.
        this.focusWhenShown('drawerKeepEditing');
        return;
      }
      this.closeDrawer();
    },

    /**
     * Plans recorded against the open item — one entry per plan, not per day.
     *
     * A plan that covers several days is several itinerary records, all but
     * the first carrying planId. Filtering to the rows that own their plan is
     * what makes a four-night booking read as one plan here while still being
     * four deletable rows on the Itinerary tab.
     */
    get openItemPlans() {
      if (!this.openItem) return [];
      return this.plansFor(this.openItem);
    },

    async saveDrawer() {
      const description = this.drawerForm.description.trim();
      if (!description) {
        this.drawerError = 'A checklist item needs a description.';
        return;
      }
      this.drawerError = '';
      this.drawerBusy = true;
      try {
        await this.api.updateCheck(this.trip.id, this.openItem.id, {
          description,
          note: this.drawerForm.note.trim(),
          category: this.drawerForm.category,
          // An empty array clears every link server-side.
          countryCodes: this.drawerForm.countryCodes,
        });
        toast.success('Saved');
        await this.reload();
        this.refreshOpenItem();
      } catch (error) {
        this.drawerError = error.fullMessage;
      } finally {
        this.drawerBusy = false;
      }
    },

    /**
     * Raises the completion confirmation, from either the list's tick or the
     * drawer's button. Completing is the deliberate "this job is finished"
     * call — it takes the Plan button away with it — so both routes ask, and
     * both ask the same way.
     */
    askComplete(item, event) {
      event?.stopPropagation();
      this.completingItem = item;
    },

    async confirmSetComplete() {
      const item = this.completingItem;
      this.completingItem = null;
      if (item) await this.applyStatus(item, 'COMPLETED');
    },

    /** Plans recorded against whichever item the confirmation is asking about. */
    get completingItemPlans() {
      if (!this.completingItem) return [];
      return this.plansFor(this.completingItem);
    },

    async setCheckStatus(status) {
      await this.applyStatus(this.openItem, status);
    },

    /** The one path that writes a status, whichever surface asked for it. */
    async applyStatus(item, status) {
      try {
        await this.api.setCheckStatus(this.trip.id, item.id, status);
        toast.success(status === 'COMPLETED' ? 'Marked complete' : 'Reopened');
        await this.reload();

        // Completing is the "this job is finished" call — it takes the Plan
        // button away with it — so the item's own panel has nothing left to
        // say and closes. Reopening deliberately leaves it open: that is the
        // start of more work on the item, not the end of it.
        if (status === 'COMPLETED' && this.openItem?.id === item.id) this.closeDrawer();
        else this.refreshOpenItem();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    /**
     * Inline tick from the list, without opening the drawer. Ticking asks for
     * confirmation; un-ticking does not — undoing a completion needs no
     * ceremony, and refusing to ask twice keeps the quick path quick.
     * The tick is a <button> beside the row, so it is reachable by Tab and
     * announced with its pressed state.
     */
    async quickToggle(item, event) {
      if (item.status !== 'COMPLETED') {
        this.askComplete(item, event);
        return;
      }
      event.stopPropagation();
      await this.applyStatus(item, 'TODO');
    },

    async confirmDeleteCheck() {
      try {
        await this.api.deleteCheck(this.trip.id, this.openItem.id);
        toast.success('Checklist item deleted');
        this.closeDrawer();
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    /** Re-points the drawer at the reloaded copy of the same item. */
    refreshOpenItem() {
      if (!this.openItem) return;
      const found = this.checklist.find((item) => item.id === this.openItem.id);
      this.openItem = found || null;
    },

    // ── plan form ───────────────────────────────────
    async openPlanForm() {
      this.planError = '';
      this.planForm = blankPlan();
      // Defaults to the checklist item's own locations — "Plan activities in
      // Paris" obviously happens in Paris — but stays fully editable.
      this.planForm.countryCodes = [...(this.openItem.countryCodes || [])];
      this.planOpen = true;

      // Pre-fill from the server's suggestion; every field stays editable.
      try {
        const template = await this.api.planTemplate(this.trip.id, this.openItem.id);
        this.planForm.description = template.description || '';
        this.planForm.startDate = dateOf(template.startAt);
        this.planForm.startTime = timeOf(template.startAt);
        this.planForm.endDate = dateOf(template.endAt);
        this.planForm.endTime = timeOf(template.endAt);

        // The template suggests the destination country's own currency, which
        // is only useful if the member actually works in it. Offering PEN to
        // someone who keeps EUR and USD adds a code to their list and, with
        // no rate for it on the trip, a cost the budget cannot total. So the
        // suggestion is taken only when it is already selectable.
        this.planForm.currency = this.entryCurrencies.includes(template.currency)
          ? template.currency
          : (this.budget.displayCurrency || '');
      } catch {
        this.planForm.description = this.openItem.description || '';
        this.planForm.currency = this.budget.displayCurrency || '';
      }
      this.focusWhenShown('planDescription');
    },

    editPlan(plan) {
      this.planError = '';
      this.planForm = {
        id: plan.id,
        description: plan.description || '',
        startDate: dateOf(plan.startAt),
        // All-day midnight is a placeholder, not a time — see openEditEntry.
        startTime: plan.allDay ? '' : timeOf(plan.startAt),
        endDate: dateOf(plan.endAt),
        endTime: plan.allDay ? '' : timeOf(plan.endAt),
        cost: plan.cost ?? '',
        currency: plan.currency || this.budget.displayCurrency || '',
        costCharged: this.chargedOfPlan(plan),
        sharedByUserIds: this.sharersOfPlan(plan),
        countryCodes: [...(plan.countryCodes || [])],
      };
      this.planOpen = true;
    },

    async savePlan() {
      const description = this.planForm.description.trim();
      if (!description) {
        this.planError = 'Describe the plan.';
        return;
      }

      const startAt = combine(this.planForm.startDate, this.planForm.startTime);
      const endAt = combine(this.planForm.endDate, this.planForm.endTime);
      if (startAt && endAt && endAt < startAt) {
        this.planError = 'The end time cannot be before the start time.';
        return;
      }
      const outsideTrip = this.dateOutsideTrip(this.planForm.startDate, 'start date')
                       || this.dateOutsideTrip(this.planForm.endDate, 'end date');
      if (outsideTrip) {
        this.planError = outsideTrip;
        return;
      }

      const cost = this.planForm.cost === '' ? null : Number(this.planForm.cost);
      if (cost != null && (!Number.isFinite(cost) || cost < 0)) {
        this.planError = 'That cost does not look like a number.';
        return;
      }

      this.planError = '';
      this.planBusy = true;
      try {
        if (this.planForm.id) {
          await this.api.updatePlan(this.trip.id, this.planForm.id, {
            description,
            startAt,
            endAt,
            // Zero is how the API is told to clear a cost and drop its budget row.
            cost: cost == null ? 0 : cost,
            currency: this.planForm.currency || null,
            costCharged: this.planForm.costCharged,
            costSharedByUserIds: this.planForm.sharedByUserIds,
            // An empty array clears every link server-side.
            countryCodes: this.planForm.countryCodes,
          });
          toast.success('Plan updated');
        } else {
          await this.api.addPlan(this.trip.id, {
            checklistItemId: this.openItem.id,
            category: this.openItem.category,
            description,
            startAt,
            endAt,
            cost,
            // A date with no time belongs to a day, not an hour — the API
          // records it the same way a stay's middle nights are recorded, and
          // the row shows "—" instead of a misleading 00:00.
          allDay: !this.planForm.startTime,
          currency: cost == null ? null : (this.planForm.currency || null),
            costCharged: this.planForm.costCharged,
            costSharedByUserIds: this.planForm.sharedByUserIds,
            countryCodes: this.planForm.countryCodes,
          });
          toast.success(cost == null
            ? 'Plan added'
            : `Plan added — a ${money(cost, this.planForm.currency)} budget entry was created`);
        }
        this.planOpen = false;
        await this.reload();
        this.refreshOpenItem();
      } catch (error) {
        this.planError = error.fullMessage;
      } finally {
        this.planBusy = false;
      }
    },

    /**
     * Removes the plan and every day it covers — from here a plan is the
     * booking. Removing a single night is the Itinerary tab's job.
     */
    async deletePlan(plan) {
      const days = this.daysOfPlan(plan).length;
      try {
        await this.api.deletePlan(this.trip.id, plan.id);
        toast.success(days > 1 ? `Plan removed — ${days} itinerary items` : 'Plan removed');
        await this.reload();
        this.refreshOpenItem();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    // ── display helpers ─────────────────────────────
    // ── bulk selection ──────────────────────────────
    toggleCheckSelected(id) {
      toggleId(this.checkSelectedIds, id);
    },

    isCheckSelected(id) {
      return this.checkSelectedIds.includes(id);
    },

    /**
     * Selected items that are actually on screen — everything else derives
     * from this, so "Delete selected" can never remove a row a filter is
     * hiding. Ids survive the filter, so widening it brings them back.
     */
    get checkSelected() {
      return selectedPresent(this.checkSelectedIds, this.filteredChecklist);
    },

    /** How many of the selected items carry plans, for the confirm wording. */
    get checkSelectedPlanCount() {
      return this.checkSelected.reduce((sum, item) => sum + this.planCountFor(item), 0);
    },

    async confirmBulkDeleteChecks() {
      const doomed = this.checkSelected;
      if (!doomed.length) return;

      this.checkBulkBusy = true;
      try {
        const { deleted, failed } = await runBulkDelete(
          doomed, (id) => this.api.deleteCheck(this.trip.id, id));

        this.checkSelectedIds = [];
        this.checkBulkOpen = false;

        if (failed) toast.error(`Deleted ${deleted} — ${failed} could not be removed`);
        else toast.success(`Deleted ${deleted} checklist item${deleted === 1 ? '' : 's'}`);

        await this.reload();
      } finally {
        this.checkBulkBusy = false;
      }
    },

    categoryOf: (value) => category(value),

    planWhen(plan) {
      if (!plan.startAt) return 'No date set';
      const time = timeRange(plan.startAt, plan.endAt);
      const day = longDate(plan.startAt.slice(0, 10));
      return time ? `${day} · ${time}` : day;
    },

    planCost: (plan) => money(plan.cost, plan.currency),

    // ── location picker (shared with the itinerary and expense forms) ──
    toggleLocation,

    countryLabels(countryCodes) {
      return locationNames(this.destinations, countryCodes);
    },

    /** Comma-joined, for chip/label text; empty when nothing is linked. */
    countryLabel(countryCodes) {
      return this.countryLabels(countryCodes).join(', ');
    },

    /** The plans linked to an item: the rows that are plans, not their days. */
    plansFor(item) {
      return this.itinerary.filter(
        (plan) => plan.checklistItemId === item.id && !plan.planId);
    },

    planCountFor(item) {
      return this.plansFor(item).length;
    },

    /** Every row belonging to one plan, the plan's own row included. */
    daysOfPlan(plan) {
      return this.itinerary.filter((row) => row.id === plan.id || row.planId === plan.id);
    },

    /**
     * "4N" for a plan that spans, nothing for one that does not. Counted from
     * the rows that exist right now, so deleting a night makes this read 3N
     * without anything having to be rewritten.
     */
    planNights(plan) {
      const nights = this.daysOfPlan(plan).length - 1;
      return nights > 0 ? `${nights}N` : '';
    },
  };
}

/** "2026-10-25" + "15:00" -> "2026-10-25T15:00:00", or null if there is no date. */
function combine(date, time) {
  if (!date) return null;
  return `${date}T${time || '00:00'}:00`;
}
