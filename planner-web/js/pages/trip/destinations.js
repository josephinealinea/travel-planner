import { toast } from '../../toast.js';
import { dateRange, nightsBetween } from '../../format.js';

const LOOKUP_DEBOUNCE_MS = 250;

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
  });

  return {
    destForm: blankForm(),
    destFormOpen: false,
    destError: '',
    destBusy: false,
    deletingDestination: null,

    suggestions: [],
    lookupBusy: false,
    lookupRan: false,
    lookupTimer: null,

    openAddDestination() {
      this.destForm = blankForm();
      this.destError = '';
      this.suggestions = [];
      this.lookupRan = false;
      this.destFormOpen = true;
      this.$nextTick(() => this.$refs.destName?.focus());
    },

    openEditDestination(destination) {
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
      };
      this.destError = '';
      this.suggestions = [];
      this.lookupRan = false;
      this.destFormOpen = true;
    },

    closeDestinationForm() {
      this.destFormOpen = false;
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
      this.lookupBusy = true;
      try {
        this.suggestions = await this.api.geocode(query);
      } catch {
        // A lookup outage must never block typing a destination in by hand.
        this.suggestions = [];
      } finally {
        this.lookupBusy = false;
        this.lookupRan = true;
      }
    },

    pickSuggestion(place) {
      this.destForm.name = place.name;
      this.destForm.countryCode = place.countryCode || '';
      this.destForm.countryName = place.countryName || '';
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

    askDeleteDestination(destination) {
      this.deletingDestination = destination;
    },

    async confirmDeleteDestination() {
      const destination = this.deletingDestination;
      try {
        const result = await this.api.deleteDestination(this.trip.id, destination.id);
        this.deletingDestination = null;
        const kept = result?.checklistItemsUnlinked || 0;
        toast.success(kept
          ? `${destination.name} deleted — ${kept} checklist item${kept === 1 ? '' : 's'} kept`
          : `${destination.name} deleted`);
        await this.reload();
      } catch (error) {
        toast.error(error.fullMessage);
        this.deletingDestination = null;
      }
    },

    // ── display helpers ─────────────────────────────
    destinationDates: (destination) => dateRange(destination.startDate, destination.endDate),

    nightsOf: (destination) => nightsBetween(destination.startDate, destination.endDate),

    coordinates(destination) {
      if (destination.latitude == null || destination.longitude == null) return '—';
      return `${destination.latitude.toFixed(4)}, ${destination.longitude.toFixed(4)}`;
    },

    mapUrl(destination) {
      if (destination.latitude == null || destination.longitude == null) return null;
      return `https://www.google.com/maps/search/?api=1&query=${destination.latitude},${destination.longitude}`;
    },

    checklistCountFor(destination) {
      return this.checklist.filter((item) => item.destinationId === destination.id).length;
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
