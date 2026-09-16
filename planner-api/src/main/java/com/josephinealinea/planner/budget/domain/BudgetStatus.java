package com.josephinealinea.planner.budget.domain;

/**
 * Whether an expense has actually been charged yet.
 *
 * The distinction is the difference between what a trip has cost and what it
 * is going to cost. A booking made but not yet paid, a tour that takes cash on
 * the day, a deposit with the balance still due — all of them are real numbers
 * worth planning around, and none of them belongs in the figure that answers
 * "how much have I spent".
 *
 * So the two live side by side rather than one replacing the other: the
 * budget's headline total and its pie count {@code CONFIRMED} only, and the
 * forecast breakdowns count both. Nothing is ever hidden — a pending row is
 * listed in the table like any other, labelled Pending.
 *
 * <b>CONFIRMED is the default</b>, and deliberately so. Every row written
 * before this existed was a charge somebody had already made, and a
 * budget that silently reclassified the whole trip as speculative the day
 * this shipped would be worse than one that never knew the difference. A
 * record with no {@code status} in its YAML therefore reads as CONFIRMED.
 */
public enum BudgetStatus {
    CONFIRMED,
    PENDING
}
