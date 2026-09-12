import { toast } from '../../toast.js';
import { category, timeRange, longDate, money, dateOf, timeOf } from '../../format.js';

/**
 * The Itinerary tab. Mostly a read view of what planning produced, grouped by
 * day, but entries can also be added here directly without going through a
 * checklist item.
 */
export function itineraryTab() {
  const blankEntry = () => ({
    id: null,
    category: 'OTHERS',
    description: '',
    startDate: '',
    startTime: '',
    endDate: '',
    endTime: '',
    cost: '',
    currency: '',
  });

  return {
    itinCategoryFilters: [],
    itinCostOnly: false,

    entryOpen: false,
    entryForm: blankEntry(),
    entryError: '',
    entryBusy: false,
    deletingEntry: null,

    get filteredItinerary() {
      return this.itinerary.filter((item) => {
        if (this.itinCategoryFilters.length
            && !this.itinCategoryFilters.includes(item.category)) return false;
        if (this.itinCostOnly && !item.cost) return false;
        return true;
      });
    },

    /** [{ date, label, entries }], with undated entries collected at the end. */
    get itineraryDays() {
      const byDay = new Map();
      const undated = [];

      this.filteredItinerary.forEach((item) => {
        if (!item.startAt) {
          undated.push(item);
          return;
        }
        const date = item.startAt.slice(0, 10);
        if (!byDay.has(date)) byDay.set(date, []);
        byDay.get(date).push(item);
      });

      const days = [...byDay.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([date, entries]) => ({ date, label: longDate(date), entries }));

      if (undated.length) days.push({ date: null, label: 'No date yet', entries: undated });
      return days;
    },

    toggleItinCategory(value) {
      const index = this.itinCategoryFilters.indexOf(value);
      if (index >= 0) this.itinCategoryFilters.splice(index, 1);
      else this.itinCategoryFilters.push(value);
    },

    openAddEntry() {
      this.entryForm = blankEntry();
      this.entryForm.currency = this.budget.displayCurrency || '';
      this.entryForm.startDate = this.trip.startDate || '';
      this.entryError = '';
      this.entryOpen = true;
      this.$nextTick(() => this.$refs.entryDescription?.focus());
    },

    openEditEntry(item) {
      this.entryForm = {
        id: item.id,
        category: item.category,
        description: item.description || '',
        startDate: dateOf(item.startAt),
        startTime: timeOf(item.startAt),
        endDate: dateOf(item.endAt),
        endTime: timeOf(item.endAt),
        cost: item.cost ?? '',
        currency: item.currency || this.budget.displayCurrency || '',
      };
      this.entryError = '';
      this.entryOpen = true;
    },

    async saveEntry() {
      const description = this.entryForm.description.trim();
      if (!description) {
        this.entryError = 'Describe the entry.';
        return;
      }

      const startAt = combine(this.entryForm.startDate, this.entryForm.startTime);
      const endAt = combine(this.entryForm.endDate, this.entryForm.endTime);
      if (startAt && endAt && endAt < startAt) {
        this.entryError = 'The end time cannot be before the start time.';
        return;
      }

      const cost = this.entryForm.cost === '' ? null : Number(this.entryForm.cost);
      if (cost != null && (!Number.isFinite(cost) || cost < 0)) {
        this.entryError = 'That cost does not look like a number.';
        return;
      }

      this.entryError = '';
      this.entryBusy = true;
      try {
        const payload = {
          category: this.entryForm.category,
          description,
          startAt,
          endAt,
          currency: cost == null ? null : (this.entryForm.currency || null),
        };
        if (this.entryForm.id) {
          await this.api.updatePlan(this.trip.id, this.entryForm.id,
            { ...payload, cost: cost == null ? 0 : cost });
          toast.success('Entry updated');
        } else {
          await this.api.addPlan(this.trip.id, { ...payload, cost });
          toast.success('Entry added');
        }
        this.entryOpen = false;
        await this.reload();
      } catch (error) {
        this.entryError = error.fullMessage;
      } finally {
        this.entryBusy = false;
      }
    },

    askDeleteEntry(item) {
      this.deletingEntry = item;
    },

    async confirmDeleteEntry() {
      try {
        await this.api.deletePlan(this.trip.id, this.deletingEntry.id);
        this.deletingEntry = null;
        toast.success('Entry removed');
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
        this.deletingEntry = null;
      }
    },

    // ── display helpers ─────────────────────────────
    entryTime: (item) => timeRange(item.startAt, item.endAt) || '—',
    entryCost: (item) => money(item.cost, item.currency),
    categoryOf: (value) => category(value),

    /** Shows which checklist item a plan came from. */
    sourceChecklist(item) {
      if (!item.checklistItemId) return null;
      return this.checklist.find((check) => check.id === item.checklistItemId)?.description || null;
    },
  };
}

function combine(date, time) {
  if (!date) return null;
  return `${date}T${time || '00:00'}:00`;
}
