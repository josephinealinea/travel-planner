import { toast } from '../../toast.js';
import { category, timeRange, longDate, money, dateOf, timeOf } from '../../format.js';
import { toggleId, selectedPresent, runBulkDelete } from '../../selection.js';
import { toggleLocation, locationNames } from '../../location-picker.js';
import { condition, temperatureRange, sourceNote, sourceBadge, hasReading, unavailableHint }
  from '../../weather.js';

/**
 * One entry's row.
 *
 * A night in the middle of a stay belongs to a day but not to an hour, so it
 * shows no time; check-in and check-out show theirs. `item` is kept whole so
 * selecting, editing and deleting act on that entry itself.
 */
function entryView(item) {
  return {
    item,
    key: item.id,
    startAt: item.allDay ? null : item.startAt,
    endAt: item.allDay ? null : item.endAt,
    cost: item.cost,
    currency: item.currency,
  };
}

/**
 * One weather row: where the trip is on a given day.
 *
 * Not an itinerary entry and deliberately not shaped like one — nothing selects
 * it, edits it or deletes it, because there is nothing stored to act on. It is
 * computed from a destination's dates on every request, so the way to change it
 * is to change the destination.
 */
function weatherView(day) {
  return {
    day,
    key: `weather-${day.destinationId}-${day.date}`,
    weather: true,
  };
}

/**
 * The Itinerary tab. Mostly a read view of what planning produced, grouped by
 * day, but entries can also be added here directly without going through a
 * checklist item.
 *
 * Two different things share those day groups. An **itinerary entry** is
 * something somebody planned and stored. A **weather row** is where the trip
 * already is that day, because a destination's dates say so — so it needs no
 * planning and cannot be edited here. The Show filter is what lets you read
 * either on its own.
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
    // Unticked by default, like the Plan form — see checklist.js.
    costCharged: false,
    sharedByUserIds: [],
    countryCodes: [],
  });

  return {
    itinCategoryFilters: [],

    // ALL | WEATHER | ITINERARY. Mirrors checkStatusFilter on the checklist.
    itinShow: 'ALL',

    // Filled by loadWeather(), which the tab triggers itself — weather is not
    // part of the trip bundle, so nothing else fetches it.
    weatherDays: [],
    weatherLoading: false,
    weatherFailed: false,
    // The destinations these rows were fetched for — see weatherSignature.
    weatherFetchedFor: null,

    entryOpen: false,
    entryForm: blankEntry(),
    entryError: '',
    entryBusy: false,
    // bulk selection — this tab has no per-row delete
    itinSelectedIds: [],
    itinBulkOpen: false,
    itinBulkBusy: false,

    get filteredItinerary() {
      // The category chips filter plans, so choosing "Weather only" leaves
      // nothing for them to act on — and a stale tick must not keep an entry
      // on screen in a mode that is meant to exclude them.
      if (this.itinShow === 'WEATHER') return [];
      return this.itinerary.filter((item) => {
        if (this.itinCategoryFilters.length
            && !this.itinCategoryFilters.includes(item.category)) return false;
        return true;
      });
    },

    /** The weather rows the Show filter is letting through. */
    get filteredWeather() {
      return this.itinShow === 'ITINERARY' ? [] : this.weatherDays;
    },

    /**
     * Everything about the destinations that changes what the weather rows
     * should say: which places, where, and on which days. Renaming a
     * destination is in it because the row shows the name; a note or a
     * checklist count is not.
     */
    get weatherSignature() {
      return (this.destinations || [])
        .map((d) => [d.id, d.name, d.startDate, d.endDate, d.latitude, d.longitude].join('~'))
        .join('|');
    },

    /**
     * Fetches the weather rows.
     *
     * Triggered when the Itinerary tab is first opened rather than on page
     * load, so a member who never opens the tab never causes a call to
     * Open-Meteo — which is free but rate-limited per minute, and the climate
     * endpoint reaches that limit first (see WeatherClient).
     *
     * After that it refetches only when the signature above changes. `reload()`
     * runs after every mutation on the whole trip, and ticking a checklist item
     * has no bearing on the weather.
     *
     * A failure is not an error state for the page: the rows just do not
     * appear, and weatherFailed only drives a quiet note under the toolbar.
     */
    async loadWeather({ force = false } = {}) {
      if (this.weatherLoading) return;

      const signature = this.weatherSignature;
      if (!force && this.weatherFetchedFor === signature) return;

      this.weatherLoading = true;
      try {
        const response = await this.api.weather(this.tripId);
        this.weatherDays = response.days || [];
        this.weatherFailed = false;
        this.weatherFetchedFor = signature;
      } catch (error) {
        this.weatherDays = [];
        this.weatherFailed = true;
        // Left unset so the next visit tries again rather than trusting a
        // failure as "already fetched".
        this.weatherFetchedFor = null;
      } finally {
        this.weatherLoading = false;
      }
    },

    /**
     * [{ date, label, entries, weather }], with undated entries collected at
     * the end.
     *
     * A day exists here if either half puts something on it, so "Weather only"
     * still shows the days a destination covers even when nothing is planned
     * on them, and a planned day with no destination behind it still appears.
     */
    get itineraryDays() {
      const byDay = new Map();
      const weatherByDay = new Map();
      const undated = [];

      this.filteredWeather.forEach((day) => {
        if (!day.date) return;
        if (!weatherByDay.has(day.date)) weatherByDay.set(day.date, []);
        weatherByDay.get(day.date).push(weatherView(day));
      });

      // One row per item, on its own day. A stay covering several nights is
      // already several items — ItineraryService writes them at create time —
      // so expanding here as well would show every night twice.
      this.filteredItinerary.forEach((item) => {
        if (!item.startAt) {
          undated.push(entryView(item));
          return;
        }
        const date = item.startAt.slice(0, 10);
        if (!byDay.has(date)) byDay.set(date, []);
        byDay.get(date).push(entryView(item));
      });

      const dates = [...new Set([...byDay.keys(), ...weatherByDay.keys()])].sort();
      const days = dates.map((date) => ({
        date,
        label: longDate(date),
        entries: byDay.get(date) || [],
        weather: weatherByDay.get(date) || [],
      }));

      if (undated.length) {
        // Nowhere to put weather for a day that has no date.
        days.push({ date: null, label: 'No date yet', entries: undated, weather: [] });
      }
      return days;
    },

    /** What the day heading counts, given both halves can be showing. */
    dayCount(day) {
      const parts = [];
      if (day.entries.length) {
        parts.push(`${day.entries.length} item${day.entries.length === 1 ? '' : 's'}`);
      }
      if (day.weather.length) {
        parts.push(day.weather.length === 1
          ? day.weather[0].day.destinationName
          : `${day.weather.length} places`);
      }
      return parts.join(' · ');
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
      this.focusWhenShown('entryDescription');
    },

    openEditEntry(item) {
      this.entryForm = {
        id: item.id,
        category: item.category,
        description: item.description || '',
        startDate: dateOf(item.startAt),
        // An all-day entry is stored at T00:00, but that midnight is a
        // placeholder, not a time. Prefilling it made Save send allDay: false
        // and quietly turned the entry into a timed 00:00 one.
        startTime: item.allDay ? '' : timeOf(item.startAt),
        endDate: dateOf(item.endAt),
        endTime: item.allDay ? '' : timeOf(item.endAt),
        cost: item.cost ?? '',
        currency: item.currency || this.budget.displayCurrency || '',
        costCharged: this.chargedOfPlan(item),
        sharedByUserIds: this.sharersOfPlan(item),
        countryCodes: [...(item.countryCodes || [])],
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
      const outsideTrip = this.dateOutsideTrip(this.entryForm.startDate, 'start date')
                       || this.dateOutsideTrip(this.entryForm.endDate, 'end date');
      if (outsideTrip) {
        this.entryError = outsideTrip;
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
          // A date with no time belongs to a day, not an hour — the API
          // records it the same way a stay's middle nights are recorded, and
          // the row shows "—" instead of a misleading 00:00.
          allDay: !this.entryForm.startTime,
          currency: cost == null ? null : (this.entryForm.currency || null),
          costCharged: this.entryForm.costCharged,
          costSharedByUserIds: this.entryForm.sharedByUserIds,
          countryCodes: this.entryForm.countryCodes,
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

    // ── bulk selection ──────────────────────────────
    toggleItinSelected(id) {
      toggleId(this.itinSelectedIds, id);
    },

    isItinSelected(id) {
      return this.itinSelectedIds.includes(id);
    },

    /** Selected entries the filters are actually showing — see checkSelected. */
    get itinSelected() {
      return selectedPresent(this.itinSelectedIds, this.filteredItinerary);
    },

    /** Selected entries carrying a cost — each takes a budget row with it. */
    get itinSelectedCostCount() {
      return this.itinSelected.filter((item) => item.cost).length;
    },

    async confirmBulkDeleteEntries() {
      const doomed = this.itinSelected;
      if (!doomed.length) return;

      this.itinBulkBusy = true;
      try {
        const { deleted, failed } = await runBulkDelete(
          // deleteEntry, not deletePlan: a tick here selects one day of a
          // plan, and removing it must not take the rest of the booking.
          doomed, (id) => this.api.deleteEntry(this.trip.id, id));

        this.itinSelectedIds = [];
        this.itinBulkOpen = false;

        if (failed) toast.error(`Removed ${deleted} — ${failed} could not be removed`);
        else toast.success(`Removed ${deleted} itinerary item${deleted === 1 ? '' : 's'}`);

        await this.reload();
      } finally {
        this.itinBulkBusy = false;
      }
    },

    // ── weather display ─────────────────────────────
    weatherIcon: (day) => condition(day).icon,
    weatherCondition: (day) => condition(day).label,
    weatherTemps: (day) => temperatureRange(day),
    weatherNote: (day) => sourceNote(day),
    weatherBadge: (day) => sourceBadge(day),
    weatherHasReading: (day) => hasReading(day),

    /**
     * The place, without a flag on it.
     *
     * The flag rides in the country chip instead — see weatherCountryLabel —
     * so a card reads exactly like an itinerary entry underneath it: the thing
     * itself, then a chip saying which country it is in. Putting the flag in
     * both places showed it twice a few pixels apart.
     */
    weatherPlace: (day) => day.destinationName,

    /**
     * The country chip, rendered by the same helper the itinerary entries use.
     *
     * Deliberately `countryLabel` rather than anything bespoke: that is what
     * guarantees a weather card and an itinerary entry below it label the same
     * country identically — flag, name and all — however that label later
     * changes. A destination with no country yields '' and the chip hides.
     */
    weatherCountryLabel(day) {
      return this.countryLabel(day.countryCode ? [day.countryCode] : []);
    },

    weatherUnavailableText: (day) => unavailableHint(day).text,
    weatherUnavailableTitle: (day) => unavailableHint(day).title,

    // ── display helpers ─────────────────────────────
    entryTime: (item) => timeRange(item.startAt, item.endAt) || '—',
    entryCost: (item) => money(item.cost, item.currency),
    categoryOf: (value) => category(value),

    /** Shows which checklist item a plan came from. */
    sourceChecklist(item) {
      if (!item.checklistItemId) return null;
      return this.checklist.find((check) => check.id === item.checklistItemId)?.description || null;
    },

    // ── location picker (shared with the checklist and expense forms) ──
    toggleLocation,

    countryLabels(countryCodes) {
      return locationNames(this.destinations, countryCodes);
    },

    /** Comma-joined, for chip/label text; empty when nothing is linked. */
    countryLabel(countryCodes) {
      return this.countryLabels(countryCodes).join(', ');
    },
  };
}

function combine(date, time) {
  if (!date) return null;
  return `${date}T${time || '00:00'}:00`;
}
