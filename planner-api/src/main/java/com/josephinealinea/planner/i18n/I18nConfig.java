package com.josephinealinea.planner.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class I18nConfig {

    private static final Pattern FILE = Pattern.compile("messages_([A-Za-z]{2,3})\\.properties");

    @Bean
    MessageSource messageSource() {
        return source();
    }

    @Bean
    Messages messages() {
        return standalone();
    }

    /** For tests that want the real message files without a Spring context. */
    public static Messages standalone() {
        return STANDALONE;
    }

    // Built once: reading the classpath for language files is not free, and
    // the answer cannot change while the process runs.
    private static final Messages STANDALONE = new Messages(source(), languagesWithAFile());

    private static ResourceBundleMessageSource source() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // Without this a language with no file would fall back to the server's
        // own locale, so the answer would depend on where the API happens to run.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static Set<String> languagesWithAFile() {
        Set<String> found = new TreeSet<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:messages_*.properties")) {
                Matcher m = FILE.matcher(String.valueOf(r.getFilename()));
                if (m.matches()) found.add(m.group(1).toLowerCase());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
