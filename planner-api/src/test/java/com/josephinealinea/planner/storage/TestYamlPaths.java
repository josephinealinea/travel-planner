package com.josephinealinea.planner.storage;

import com.josephinealinea.planner.config.AppProperties;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** YAML storage paths for a repository test, rooted in a temp directory. */
public final class TestYamlPaths {

    private TestYamlPaths() {}

    /**
     * YAML paths rooted in {@code dir}, with the published pages under
     * {@code dir/published}.
     *
     * Built from a mocked AppProperties rather than its ten-argument
     * constructor, which about twenty older tests call positionally: a new
     * settings group then doesn't have to be threaded through every repository
     * test. (It is also why new settings go in records of their own rather than
     * into AppProperties — see the deployment plan.)
     */
    public static YamlPaths under(Path dir) {
        AppProperties props = mock(AppProperties.class);
        when(props.storage()).thenReturn(new AppProperties.Storage(dir.toString()));
        when(props.publish()).thenReturn(new AppProperties.Publish(dir.resolve("published").toString(), null));
        return new YamlPaths(props);
    }
}
