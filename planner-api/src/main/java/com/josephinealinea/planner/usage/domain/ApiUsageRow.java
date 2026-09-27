package com.josephinealinea.planner.usage.domain;

/**
 * One counter: how many calls a service has made in a UTC calendar month.
 * A new month is a new row, so nothing ever has to be reset.
 */
public record ApiUsageRow(String service, String month, int calls) {}
