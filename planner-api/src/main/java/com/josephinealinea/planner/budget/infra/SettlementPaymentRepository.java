package com.josephinealinea.planner.budget.infra;

import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.storage.TripScopedRepository;

/**
 * The storage contract for settlement payments: {@link YamlSettlementPaymentRepository}
 * with the database flag off, a JDBC implementation with it on. Nothing here
 * beyond the generic per-trip contract — payments have no sort field of their
 * own, so the list is in insertion order in both stores.
 */
public interface SettlementPaymentRepository extends TripScopedRepository<SettlementPayment> {
}
