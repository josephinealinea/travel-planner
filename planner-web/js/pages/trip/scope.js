import { savedScope, saveScope } from '../../trip-scope.js';

const KEYS = { destinations: 'destinationIds', checklist: 'checklistItemIds', itinerary: 'itineraryItemIds' };
const NOUNS = {
  destinations: ['destination', 'destinations'],
  checklist: ['checklist item', 'checklist items'],
  itinerary: ['plan', 'plans'],
};

/**
 * Mine / Whole trip. The API decides which records are this member's
 * (`mine`, see Travellers on the API) and this only filters on it — never
 * re-deriving who is going, or the planner and a personal published page
 * could disagree. Link pickers keep reading the whole lists; only what is
 * shown reads the scoped ones.
 *
 * Only the owner gets the switch; every other travel buddy always sees Mine.
 * Still a view, not privacy: the whole trip is in the bundle either way.
 */
export function scopeTab() {
  return {
    scope: savedScope(),
    mine: { destinationIds: [], checklistItemIds: [], itineraryItemIds: [] },
    namedTravellers: { destinations: {}, checklist: {}, itinerary: {} },

    setScope(scope) {
      this.scope = scope;
      saveScope(scope);
    },

    /** The switch is the owner's alone; everyone else is always on Mine. */
    get canSeeWholeTrip() { return !!this.isOwner; },

    get showingMine() { return !this.canSeeWholeTrip || this.scope === 'mine'; },

    get scopedDestinations() { return this.inScope('destinations', this.destinations); },
    get scopedChecklist() { return this.inScope('checklist', this.checklist); },
    get scopedItinerary() { return this.inScope('itinerary', this.itinerary); },

    inScope(kind, all) {
      if (!this.showingMine) return all;
      const ids = this.mine[KEYS[kind]] || [];
      return all.filter((record) => ids.includes(record.id));
    },

    /** "2 more destinations", or '' when Mine hides nothing of this kind. */
    hiddenLabel(kind) {
      // Only the owner can act on "there is more": nobody else has the switch.
      if (!this.showingMine || !this.canSeeWholeTrip) return '';
      const all = { destinations: this.destinations, checklist: this.checklist, itinerary: this.itinerary }[kind];
      const hidden = all.length - this.inScope(kind, all).length;
      if (hidden <= 0) return '';
      const [one, many] = NOUNS[kind];
      return `${hidden} more ${hidden === 1 ? one : many}`;
    },

    /** True when a just-saved record is not on this member's list. */
    isHiddenByScope(kind, id) {
      return !(this.mine[KEYS[kind]] || []).includes(id);
    },

    /**
     * The toast for something just saved that the member will not see: the
     * owner is told where it went, anyone else that it has left their view.
     */
    notOnListMessage(message) {
      return this.canSeeWholeTrip
        ? `${message}. It's not on your list, so it shows under Whole trip.`
        : `${message}. It's not on your list, so it won't show here.`;
    },

    namesOf(ids) {
      return (ids || [])
        .map((id) => this.members.find((m) => m.userId === id)?.displayName)
        .filter(Boolean)
        .join(', ');
    },

    /** "Maia, Joey" for a record not for the whole trip, '' otherwise. */
    travellerNames(kind, id) {
      return this.namesOf((this.namedTravellers[kind] || {})[id]);
    },

    /**
     * The same names as a list, one pill each. A record for the whole trip has
     * no names of its own, so it gets a single "Everyone" pill instead of a
     * blank that reads as "not set".
     */
    travellerNameList(kind, id) {
      const ids = (this.namedTravellers[kind] || {})[id] || [];
      if (!ids.length) return ['Everyone'];
      return ids
        .map((userId) => this.members.find((m) => m.userId === userId)?.displayName)
        .filter(Boolean);
    },

    /**
     * Pills for a table cell: everyone when there are one or two, otherwise
     * the first name and a "+N" for the rest, so the row stays narrow.
     */
    travellerPills(kind, id) {
      // The signed-in member leads, so their own name is the one that stays visible.
      const ids = [...((this.namedTravellers[kind] || {})[id] || [])];
      if (!ids.length) return [{ text: '👥 Everyone', title: 'Everyone on the trip' }];
      const mine = ids.indexOf(this.currentUserId);
      if (mine > 0) ids.unshift(ids.splice(mine, 1)[0]);
      const names = ids
        .map((userId) => this.members.find((m) => m.userId === userId)?.displayName)
        .filter(Boolean);
      if (names.length <= 2) return names.map((name) => ({ text: '👥 ' + name, title: name }));
      return [
        { text: '👥 ' + names[0], title: names[0] },
        { text: '+' + (names.length - 1), title: names.slice(1).join(', ') },
      ];
    },
  };
}
