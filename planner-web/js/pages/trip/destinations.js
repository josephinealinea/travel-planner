import { toast } from '../../toast.js';
import { dateRange, daysBetween } from '../../format.js';
import { toggleId, selectedPresent, runBulkDelete } from '../../selection.js';

const LOOKUP_DEBOUNCE_MS = 250;
const COUNTRY_MATCH_LIMIT = 8;

/**
 * The Destinations tab, including the countries.dev name lookup.
 *
 * The lookup is a convenience, never a gate: free text always saves, and the
 * latitude and longitude stay editable whether or not a suggestion was picked.
 * Some real destinations have no gazetteer entry under the name travellers use
 * for them, so typing the coordinates by hand is a supported path.
 */
export function destinationsTab() {
  const blankForm = () => ({
    id: null,
    name: '',
    countryCode: '',
    countryName: '',
    latitude: '',
    longitude: '',
    geonameId: null,
    timezone: '',
    startDate: '',
    endDate: '',
    notes: '',
    // Ticked, nothing is seeded for this destination. Stored on the record
    // rather than acted on once, because the accommodation item is also seeded
    // on a later date edit — see DestinationService.
    suppressChecklist: false,
  });

  return {
    destForm: blankForm(),
    destFormOpen: false,
    destError: '',
    destBusy: false,

    // bulk selection — like the checklist, itinerary and budget tabs,
    // removing is a select-then-delete job rather than a button on every row
    destSelectedIds: [],
    destBulkOpen: false,
    destBulkBusy: false,

    suggestions: [],
    lookupBusy: false,
    lookupRan: false,
    lookupTimer: null,
    // Guards against a slow lookup reopening a list that was dismissed.
    lookupToken: 0,

    // Every country, for the picker. Loaded once, lazily — the destination
    // form is the only thing that needs it.
    countryOptions: [],

    // What is typed in the country box, kept apart from destForm.countryCode
    // so half-typed text is never mistaken for a chosen country.
    countryQuery: '',
    countryListOpen: false,

    /**
     * Loads the country list if it is not already here.
     *
     * Started when a form opens and deliberately not awaited: it is a 250-item
     * fetch and the modal should not wait behind it. What makes that safe is
     * countryChoices, which keeps the destination's own code selectable while
     * the list is still in flight — without it the <select> would match no
     * option and silently fall back to whichever country sorts first.
     */
    async loadCountries() {
      if (this.countryOptions.length) return;
      try {
        this.countryOptions = await this.api.countries();
      } catch {
        // Leaves the picker empty; countryFor keeps the saved code selectable
        // so nothing is lost, and the code stays visible in its own field.
        this.countryOptions = [];
      }
    },

    /**
     * The countries matching what has been typed, or the first few when the
     * box is empty so the list is never blank on focus.
     *
     * Matches on a leading substring of the name first — typing "per" should
     * put Peru at the top, not bury it under countries that merely contain
     * "per" somewhere — and the code is matched too, so "PE" works.
     */
    get countryMatches() {
      const query = this.countryQuery.trim().toLowerCase();
      if (!query) return this.countryOptions.slice(0, COUNTRY_MATCH_LIMIT);

      const starts = [];
      const contains = [];
      for (const option of this.countryOptions) {
        const name = option.name.toLowerCase();
        if (name.startsWith(query) || option.code.toLowerCase() === query) starts.push(option);
        else if (name.includes(query)) contains.push(option);
      }
      return [...starts, ...contains].slice(0, COUNTRY_MATCH_LIMIT);
    },

    onCountryInput() {
      this.loadCountries();
      this.countryListOpen = true;
    },

    /** Choosing is the only way a country gets set; the code comes with it. */
    pickCountry(option) {
      this.destForm.countryCode = option.code;
      this.destForm.countryName = option.name;
      this.countryQuery = option.name;
      this.countryListOpen = false;
    },

    /**
     * Resolves whatever was typed when the box is left.
     *
     * The field is a text box so it can be typed into, but only a real country
     * may come out of it. So on the way out: an exact name or code is accepted
     * as if it had been picked, an empty box clears the country, and anything
     * else snaps back to whatever was chosen before. Half-typed text is never
     * left sitting in the field looking like an answer.
     */
    resolveCountry() {
      this.countryListOpen = false;
      const query = this.countryQuery.trim();

      if (!query) {
        this.destForm.countryCode = '';
        this.destForm.countryName = '';
        return;
      }

      const exact = this.countryOptions.find(
        (option) => option.name.toLowerCase() === query.toLowerCase()
          || option.code.toLowerCase() === query.toLowerCase());
      if (exact) {
        this.pickCountry(exact);
        return;
      }

      // Narrowed to one country counts as having chosen it. The official
      // names are often far longer than what anyone types — "Bolivia" against
      // "Bolivia (Plurinational State of)" — so requiring an exact match here
      // would reject the obvious thing to type and snap the field back.
      const matches = this.countryMatches;
      if (matches.length === 1) {
        this.pickCountry(matches[0]);
        return;
      }

      // Still ambiguous or unrecognised: keep the country that was already
      // chosen, and show its name rather than text that matched nothing.
      this.countryQuery = this.destForm.countryName || '';
    },

    openAddDestination() {
      this.loadCountries();
      this.destForm = blankForm();
      this.countryQuery = '';
      this.countryListOpen = false;
      this.destError = '';
      this.suggestions = [];
      this.lookupRan = false;
      this.destFormOpen = true;
      this.focusWhenShown('destName');
    },

    openEditDestination(destination) {
      this.loadCountries();
      this.countryListOpen = false;
      this.countryQuery = destination.countryName || destination.countryCode || '';
      this.destForm = {
        id: destination.id,
        name: destination.name || '',
        countryCode: destination.countryCode || '',
        countryName: destination.countryName || '',
        latitude: destination.latitude ?? '',
        longitude: destination.longitude ?? '',
        geonameId: destination.geonameId ?? null,
        timezone: destination.timezone || '',
        startDate: destination.startDate || '',
        endDate: destination.endDate || '',
        notes: destination.notes || '',
        suppressChecklist: !!destination.suppressChecklist,
      };
      this.destError = '';
      this.suggestions = [];
      this.lookupRan = false;
      this.destFormOpen = true;
    },

    closeDestinationForm() {
      this.destFormOpen = false;
      this.countryListOpen = false;
      this.suggestions = [];
    },

    // ── name lookup ─────────────────────────────────
    onDestNameInput() {
      clearTimeout(this.lookupTimer);
      this.suggestions = [];
      this.lookupRan = false;

      const query = this.destForm.name.trim();
      if (query.length < 2) return;

      this.lookupTimer = setTimeout(() => this.runLookup(query), LOOKUP_DEBOUNCE_MS);
    },

    async runLookup(query) {
      const token = ++this.lookupToken;
      this.lookupBusy = true;
      try {
        const found = await this.api.geocode(query);
        // A lookup that resolves after the list was dismissed must not reopen
        // it over the fields below — the reason for dismissing it was to reach
        // them.
        if (token !== this.lookupToken) return;
        this.suggestions = found;
      } catch {
        // A lookup outage must never block typing a destination in by hand.
        this.suggestions = [];
      } finally {
        if (token === this.lookupToken) {
          this.lookupBusy = false;
          this.lookupRan = true;
        }
      }
    },

    /**
     * Closes the suggestion list.
     *
     * The list is absolutely positioned over the fields that follow it, so
     * while it is open those fields cannot be clicked — a click where "Country
     * code" appears lands on a suggestion instead. Bumping the token also
     * abandons any lookup still in flight, so nothing reopens it a moment
     * later.
     */
    dismissSuggestions() {
      clearTimeout(this.lookupTimer);
      this.lookupToken++;
      this.suggestions = [];
      this.lookupBusy = false;
    },

    /**
     * "My city is not in this list" — keeps what was typed and gets out of the
     * way. Free text has always been saveable; what was missing was any way to
     * say so while the list was covering the rest of the form.
     */
    keepTypedName() {
      this.dismissSuggestions();
      this.lookupRan = false;
    },

    pickSuggestion(place) {
      this.destForm.name = place.name;
      this.destForm.countryCode = place.countryCode || '';
      this.destForm.countryName = place.countryName || '';
      // The country box shows a name, so it has to follow the place's country
      // rather than keep whatever was in it.
      this.countryQuery = place.countryName || '';
      this.destForm.latitude = place.latitude ?? '';
      this.destForm.longitude = place.longitude ?? '';
      this.destForm.geonameId = place.geonameId ?? null;
      this.destForm.timezone = place.timezone || '';
      this.suggestions = [];
      this.lookupRan = false;
    },

    suggestionMeta(place) {
      const bits = [];
      if (place.countryName) bits.push(place.countryName);
      if (place.population) bits.push(`pop ${formatPopulation(place.population)}`);
      return bits.join(' · ');
    },

    // ── save and delete ─────────────────────────────
    async saveDestination() {
      this.destError = '';

      const name = this.destForm.name.trim();
      if (!name) {
        this.destError = 'Give the destination a name.';
        return;
      }
      if (this.destForm.startDate && this.destForm.endDate
          && this.destForm.endDate < this.destForm.startDate) {
        this.destError = 'The end date cannot be before the start date.';
        return;
      }
      const outsideTrip = this.dateOutsideTrip(this.destForm.startDate, 'start date')
                       || this.dateOutsideTrip(this.destForm.endDate, 'end date');
      if (outsideTrip) {
        this.destError = outsideTrip;
        return;
      }

      const payload = {
        name,
        countryCode: this.destForm.countryCode || null,
        countryName: this.destForm.countryName || null,
        latitude: numberOrNull(this.destForm.latitude),
        longitude: numberOrNull(this.destForm.longitude),
        geonameId: this.destForm.geonameId || null,
        timezone: this.destForm.timezone || null,
        startDate: this.destForm.startDate || null,
        endDate: this.destForm.endDate || null,
        notes: this.destForm.notes || null,
        suppressChecklist: this.destForm.suppressChecklist,
      };

      this.destBusy = true;
      try {
        if (this.destForm.id) {
          await this.api.updateDestination(this.trip.id, this.destForm.id, payload);
          toast.success(`${name} updated`);
        } else {
          const created = await this.api.addDestination(this.trip.id, payload);
          const seeded = created.seededChecklist?.length || 0;
          toast.success(`${name} added — ${seeded} checklist item${seeded === 1 ? '' : 's'} created`);
        }
        this.destFormOpen = false;
        await this.reload();
      } catch (error) {
        this.destError = error.fullMessage;
      } finally {
        this.destBusy = false;
      }
    },

    // ── bulk selection ──────────────────────────────
    toggleDestSelected(id) {
      toggleId(this.destSelectedIds, id);
    },

    isDestSelected(id) {
      return this.destSelectedIds.includes(id);
    },

    /**
     * Selected destinations that are actually on screen — everything else
     * derives from this, so "Delete selected" can never remove a row a filter
     * is hiding. There is no filter on this tab today, but the intersection
     * still protects against a destination another member deleted or a reload
     * dropped.
     */
    get destSelected() {
      return selectedPresent(this.destSelectedIds, this.destinations);
    },

    /**
     * How many checklist items the selected destinations still have linked,
     * for the confirm wording — deleting a destination unlinks these items
     * rather than deleting them, so nothing already planned is lost.
     */
    get destSelectedChecklistCount() {
      return this.destSelected.reduce((sum, d) => sum + this.checklistCountFor(d), 0);
    },

    async confirmBulkDeleteDestinations() {
      const doomed = this.destSelected;
      if (!doomed.length) return;

      this.destBulkBusy = true;
      try {
        const { deleted, failed } = await runBulkDelete(
          doomed, (id) => this.api.deleteDestination(this.trip.id, id));

        this.destSelectedIds = [];
        this.destBulkOpen = false;

        if (failed) toast.error(`Deleted ${deleted} — ${failed} could not be removed`);
        else toast.success(`Deleted ${deleted} destination${deleted === 1 ? '' : 's'}`);

        await this.reload();
      } finally {
        this.destBulkBusy = false;
      }
    },

    // ── display helpers ─────────────────────────────
    destinationDates: (destination) => dateRange(destination.startDate, destination.endDate),

    // Days, counting both ends: the table is read as "how long are we in
    // Cusco", which is 7 days for 25-Oct to 31-Oct and 1 for a day trip.
    // Nights is the other reading of the same span and stays the API's
    // business — it is what a room is booked in. See CLAUDE.md.
    daysOf: (destination) => daysBetween(destination.startDate, destination.endDate),

    coordinates(destination) {
      if (destination.latitude == null || destination.longitude == null) return '—';
      return `${destination.latitude.toFixed(4)}, ${destination.longitude.toFixed(4)}`;
    },

    mapUrl(destination) {
      if (destination.latitude == null || destination.longitude == null) return null;
      return `https://www.google.com/maps/search/?api=1&query=${destination.latitude},${destination.longitude}`;
    },

    checklistCountFor(destination) {
      // Counts the items this destination itself produced, through the
      // seeder's provenance. The link a member picks is the country, and
      // counting by that would give every city in a country the same number —
      // saying nothing about any of them.
      return this.checklist.filter(
        (item) => item.seededFromDestinationId === destination.id).length;
    },
  };
}

function numberOrNull(value) {
  if (value === '' || value == null) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatPopulation(value) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}k`;
  return String(value);
}
