package com.josephinealinea.planner.news.domain;

import java.util.List;

/** One destination's stories. Absent from the response entirely if empty —
 * see NewsService. */
public record DestinationNewsGroup(String destinationName, String countryCode, String countryFlag,
                                   List<NewsArticle> articles) {}
