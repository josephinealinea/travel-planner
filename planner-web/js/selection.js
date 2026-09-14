/**
 * Row selection for bulk actions.
 *
 * Kept as free functions over a plain array rather than a class or a Set:
 * both trip tabs merge into one Alpine component, so each tab owns its own
 * array under its own name, and Alpine tracks plain arrays reliably where it
 * does not track Sets.
 */

/** Adds or removes an id, mutating in place so Alpine sees the change. */
export function toggleId(list, id) {
  const at = list.indexOf(id);
  if (at >= 0) list.splice(at, 1);
  else list.push(id);
}

/**
 * The selected items that still exist, in the order the list shows them.
 *
 * Everything derives from this intersection, so an id that disappears —
 * another member deleted it, a filter hid it, a reload dropped it — simply
 * stops counting. There is no stale-selection cleanup to forget.
 */
export function selectedPresent(ids, items) {
  return items.filter((item) => ids.includes(item.id));
}

/**
 * Deletes items one at a time through the ordinary single-delete endpoint.
 *
 * Looping the real endpoint is deliberate: it is what keeps the server-side
 * cascades — a plan's budget row, a checklist item's plan links — on exactly
 * the path they already take for a single delete.
 *
 * Sequential, not Promise.all: the YAML store rewrites one file per trip under
 * a lock, so parallel deletes would queue anyway while making a partial
 * failure harder to report accurately.
 */
export async function runBulkDelete(items, deleteOne) {
  let deleted = 0;
  let failed = 0;

  for (const item of items) {
    try {
      await deleteOne(item.id);
      deleted += 1;
    } catch {
      // Keep going: one bad id should not strand the rest of the selection.
      failed += 1;
    }
  }

  return { deleted, failed };
}
