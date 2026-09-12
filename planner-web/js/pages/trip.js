import { api } from '../api.js';
import { queryParam } from '../chrome.js';
import { dateRange, CATEGORIES, CURRENCIES } from '../format.js';
import { toast } from '../toast.js';

import { overviewTab } from './trip/overview.js';
import { membersTab } from './trip/members.js';
import { destinationsTab } from './trip/destinations.js';
import { checklistTab } from './trip/checklist.js';
import { itineraryTab } from './trip/itinerary.js';
import { budgetTab } from './trip/budget.js';
import { publishTab } from './trip/publish.js';

const TABS = [
  { id: 'overview',     label: 'Overview' },
  { id: 'members',      label: 'Members' },
  { id: 'destinations', label: 'Destinations' },
  { id: 'checklist',    label: 'Checklist' },
  { id: 'itinerary',    label: 'Itinerary' },
  { id: 'budget',       label: 'Budget' },
  { id: 'publish',      label: 'Publish' },
];

/**
 * The trip workspace: one page, one bundle request, tabs routed through the
 * URL hash so a tab can be linked to and survives a reload.
 *
 * Each tab contributes its own state and methods from js/pages/trip/, all
 * spread into this single Alpine component so they share the loaded bundle and
 * one reload() rather than each fetching separately.
 */
export function tripPage() {
  const base = {
    api,
    tabs: TABS,
    categories: CATEGORIES,
    currencies: CURRENCIES,

    tripId: queryParam('id'),
    tab: 'overview',
    loading: true,
    error: '',

    // Filled by reload()
    trip: {},
    members: [],
    destinations: [],
    checklist: [],
    itinerary: [],
    budget: { items: [], byCategory: {}, exchangeRates: {}, total: 0, displayCurrency: 'EUR' },
    publish: { status: 'DRAFT', requests: [] },
    currentUserId: null,
    isOwner: false,

    // Trip-level editing
    editOpen: false,
    editForm: { title: '', startDate: '', endDate: '', displayCurrency: '' },
    editError: '',
    editBusy: false,
    deletingTrip: false,
    deleteConfirm: '',

    async init() {
      if (!this.tripId) {
        this.error = 'No trip was specified.';
        this.loading = false;
        return;
      }

      this.tab = this.tabFromHash();
      window.addEventListener('hashchange', () => { this.tab = this.tabFromHash(); });

      await this.reload();
      this.loading = false;
    },

    tabFromHash() {
      const hash = location.hash.replace('#', '');
      return TABS.some((tab) => tab.id === hash) ? hash : 'overview';
    },

    selectTab(id) {
      this.tab = id;
      // replaceState rather than assigning location.hash, so switching tabs
      // does not fill the back button with history entries.
      history.replaceState(null, '', `${location.pathname}${location.search}#${id}`);
    },

    /** Re-reads the whole bundle. Every mutation ends by calling this. */
    async reload() {
      try {
        const detail = await api.trip(this.tripId);
        this.trip = detail;
        this.members = detail.members || [];
        this.destinations = detail.destinations || [];
        this.checklist = detail.checklist || [];
        this.itinerary = detail.itinerary || [];
        this.budget = detail.budget || this.budget;
        this.publish = detail.publish || this.publish;
        this.currentUserId = detail.currentUserId;
        this.isOwner = detail.isOwner;
        this.error = '';
      } catch (error) {
        this.error = error.fullMessage;
      }
    },

    tripDates() {
      return dateRange(this.trip.startDate, this.trip.endDate);
    },

    ownerName() {
      return this.members.find((member) => member.role === 'OWNER')?.displayName || '';
    },

    countFor(tabId) {
      switch (tabId) {
        case 'members': return this.members.length;
        case 'destinations': return this.destinations.length;
        case 'checklist': return this.checklist.length;
        case 'itinerary': return this.itinerary.length;
        case 'budget': return this.budget.items?.length || 0;
        case 'publish': return this.pendingRequests.length || 0;
        default: return 0;
      }
    },

    // ── trip details ────────────────────────────────
    openEdit() {
      this.editForm = {
        title: this.trip.title || '',
        startDate: this.trip.startDate || '',
        endDate: this.trip.endDate || '',
        displayCurrency: this.budget.displayCurrency || 'EUR',
      };
      this.editError = '';
      this.editOpen = true;
    },

    async saveEdit() {
      const title = this.editForm.title.trim();
      if (!title) {
        this.editError = 'Give the trip a title.';
        return;
      }
      if (this.editForm.endDate < this.editForm.startDate) {
        this.editError = 'The end date cannot be before the start date.';
        return;
      }

      this.editError = '';
      this.editBusy = true;
      try {
        await api.updateTrip(this.tripId, {
          title,
          startDate: this.editForm.startDate,
          endDate: this.editForm.endDate,
          displayCurrency: this.editForm.displayCurrency,
        });
        this.editOpen = false;
        toast.success('Trip updated');
        await this.reload();
      } catch (error) {
        this.editError = error.fullMessage;
      } finally {
        this.editBusy = false;
      }
    },

    async confirmDeleteTrip() {
      try {
        await api.deleteTrip(this.tripId);
        location.href = 'trips.html';
      } catch (error) {
        toast.error(error.fullMessage);
        this.deletingTrip = false;
      }
    },

    /** Jumps from the Overview shortcut straight into a checklist item's drawer. */
    planFromOverview(item) {
      this.selectTab('checklist');
      this.openChecklistItem(item);
      this.$nextTick(() => this.openPlanForm());
    },
  };

  return mergeTabs(base,
    overviewTab(),
    membersTab(),
    destinationsTab(),
    checklistTab(),
    itineraryTab(),
    budgetTab(),
    publishTab());
}

/**
 * Copies each tab's properties onto the component by descriptor rather than by
 * spreading them.
 *
 * Object spread *reads* every own property, which invokes a getter and stores
 * its result — so a computed property like filteredChecklist would be evaluated
 * once, before any trip data existed, and then never recompute. Copying the
 * descriptors keeps getters as getters, which is what Alpine's reactivity needs.
 */
function mergeTabs(target, ...tabs) {
  for (const tab of tabs) {
    Object.defineProperties(target, Object.getOwnPropertyDescriptors(tab));
  }
  return target;
}
