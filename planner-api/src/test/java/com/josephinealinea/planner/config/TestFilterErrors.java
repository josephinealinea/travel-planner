package com.josephinealinea.planner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.i18n.Messages;
import com.josephinealinea.planner.i18n.RequestLocale;

/** A real FilterErrors over the real message files, for tests that build a filter by hand. */
final class TestFilterErrors {
    private TestFilterErrors() {}

    static FilterErrors create() {
        Messages messages = I18nConfig.standalone();
        return new FilterErrors(messages, new RequestLocale(messages, null), new ObjectMapper());
    }
}
