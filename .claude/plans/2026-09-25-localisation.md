# Localisation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Every user-visible string lives in one English message file per side (API, web, published page), code refers to keys, and a member can pick a language in Account settings.

**Architecture:** The API resolves its own messages through Spring `MessageSource` (`messages_en.properties`); `ApiException` carries a key plus arguments and `GlobalExceptionHandler` resolves it for the request's locale. The web has its own table (`js/i18n/en.js`) read through `t(key, params)`. The published page inlines its table as `window.I18N`, like `COUNTRIES`. The language is `User.languageCode`; signed out it falls back to `Accept-Language`, then `en`.

**Tech Stack:** Spring Boot 3.5 / Java 21, Alpine.js, plain ES modules.

**Spec:** Agreed in conversation 2026-09-25 (design approved: server resolves API messages, frontend resolves UI text, emails and published pages in scope, English only, `xx` pseudo-locale in tests to prove the switch).

## Global Constraints

- Existing `code` field on error responses stays; the frontend's logic keys off it.
- English is the default and the fallback for any missing key.
- The English files are named as English: `messages_en.properties`, `js/i18n/en.js`, `publish/i18n-en.json`.
- No `xx` locale ships to users; it exists in test resources only.
- On screen a member is a "travel buddy" (keep wording in the message files).
- `db/migration` is schema only (`MigrationsAreSchemaOnlyTest`).
- New setting fields on `User` must be added to `storage/EveryField` and the JDBC mapper.
- Two constructors on a `@Service` need `@Autowired` on the real one.

## Review Focus

- A key used in code but absent from `en` (must fail a test, not show a raw key in production).
- A key in `en` absent from another locale (falls back to English, never blank).
- Message arguments containing `{`, `'` or `%` (MessageFormat treats `'` specially; names like "O'Brien" or a trip titled "50% off").
- Signed-out request with an `Accept-Language` the app does not support (must be `en`, not an error).
- A published page rendered for a member whose language differs from the publisher's (uses the member's).
- Stale `localStorage` language no longer offered (falls back to `en`).

---

## Phase 1: API infrastructure

### Task 1: Message source, locale resolution, `Messages` helper

**Files:**
- Create: `planner-api/src/main/resources/messages_en.properties`
- Create: `planner-api/src/main/java/com/josephinealinea/planner/i18n/Messages.java`
- Create: `planner-api/src/main/java/com/josephinealinea/planner/i18n/I18nConfig.java`
- Create: `planner-api/src/main/java/com/josephinealinea/planner/i18n/RequestLocale.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/i18n/MessagesTest.java`
- Test resource: `planner-api/src/test/resources/messages_xx.properties`

**Interfaces:**
- Produces: `Messages.get(String key, Object... args)` (current request locale), `Messages.get(Locale, String key, Object... args)`, `Messages.supported()` (`Set<String>` of language codes), `RequestLocale.current()`.

- [ ] Write `MessagesTest`: english lookup, `xx` lookup, unknown locale falls back to `en`, missing key throws in test mode, argument containing `'` and `{` is rendered literally.
- [ ] Run it, expect FAIL (classes missing).
- [ ] Implement `Messages` over `ResourceBundleMessageSource` (`basename=messages`, `fallbackToSystemLocale=false`, `useCodeAsDefaultMessage=false`, `alwaysUseMessageFormat=true`). Escape arguments by passing them as arguments only, never concatenating into the pattern.
- [ ] Implement `RequestLocale`: signed-in user's `languageCode` if supported, else `Accept-Language` best supported match, else `en`.
- [ ] Run tests, expect PASS. Commit.

### Task 2: `ApiException` carries a key; handler resolves it

**Files:**
- Modify: `shared/ApiException.java`, `shared/GlobalExceptionHandler.java`
- Test: `shared/ApiExceptionMessagesTest.java`

**Interfaces:**
- Produces: `ApiException.badRequest(String key, Object... args)`, same for `notFound`, `forbidden`, `unauthorized`, `conflict(String code, String key, Object... args)`. `messageKey()` and `args()` accessors. `getMessage()` still returns the key so logs stay greppable.

- [ ] Test: throwing `badRequest("error.email.invalid")` through MockMvc with `Accept-Language: xx` returns the `xx` text and the same `code`.
- [ ] Implement; handler resolves via `Messages`. Validation handler resolves `fe.getDefaultMessage()` as a key (`{key}` placeholders) and the fixed strings ("Some fields need attention", "Something went wrong on our side.") become keys.
- [ ] Run, PASS, commit.

### Task 3: Migrate every API string

**Files:** all 69 `ApiException` call sites, all 29 `message = "..."` annotations, `messages_en.properties`.

- [ ] Guardrail test first (`MessageKeysTest`): scan `src/main/java` for `ApiException.*("` and `message = "` whose first argument is not a key of the form `[a-z]+(\.[a-z0-9]+)+`; fail listing the offenders. Also fail for any key referenced in code and missing from `messages_en.properties`, and for keys in `en` not in the `xx` test file.
- [ ] Run, FAIL listing every offender (this is the work list).
- [ ] Convert module by module (`identity`, `trips`, `destinations`, `checklist`, `itinerary`, `budget`, `publish`, `rates`, `weather`, `geocoding`, `config`), moving text to `messages_en.properties`, grouping keys by module. Keep wording identical so existing tests asserting text still pass or are updated to assert the key.
- [ ] Add every key to `messages_xx.properties` (pseudo: `[xx] <text>`).
- [ ] `./gradlew test`; check the `skipped` count is 0. Commit per module.

## Phase 2: Language on the user

### Task 4: `User.languageCode`

**Files:** `identity/domain/User.java`, `identity/infra/JdbcUserRepository.java`, `db/migration/V12__user_language.sql`, `identity/web/AuthDtos.java`, `identity/web/AccountController.java`, `identity/api/UserService.java`, `storage/EveryField` (test), `trips/api/TripViews.java`.

- [ ] Test: round trip through YAML and JDBC contracts; `PATCH /account/language` with `{"languageCode":"xx"}` stores it, an unsupported code is a 400 (`error.language.unsupported`), and `/auth/me` returns it.
- [ ] Add nullable `language_code text` (null means "follow Accept-Language, then en").
- [ ] `RequestLocale` reads it. Also add `GET /config` `languages` (supported codes with their own names from `language.name.<code>` keys) for the picker.
- [ ] Run, PASS, commit.

## Phase 3: Emails and published pages

### Task 5: Emails in the recipient's language

**Files:** `notification/MailTemplates.java`, `messages_en.properties`, callers of `MailTemplates` (`identity`, `trips`, `publish`).

- [ ] Test: `invitedNewMember` for an `xx` recipient renders the `xx` subject and body; a name containing `'` and `%` survives.
- [ ] Change each template method to take a `Locale` (from the recipient's `User`, `en` when the account has none) and read subject and body from `email.<name>.subject` / `email.<name>.body` keys with `{0}`-style args. The sign-off stays one key.
- [ ] Run, PASS, commit.

### Task 6: Published pages

**Files:** `publish/api/StaticSiteRenderer.java`, `publish/api/PublishOptions.java`, `resources/publish/page.js`, `resources/publish/i18n-en.json` (new), template placeholder `{{i18n}}`.

- [ ] Test (`PublishedPageLanguageTest`): render for a viewer with `languageCode=xx`; the file inlines the `xx` table; a viewer with none gets `en`; personal pages use the viewer's language, not the publisher's.
- [ ] Move every literal in `page.js` (labels, badges "Forecast"/"Typical", weather phrases, filter names, the footer link text) into `i18n-en.json`; `page.js` reads `window.I18N` via a local `t()`. Substitute `{{i18n}}` literally (never `.formatted()`, see CLAUDE.md trap).
- [ ] `npm run check` (countries drift) still passes. Run tests, commit.

## Phase 4: Web

### Task 7: i18n module and table

**Files:**
- Create: `planner-web/js/i18n/index.js`, `planner-web/js/i18n/en.js`
- Modify: `planner-web/js/api.js`, `planner-web/js/boot.js`, `planner-web/js/toast.js`, `planner-web/js/config.js`

**Interfaces:**
- Produces: `t(key, params)`; `setLanguage(code)`; `currentLanguage()`; Alpine magic `$t`; `applyTranslations(root)` for `data-i18n`, `data-i18n-placeholder`, `data-i18n-aria-label`, `data-i18n-title`.

- [ ] Node test (`planner-web/scripts/i18n.test.mjs`, run with `node --test`): `t` interpolates `{name}`, falls back to English then to the key, `xx` table is honoured.
- [ ] Implement. `api.js` sends `Accept-Language` from `currentLanguage()`; the stored choice is `localStorage.plannerLanguage`, validated against the languages the table knows.
- [ ] Add `npm run i18n:check`: every `t('key')` and `data-i18n*="key"` in `js/` and `*.html` exists in `en.js`. Wire into `npm run check`.

### Task 8: Migrate JS strings

**Files:** every file under `planner-web/js/` (~5.5k lines): `toast` calls (75), error text, labels built in JS, `format.js` category names, `weather.js` conditions.

- [ ] `i18n:check` first with a rule that fails on toast/alert/textContent string literals; run, FAIL as work list.
- [ ] Convert file by file; keys grouped `trip.checklist.*`, `trip.budget.*`, `common.*`. Plurals via `key.one` / `key.other` chosen in `t` by `params.count`.
- [ ] Commit per file group.

### Task 9: Migrate HTML

**Files:** `index.html`, `login.html`, `account.html`, `change-password.html`, `trips.html`, `trip.html`, `404.html`.

- [ ] Replace visible text with `data-i18n` attributes (English text stays inside as the pre-JS fallback only if it is generated from `en.js` by `npm run build`; otherwise leave the element empty and let `applyTranslations` fill it, and set `<html lang>` on load).
- [ ] Alpine templates use `x-text="$t('...')"`.
- [ ] Manual check of each page in the browser at 400px and desktop.

### Task 10: The picker

**Files:** `planner-web/account.html` (Appearance section), `js/session.js`.

- [ ] Language `<select>` from `GET /config` languages; on change: `PATCH /account/language`, `setLanguage`, re-apply. Signed out pages use the stored choice.
- [ ] Verify in the browser with a temporary `xx` table: whole trip page changes, an API error toast changes, an email logged by `LoggingEmailSender` changes.

## Phase 5: Documentation

- [ ] Add a "Localisation" section to CLAUDE.md (where messages live, how to add a language, the `xx` guardrails) and a short README note using the one-command-per-`####` format.
