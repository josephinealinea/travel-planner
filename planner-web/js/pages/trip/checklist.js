import { toast } from '../../toast.js';
import { category, timeRange, longDate, money, dateOf, timeOf } from '../../format.js';

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
  });

  return {
    // filters
    checkStatusFilter: 'ALL',
    checkCategoryFilters: [],
    checkGroupBy: 'destination',

    // add form
    addCheckOpen: false,
    newCheck: { category: 'OTHERS', description: '', note: '', destinationId: '' },
    addCheckError: '',
    addCheckBusy: false,

    // drawer
    openItem: null,
    drawerForm: { description: '', note: '', category: 'OTHERS', destinationId: '' },
    drawerError: '',
    drawerBusy: false,
    deletingCheck: false,

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

    /** Returns [{ title, items }] so the template stays simple. */
    get checklistGroups() {
      const items = this.filteredChecklist;
      if (this.checkGroupBy === 'none') return [{ title: null, items }];

      const groups = new Map();
      const keyFor = (item) => (this.checkGroupBy === 'category'
        ? category(item.category).label
        : this.destinationName(item.destinationId) || 'No destination');

      items.forEach((item) => {
        const key = keyFor(item);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(item);
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
      this.newCheck = { category: 'OTHERS', description: '', note: '', destinationId: '' };
      this.addCheckError = '';
      this.addCheckOpen = true;
      this.$nextTick(() => this.$refs.newCheckDescription?.focus());
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
          destinationId: this.newCheck.destinationId || null,
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
        destinationId: item.destinationId || '',
      };
      this.drawerError = '';
      this.planOpen = false;
      this.deletingCheck = false;
    },

    closeDrawer() {
      this.openItem = null;
      this.planOpen = false;
    },

    /** Plans recorded against the open item. */
    get openItemPlans() {
      if (!this.openItem) return [];
      return this.itinerary.filter((plan) => plan.checklistItemId === this.openItem.id);
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
          // An empty string clears the link server-side.
          destinationId: this.drawerForm.destinationId,
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

    async setCheckStatus(status) {
      try {
        await this.api.setCheckStatus(this.trip.id, this.openItem.id, status);
        toast.success(status === 'COMPLETED' ? 'Marked complete' : 'Reopened');
        await this.reload();
        this.refreshOpenItem();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    /** Inline tick from the list, without opening the drawer. */
    async quickToggle(item, event) {
      event.stopPropagation();
      try {
        await this.api.setCheckStatus(this.trip.id, item.id,
          item.status === 'COMPLETED' ? 'TODO' : 'COMPLETED');
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
      }
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
      this.planOpen = true;

      // Pre-fill from the server's suggestion; every field stays editable.
      try {
        const template = await this.api.planTemplate(this.trip.id, this.openItem.id);
        this.planForm.description = template.description || '';
        this.planForm.startDate = dateOf(template.startAt);
        this.planForm.startTime = timeOf(template.startAt);
        this.planForm.endDate = dateOf(template.endAt);
        this.planForm.endTime = timeOf(template.endAt);
        this.planForm.currency = template.currency || this.budget.displayCurrency || '';
      } catch {
        this.planForm.description = this.openItem.description || '';
        this.planForm.currency = this.budget.displayCurrency || '';
      }
      this.$nextTick(() => this.$refs.planDescription?.focus());
    },

    editPlan(plan) {
      this.planError = '';
      this.planForm = {
        id: plan.id,
        description: plan.description || '',
        startDate: dateOf(plan.startAt),
        startTime: timeOf(plan.startAt),
        endDate: dateOf(plan.endAt),
        endTime: timeOf(plan.endAt),
        cost: plan.cost ?? '',
        currency: plan.currency || this.budget.displayCurrency || '',
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
            currency: cost == null ? null : (this.planForm.currency || null),
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

    async deletePlan(plan) {
      try {
        await this.api.deletePlan(this.trip.id, plan.id);
        toast.success('Plan removed');
        await this.reload();
        this.refreshOpenItem();
      } catch (error) {
        toast.error(error.fullMessage);
      }
    },

    // ── display helpers ─────────────────────────────
    categoryOf: (value) => category(value),

    planWhen(plan) {
      if (!plan.startAt) return 'No date set';
      const time = timeRange(plan.startAt, plan.endAt);
      const day = longDate(plan.startAt.slice(0, 10));
      return time ? `${day} · ${time}` : day;
    },

    planCost: (plan) => money(plan.cost, plan.currency),

    destinationName(destinationId) {
      if (!destinationId) return null;
      return this.destinations.find((d) => d.id === destinationId)?.name || null;
    },

    planCountFor(item) {
      return this.itinerary.filter((plan) => plan.checklistItemId === item.id).length;
    },
  };
}

/** "2026-10-25" + "15:00" -> "2026-10-25T15:00:00", or null if there is no date. */
function combine(date, time) {
  if (!date) return null;
  return `${date}T${time || '00:00'}:00`;
}
