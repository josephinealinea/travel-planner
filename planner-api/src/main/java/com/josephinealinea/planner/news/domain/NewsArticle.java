package com.josephinealinea.planner.news.domain;

import java.time.Instant;

/** One story. No full body ships on either provider's free plan — a title, a
 * snippet, an image and a link out is all there is. */
public record NewsArticle(String title, String description, String url, String imageUrl,
                          String sourceName, Instant publishedAt) {}
