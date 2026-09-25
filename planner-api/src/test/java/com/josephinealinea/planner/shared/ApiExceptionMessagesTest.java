package com.josephinealinea.planner.shared;

import com.josephinealinea.planner.i18n.I18nConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiExceptionMessagesTest {

    @RestController
    static class Boom {
        @GetMapping("/bad")
        String bad() { throw ApiException.badRequest("test.error", "O'Brien"); }

        @GetMapping("/taken")
        String taken() { throw ApiException.conflict("email_taken", "test.error", "x"); }

        @GetMapping("/crash")
        String crash() { throw new IllegalStateException("boom"); }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Boom())
                .setControllerAdvice(new GlobalExceptionHandler(I18nConfig.standalone()))
                .build();
    }

    @Test
    void theMessageIsResolvedInTheRequestLanguageAndTheCodeIsUntouched() throws Exception {
        mvc.perform(get("/bad").header("Accept-Language", "xx"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.detail").value("[xx] Something specific: O'Brien"));
    }

    @Test
    void englishIsTheDefault() throws Exception {
        mvc.perform(get("/bad"))
                .andExpect(jsonPath("$.detail").value("Something specific: O'Brien"));
    }

    @Test
    void anUnsupportedLanguageGetsEnglishNotAnError() throws Exception {
        mvc.perform(get("/bad").header("Accept-Language", "fr-CA"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Something specific: O'Brien"));
    }

    @Test
    void aConflictKeepsItsOwnCode() throws Exception {
        mvc.perform(get("/taken").header("Accept-Language", "xx"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"))
                .andExpect(jsonPath("$.detail").value("[xx] Something specific: x"));
    }

    @Test
    void theUnexpectedErrorIsTranslatedToo() throws Exception {
        mvc.perform(get("/crash").header("Accept-Language", "xx"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("[xx] Something went wrong on our side."));
    }
}
