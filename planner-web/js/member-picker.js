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
 */

/** Adds or removes a member, mutating the array in place. */
export function toggleSharer(sharedByUserIds, userId) {
  toggleId(sharedByUserIds, userId);
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
      label: member.displayName || member.email || member.userId,
    }));
}
