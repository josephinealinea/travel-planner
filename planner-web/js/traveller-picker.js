import { toggleId } from './selection.js';

/**
 * "Who's going": which travel buddies a destination, checklist item or plan is
 * for. Three states, mirroring travellerIds on the API (see Travellers there):
 *   inherit  — not set, same as the parent (a checklist item's destination,
 *              a plan's checklist item). Stored as null.
 *   everyone — explicitly the whole trip. Stored as [].
 *   named    — just these buddies. Stored as their ids.
 * A destination's parent is the trip itself, so for it "inherit" and
 * "everyone" mean the same thing and the form only offers Everyone.
 *
 * Markup is copied per form, like member-picker.js beside it, because there is
 * no templating for these static pages.
 */

export function travellersFromRecord(record, rootIsTrip = false) {
  const ids = record?.travellerIds;
  if (ids == null) return rootIsTrip ? { mode: 'everyone', ids: [] } : { mode: 'inherit', ids: [] };
  return { mode: ids.length ? 'named' : 'everyone', ids: [...ids] };
}

export function newTravellers(rootIsTrip = false) {
  return rootIsTrip ? { mode: 'everyone', ids: [] } : { mode: 'inherit', ids: [] };
}

/** A copy for comparing against later, so an untouched picker sends nothing. */
export function snapshotTravellers(state) {
  return { mode: state.mode, ids: [...state.ids] };
}

/** "Choose…": start from what it was inheriting, so nothing is lost by opening it. */
export function chooseTravellers(state, inheritedIds) {
  state.ids.splice(0, state.ids.length, ...(inheritedIds || []));
  state.mode = state.ids.length ? 'named' : 'everyone';
}

/** "Back to Cusco's". */
export function followParent(state) {
  state.ids.splice(0);
  state.mode = 'inherit';
}

export function toggleTraveller(state, userId) {
  toggleId(state.ids, userId);
  state.mode = state.ids.length ? 'named' : 'everyone';
}

export function everyoneGoes(state) {
  state.ids.splice(0);
  state.mode = 'everyone';
}

/** Who it is for right now, [] meaning the whole trip. */
export function effectiveTravellers(state, inheritedIds) {
  if (state.mode === 'inherit') return [...(inheritedIds || [])];
  return state.mode === 'everyone' ? [] : [...state.ids];
}

/**
 * What to send. Nothing when unchanged, so saving an unrelated edit never
 * turns "not set" into "[]" — a destination opened and saved must not stop
 * being unset merely because the form showed "Everyone".
 */
export function travellersPayload(state, initial) {
  const same = initial && state.mode === initial.mode
    && state.ids.length === initial.ids.length
    && state.ids.every((id, i) => id === initial.ids[i]);
  if (same) return {};
  if (state.mode === 'inherit') return { inheritTravellers: true };
  return { travellerIds: state.mode === 'everyone' ? [] : [...state.ids] };
}

/**
 * Who a new cost starts shared by, decided with the user: the plan's
 * travellers when it is for particular buddies, otherwise just the member
 * entering it — which is what every new cost defaulted to before travellers
 * existed, so ordinary plans behave exactly as they did. The forms always
 * send what this returns; ItineraryService applies only the first half, for a
 * request that sends no sharers at all (it has no "member at the keyboard").
 */
export function defaultCostSharers(planTravellers, currentUserId) {
  if (planTravellers && planTravellers.length) return [...planTravellers];
  return currentUserId ? [currentUserId] : [];
}
