package com.josephinealinea.planner.identity.domain;

/**
 * An account's plan. Only BASIC is capped today (see TripLimitProperties);
 * ROCKSTAR and PRO are unlimited. Declared in ascending order of privilege.
 */
public enum TierLevel { BASIC, PRO, ROCKSTAR }
