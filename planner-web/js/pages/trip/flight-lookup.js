import { t } from '../../i18n/index.js';

/**
 * The flight number field on both forms (the checklist Plan form and the
 * Itinerary tab's entry form). One module because the two forms name their
 * fields differently (planForm, entryForm), so the state lives on the form
 * object itself and every method takes the form it works on.
 *
 * Form fields it adds (see blankFlight):
 *   flightNumber    what the member typed
 *   flightSnapshot  the airports and numbers a lookup found, or the saved ones
 *   flightStatus    idle | busy | choose | found | notFound | unavailable | saved
 *   flightChoices   the legs to pick from, when one number flies several that day
 *   flightNote      the line shown under the field
 *   flightTouched   the member typed or looked up: only then is a flight sent
 *   flightHad       the entry already had a flight, so emptying the field clears it
 *
 * The lookup never runs on save or on typing, so it cannot block a save.
 */

export const blankFlight = () => ({
  flightNumber: '',
  flightSnapshot: null,
  flightChoices: [],
  flightStatus: 'idle',
  flightNote: '',
  flightTouched: false,
  flightHad: false,
  flightTicket: 0,
});

const operatedLabel = (operatingNumber) =>
  operatingNumber ? t('flight.operatedAs', { number: operatingNumber }) : '';

/** The form fields for an entry that already has a flight (or none). */
export function flightFromRecord(item) {
  const flight = item.flight;
  if (!flight) return blankFlight();
  return {
    flightNumber: flight.number || '',
    flightSnapshot: flight,
    flightChoices: [],
    flightStatus: 'saved',
    flightNote: flight.from && flight.to
      ? t('flight.saved', {
          number: flight.number,
          operated: operatedLabel(flight.operatingNumber),
          from: flight.from.iata,
          to: flight.to.iata,
        })
      : '',
    flightTouched: false,
    flightHad: true,
    flightTicket: 0,
  };
}

export function flightLookup() {
  return {
    /** A number and a start date are both needed. */
    flightCanLookup(form) {
      return !!form.flightNumber.trim() && !!form.startDate && form.flightStatus !== 'busy';
    },

    /**
     * The member edited the number or the start date. Whatever a lookup found
     * belonged to the old values, so it is dropped rather than saved against a
     * different flight. Times already filled stay: they are ordinary field
     * values by now.
     */
    flightChanged(form, numberEdited) {
      // Invalidates any lookup still in flight: its answer is for old values.
      form.flightTicket = (form.flightTicket || 0) + 1;
      form.flightSnapshot = null;
      form.flightChoices = [];
      form.flightNote = '';
      form.flightStatus = 'idle';
      if (numberEdited) form.flightTouched = true;
    },

    /** One click: look the flight up and fill the blank fields. Never overwrites. */
    async flightLookup(form) {
      if (!this.flightCanLookup(form)) return;
      const number = form.flightNumber.trim();
      const date = form.startDate;
      const ticket = form.flightTicket = (form.flightTicket || 0) + 1;
      form.flightStatus = 'busy';
      form.flightNote = '';
      // True when the member edited the number or date (or looked up again)
      // while this request was out: the answer then belongs to old values.
      const stale = () => form.flightTicket !== ticket
        || form.flightNumber.trim() !== number || form.startDate !== date;
      try {
        const found = await this.api.lookupFlight(this.trip.id, number, date);
        if (stale()) return;
        if (found.status === 'found' && !found.choices?.length && !found.flight?.number) found.status = 'unavailable';
        if (found.status !== 'found') {
          form.flightStatus = found.status === 'notFound' ? 'notFound' : 'unavailable';
          form.flightSnapshot = null;
          return;
        }
        if (found.choices?.length > 1) {
          // One number, several legs that day: the member says which is theirs.
          form.flightChoices = found.choices.map((choice) => ({ ...choice, operatingNumber: found.operatingNumber }));
          form.flightStatus = 'choose';
          form.flightNote = t('flight.choose', { count: found.choices.length });
          return;
        }
        this.flightApply(form, found);
      } catch (error) {
        console.warn('flight lookup failed', error);
        if (stale()) return;
        form.flightStatus = 'unavailable';
        form.flightSnapshot = null;
      }
    },

    /** The member picked one of the legs a lookup offered. */
    flightPick(form, choice) {
      this.flightApply(form, choice);
      form.flightChoices = [];
    },

    /** Fills the blank fields from one answer and keeps its snapshot. Never overwrites. */
    flightApply(form, found) {
      if (!form.startTime) form.startTime = found.departureTime || '';
      if (!form.endDate) form.endDate = found.arrivalDate || '';
      if (!form.endTime) form.endTime = found.arrivalTime || '';
      form.flightSnapshot = found.flight;
      form.flightTouched = true;
      form.flightStatus = 'found';
      form.flightNote = t('flight.filled', {
        number: found.flight.number,
        operated: operatedLabel(found.operatingNumber),
        from: found.flight.from?.iata || '',
        departure: found.departureTime || '',
        to: found.flight.to?.iata || '',
        arrival: found.arrivalTime || '',
      });
    },

    /** The line on one leg button. */
    flightChoiceLabel(choice) {
      return t('flight.choice', {
        from: choice.flight.from?.iata || '',
        departure: choice.departureTime || '',
        to: choice.flight.to?.iata || '',
        arrival: choice.arrivalTime || '',
      });
    },

    /**
     * What to add to the save request. Nothing unless the member touched the
     * field, so opening and saving a form never rewrites a flight. An emptied
     * field on an entry that had one sends an empty number, which clears it.
     */
    flightPayload(form, isTransport) {
      if (!isTransport || !form.flightTouched) return {};
      const number = form.flightNumber.trim();
      if (!number) return form.flightHad ? { flight: { number: '' } } : {};
      return { flight: { ...(form.flightSnapshot || {}), number } };
    },

    /** The pills on a plan card or an itinerary row: the number, then each airport on its own. */
    flightPills(flight) {
      if (!flight) return [];
      const airport = (a) => (a && a.iata ? '📍 ' + (a.name ? `${a.name} (${a.iata})` : a.iata) : '');
      const numberPill = flight.number
        ? '✈️ ' + (flight.airline ? `${flight.airline} • ${flight.number}` : flight.number)
        : '';
      return [numberPill, airport(flight.from), airport(flight.to)].filter(Boolean);
    },

    /**
     * Sanitises a flight number as it is typed: upper case, no spaces, and
     * nothing but letters and digits — the same shape the API requires
     * (`FlightNumbers.valid`), so a mistyped character is rejected immediately
     * rather than only on save.
     */
    sanitiseFlightNumber(raw) {
      return (raw || '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12);
    },

    onFlightNumberInput(form, event) {
      form.flightNumber = this.sanitiseFlightNumber(event.target.value);
      this.flightChanged(form, true);
    },
  };
}
