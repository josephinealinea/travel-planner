import { api } from '../api.js';
import { queryParam } from '../chrome.js';
import { dateRange, CATEGORIES } from '../format.js';
import { countriesOfTrip } from '../location-picker.js';
import { toast } from '../toast.js';
import { currentUser } from '../session.js';

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

    // The member's own list from Account — see entryCurrencies.
    userCurrencies: ['EUR'],

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
    budget: { items: [], byCategory: {}, byCountry: [], exchangeRates: {}, total: 0,
             displayCurrency: 'EUR', totalsCurrency: 'EUR' },
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

      const user = await currentUser();
      if (user?.currencies?.length) this.userCurrencies = user.currencies;

      this.tab = this.tabFromHash();
      window.addEventListener('hashchange', () => this.showTab(this.tabFromHash()));

      // reload() warms the weather itself, so opening a trip is what triggers
      // the lookup — not switching to the Itinerary tab. See reload().
      await this.reload();
      this.loading = false;
    },

    tabFromHash() {
      const hash = location.hash.replace('#', '');
      return TABS.some((tab) => tab.id === hash) ? hash : 'overview';
    },

    /**
     * Switches tab, closing the checklist drawer on the way out.
     *
     * The drawer's markup sits outside the tab sections, so nothing hides it
     * when the tab changes — and its backdrop covers the whole viewport. Left
     * open, it silently swallows every click on the tab you just moved to
     * (the browser's back button reaches this through hashchange). Leaving a
     * tab closes its detail view.
     */
    showTab(id) {
      if (id !== this.tab) this.closeDrawer();
      this.tab = id;
      // Normally already warm from init(); this only does anything if that
      // first attempt failed, since loadWeather() is a no-op while the
      // destinations are unchanged.
      if (id === 'itinerary') this.loadWeather();
    },

    selectTab(id) {
      this.showTab(id);
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

        // Destinations may have just changed, which is the only thing that
        // moves the weather rows — adding one is exactly when a fresh lookup is
        // wanted. loadWeather compares a signature and does nothing when they
        // did not, so this is free after a checklist tick, and no longer
        // conditional on which tab is open: the point is to have the answer
        // before the Itinerary tab is asked for.
        this.loadWeather();
      } catch (error) {
        this.error = error.fullMessage;
      }
    },

    /**
     * What every "record a cost" select offers: the member's own list, plus
     * every code this trip already works in.
     *
     * The union is what stops a silent mismatch. These forms pre-fill from
     * trip data — the trip's display currency, an existing row's currency, a
     * plan template's suggestion — and none of that has to be a code the
     * member keeps in Account. A <select> whose model matches no option
     * silently falls back to showing the first one, so the form would read
     * "EUR" while still holding, and saving, GBP. The budget's own
     * "Show totals in" control already keeps its current value selectable for
     * exactly this reason.
     *
     * Deliberately derived only from loaded data, never from a form's own
     * model: the options have to already exist in the DOM by the time a form
     * sets its currency, or x-model writes a value the select cannot show.
     *
     * Equally deliberately, nothing else may widen it. A form that wants a
     * code the member does not keep has to fall back to one they do — see
     * openPlanForm. Injecting the suggestion instead put PEN in the list of a
     * member who works in EUR and USD, preselected it, and produced a cost in
     * a currency the trip had no rate for, which the budget then excluded from
     * its total.
     */
    get entryCurrencies() {
      const codes = [...this.userCurrencies];
      const add = (code) => { if (code && !codes.includes(code)) codes.push(code); };

      add(this.budget.displayCurrency);
      // Codes already stored on this trip stay selectable, so editing a row
      // someone entered in GBP does not silently rewrite it to EUR.
      (this.budget.items || []).forEach((item) => add(item.currency));
      (this.itinerary || []).forEach((item) => add(item.currency));
      return codes;
    },

    tripDates() {
      return dateRange(this.trip.startDate, this.trip.endDate);
    },

    /**
     * The countries this trip visits, for every "Use in" / "Location" picker
     * and every country filter. One getter on the page component so all four
     * tabs offer exactly the same list, derived from the destinations rather
     * than stored anywhere.
     */
    get tripCountries() {
      return countriesOfTrip(this.destinations);
    },

    /**
     * Checks one date field against the trip's own dates, both ends inclusive.
     * Returns '' when it is fine, and the message to show otherwise.
     *
     * Every date input in the four tabs also carries min/max, but that only
     * constrains the picker — a typed or pasted date sails past it. The API
     * rejects an out-of-range date either way (TripWindow); this is what puts
     * the answer beside the field instead of behind a round trip.
     */
    dateOutsideTrip(date, what) {
      if (!date || !this.trip.startDate || !this.trip.endDate) return '';
      if (date >= this.trip.startDate && date <= this.trip.endDate) return '';
      return `The ${what} must be within the trip, ${this.tripDates()}.`;
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

    /**
     * Focuses a field in a surface that has only just been shown.
     *
     * One $nextTick is not enough: x-show applies its display change on the
     * next frame, and focus() on a still-hidden element is silently ignored,
     * so the caret never lands and the form opens with nothing focused.
     * Waiting for the frame after the tick is what makes it stick.
     */
    focusWhenShown(ref) {
      this.$nextTick(() => requestAnimationFrame(() => this.$refs[ref]?.focus()));
    },

    /**
     * The Overview's way out of an empty trip: land on Destinations with the
     * add form already open. A destination is the first domino — it seeds the
     * checklist items that everything else hangs off — so the shortcut skips
     * the step where somebody has to work out where to start.
     */
    addFirstDestination() {
      // Opened in the same tick as the tab switch, not nested inside a
      // $nextTick: the modal lives outside the tab sections, so it does not
      // wait on the tab, and nesting only pushes its focus onto a frame where
      // the surface is still hidden.
      this.selectTab('destinations');
      this.openAddDestination();
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
