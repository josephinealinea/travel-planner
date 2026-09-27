package com.josephinealinea.planner.news.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.identity.api.CurrentUser;
import com.josephinealinea.planner.news.api.NewsService;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NewsControllerTest {

    private final NewsService service = Mockito.mock(NewsService.class);

    private MockMvc mvc() {
        CurrentUserContext user = new CurrentUserContext();
        user.set(new CurrentUser("u1", "a@b.c", "A", false, null));
        return MockMvcBuilders.standaloneSetup(new NewsController(service, user))
                .setControllerAdvice(new GlobalExceptionHandler(I18nConfig.standalone()))
                .build();
    }

    @Test
    void answersTheGroupsTheServiceReturnsWithADayLongPrivateCache() throws Exception {
        Mockito.when(service.newsFor(eq("trip-1"), any(), any())).thenReturn(List.of(
                new DestinationNewsGroup("Cusco", "PE", "🇵🇪",
                        List.of(new NewsArticle("t", "d", "https://x", null, "s", null)))));

        mvc().perform(get("/api/v1/trips/trip-1/news"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, private"))
                .andExpect(jsonPath("$[0].destinationName").value("Cusco"))
                .andExpect(jsonPath("$[0].articles[0].title").value("t"));
    }

    /** An empty answer is never cached for a day: it could mean a brief outage that has since
     * recovered, and there is no way for the member to force a refresh otherwise. */
    @Test
    void anEmptyResultIsAnEmptyArrayNotAnErrorAndIsNotCachedForADay() throws Exception {
        Mockito.when(service.newsFor(eq("trip-1"), any(), any())).thenReturn(List.of());

        mvc().perform(get("/api/v1/trips/trip-1/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }
}
