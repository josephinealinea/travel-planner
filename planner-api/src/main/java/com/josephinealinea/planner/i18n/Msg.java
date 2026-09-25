package com.josephinealinea.planner.i18n;

/**
 * A message used as an argument of another, for the words inside a sentence
 * that need translating too ("budget", "destinations"). {@link Messages}
 * resolves it in the same language as the sentence around it.
 */
public record Msg(String key, Object... args) {}
