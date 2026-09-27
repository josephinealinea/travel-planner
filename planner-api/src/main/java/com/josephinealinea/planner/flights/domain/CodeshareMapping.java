package com.josephinealinea.planner.flights.domain;

import java.time.Instant;

/** A codeshare pairing (KL2842 to BT857). Permanent: it is a fact about the schedule. */
public record CodeshareMapping(String bookedNumber, String operatingNumber, Instant resolvedAt) {}
