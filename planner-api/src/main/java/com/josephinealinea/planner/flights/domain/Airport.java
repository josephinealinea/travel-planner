package com.josephinealinea.planner.flights.domain;

/** An airport as AeroDataBox describes it. Coordinates and country are kept because refetching every saved flight later would cost quota. */
public record Airport(String iata, String icao, String name, String city, String countryCode,
                      String timezone, Double lat, Double lon) {}
