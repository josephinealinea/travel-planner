import { toggleId } from './selection.js';

/**
 * Shared "Shared by" member picker.
 *
 * The expense form, the checklist's Plan form and the itinerary entry form each
 * let a member say who a cost is shared by. All three should look and behave
 * identically, so the toggle and display logic lives here once — the same
 * arrangement as location-picker.js beside it, and for the same reason.
 *
 * Selecting two people halves the cost on each of their budgets; the division
 * itself happens on the API, which is the only place that can do it to the cent
 * (see TripMembers). Nothing here divides anything.
 *
 * There is no templating system for static HTML pages in this project, so the
 * markup is copied per form rather than shared — but it is exactly this block,
 * reusing the chip-group/chip classes already styled elsewhere:
 *
 *   <div class="field">
 *     <label id="my-shared-by-label">Shared by</label>
 *     <div class="chip-group" role="group" aria-labelledby="my-shared-by-label">
 *       <template x-for="person in tripSharers" :key="person.userId">
 *         <button type="button" class="chip"
 *                 :aria-pressed="form.sharedByUserIds.includes(person.userId)"
 *                 @click="toggleSharer(form.sharedByUserIds, person.userId)"
 *                 x-text="person.label"></button>
 *       </template>
 *     </div>
 *   </div>
 *
 * `form.sharedByUserIds` is a plain array of member user ids on whatever form
 * object the field belongs to.
 *
 * "Paid by" sits directly after it on the same three forms, drawn from the same
 * member list but holding one id rather than a list — the same chips, pressed
 * one at a time:
 *
 *   <div class="field">
 *     <label id="my-paid-by-label">Paid by</label>
 *     <div class="chip-group" role="group" aria-labelledby="my-paid-by-label">
 *       <template x-for="person in tripSharers" :key="person.userId">
 *         <button type="button" class="chip"
 *                 :aria-pressed="form.paidByUserId === person.userId"
 *                 @click="choosePayer(form, person.userId)"
 *                 x-text="person.label"></button>
 *       </template>
 *     </div>
 *   </div>
 *
 * `form.paidByUserId` is a member user id, or '' for nobody. Who paid changes
 * no figure: every total and share is decided by "Shared by" alone.
 */

/** Adds or removes a member, mutating the array in place. */
export function toggleSharer(sharedByUserIds, userId) {
  toggleId(sharedByUserIds, userId);
}

/**
 * Hands the cost back to the whole trip by clearing the names.
 *
 * "Everyone" is the empty list, not a list of every member: naming them all
 * would freeze today's membership into the row, so somebody joining later
 * would be left out of a cost that is plainly the group's — and somebody
 * leaving would keep their name on it. That is the same reason the split is
 * resolved per request rather than stored. See TripMembers on the API.
 *
 * Mutated in place rather than reassigned, like toggleSharer above, so the
 * array the form holds stays the array the chips are bound to.
 */
export function shareWithEveryone(sharedByUserIds) {
  sharedByUserIds.splice(0, sharedByUserIds.length);
}

/** True while a cost names nobody, which is what "Everyone" means. */
export function sharedWithEveryone(sharedByUserIds) {
  return !sharedByUserIds || sharedByUserIds.length === 0;
}

/**
 * True when this form says its cost has already been charged.
 *
 * Three forms spell the same field two ways — the budget's own form calls it
 * `charged`, the Plan and itinerary forms `costCharged` — so the rule below
 * reads both rather than being written out three times with the odds of one
 * copy drifting.
 */
function isCharged(form) {
  return form.charged === true || form.costCharged === true;
}

/**
 * Picks the one member who paid, or clears it when they are picked again.
 * Single choice rather than a list: one card goes down per expense.
 *
 * <b>Clearing is refused while the expense is charged.</b> Money that has left
 * someone's hand with no record of whose can appear in no settlement, and the
 * API refuses it outright — so the chip simply does not clear, rather than
 * letting somebody build a state that fails on Save. A pending cost still
 * clears: nobody has paid it, so nobody has to be named.
 */
export function choosePayer(form, userId) {
  if (form.paidByUserId === userId && isCharged(form)) return;
  form.paidByUserId = form.paidByUserId === userId ? '' : userId;
}

/**
 * Keeps "Expense already charged" and "Paid by" consistent as the box is
 * ticked: a charged expense needs a payer, and the member at the keyboard is
 * the same default a brand-new cost gets. They can still pick somebody else.
 *
 * Takes the checkbox's own value rather than reading the form, so it does not
 * depend on whether x-model has applied by the time this runs.
 */
export function chargedToggled(form, currentUserId, charged) {
  if (charged && !form.paidByUserId) form.paidByUserId = currentUserId || '';
}

/**
 * The people a cost can be shared by: the trip's members, labelled the way
 * they are labelled everywhere else — screen name if they have chosen one,
 * otherwise the email they were invited with.
 *
 * That resolution is not done here: the API already does it per request as
 * `displayName`, which is why a member's stored records carry a user id and
 * never a name. Reading it off the member list means a screen name change shows
 * up immediately instead of leaving stale copies behind.
 */
export function sharersOfTrip(members) {
  return (members || [])
    .filter((member) => member.userId)
    .map((member) => ({
      userId: member.userId,
      label: member.displayName || member.userId,
    }));
}
