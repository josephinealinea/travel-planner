# Budget Currency Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every "record a cost" currency dropdown (Plan form, itinerary entry, expense, and the trip's own Budget-currency field) offers the signed-in user's own configured currency list; the Budget tab's "Show totals in" control offers only currencies the trip's budget actually uses.

**Architecture:** A per-user `currencies` list, seeded at account-creation time from a new `app.currencies.defaults` config value and freely editable afterward from Account — this is the source for the four "record a cost" dropdowns. Separately, a pure frontend getter derives the "Show totals in" options from `budget.items` currencies plus the trip's current display currency — no backend involvement, since that data is already in the loaded trip bundle. These are two independent lists with two independent sources; nothing unifies them, because they answer different questions ("what currency am I recording in" vs "what does this trip already contain").

**Tech Stack:** Spring Boot 3.5 (Java 21), YAML file store, vanilla ES modules + Alpine.js frontend, Dart Sass.

**Spec:** `.claude/briefs/2026-09-12-budget-currency-handling.md`

## Global Constraints

- Rates stay hand-maintained per trip. No live FX feed — untouched by this plan.
- A currency with no configured rate is still reported in `currenciesMissingRates` and excluded from the total, never counted at 1:1 — untouched by this plan.
- The trip's current `displayCurrency` stays selectable in "Show totals in" even after the last expense in that currency is deleted.
- No live currency-list API call (countries.dev or otherwise) for this feature — the brief's "Open question" asked whether to derive the list from destination countries via `CountryCatalog`; the user's answer supersedes that with a per-user config list instead, so `CountryCatalog` is untouched by this plan.
- `feature-enable-database` stays off; the new `currencies` field on `User` is a YAML-stored field like every other, with no JPA path.
- There is no frontend test runner in this project (CLAUDE.md). Frontend verification is browser-based with exact expected values.

## Decisions made while planning (stated so a reviewer can correct them)

**Which of the 5 currency dropdowns use which list.** Read every occurrence in
`trip.html` before writing this plan; `x-model="editForm.displayCurrency"` (the
Edit Trip modal's own "Budget currency" field) and the Budget tab's
`setDisplayCurrency(...)` "Show totals in" select both ultimately write the
exact same trip field, so at first glance they look like they should share a
list. They do not, on purpose:

| Dropdown | List | Why |
|---|---|---|
| Checklist drawer's Plan form currency | user's `currencies` | recording a cost |
| Itinerary entry currency | user's `currencies` | recording a cost |
| Expense currency | user's `currencies` | recording a cost — the one the brief names explicitly |
| Edit Trip modal's "Budget currency" | user's `currencies` | choosing what currency to track the *whole trip* in should not be limited to currencies already spent — a trip's currency is often set before any expense exists |
| Budget tab's "Show totals in" | trip's own data | the brief's explicit rule: "if only EUR and USD were added, only display these" |

Setting `displayCurrency` through Edit Trip to a currency not yet in
`budget.items` still shows up in "Show totals in" immediately afterward,
because that list is `{item currencies} ∪ {current displayCurrency}` — the
existing guardrail already covers this, so the two controls never disagree.

**`toMe()` is deduplicated.** `AuthController` and `AccountController` each
carry an identical private `toMe(User)` helper. Every future field added to
`MeResponse` — this one included — would otherwise need updating in both
places with no compiler check that they stay in sync. Task 1 replaces both
with a static factory on the record itself, `MeResponse.from(user)`. This is
a one-line-risk refactor bundled into the task that already touches both
files and the record they share; it is not scope creep on its own.

**Validation is a plain regex, not a real ISO-4217 whitelist.** Nothing else
in this codebase validates currency codes against a real list — `BudgetService`
just uppercases whatever string it is given. Matching that looseness, entries
are checked as `[A-Za-z]{3}` and nothing more.

## Ruling required before Task 1 can compile

**`AppProperties` is a record with two existing positional constructor call
sites in tests.** `BudgetSyncTest.java:33` and `StaticSiteRendererTest.java:51`
each call `new AppProperties(storage, publish, mail, security, cors,
geocoding, bootstrap)` with exactly 7 positional arguments. Adding an 8th
field to the record without updating both call sites does not fail those two
tests — it fails the whole module to *compile*, taking every other test down
with it. Task 1 Step 7 updates both call sites; do not skip it, and do not
discover it by running the suite and being surprised — it is listed here
because a plan that let a reviewer find this by accident would not have earned
its self-review.

## File Structure

| File | Responsibility |
|---|---|
| `planner-api/.../config/AppProperties.java` | new `Currencies(List<String> defaults)` nested record |
| `planner-api/.../resources/application.yml` | the config value itself |
| `planner-api/.../identity/domain/User.java` | new `currencies` field |
| `planner-api/.../identity/api/UserService.java` | seeds it on creation, exposes `updateCurrencies` |
| `planner-api/.../config/BootstrapOwner.java` | seeds it for the first account too |
| `planner-api/.../identity/web/AuthDtos.java` | `MeResponse` gains the field and a shared factory |
| `planner-api/.../identity/web/AuthController.java` | uses the shared factory |
| `planner-api/.../identity/web/AccountController.java` | uses the shared factory; new `PATCH /account/currencies` |
| two existing test files | updated `AppProperties` construction (see ruling above) |
| new test file | the seeding + validation behaviour |
| `planner-web/js/api.js` | new `updateCurrencies` call |
| `planner-web/js/format.js` | `CURRENCIES` constant removed |
| `planner-web/js/pages/trip.js` | `entryCurrencies` populated from the signed-in user |
| `planner-web/trip.html` | 4 of 5 dropdowns rewired to `entryCurrencies` |
| `planner-web/account.html` | new "Currencies" card |
| `planner-web/scss/components/_badge.scss` | small `.chip-remove` addition |
| `planner-web/js/pages/trip/budget.js` | new `budgetCurrencies` getter |
| `planner-web/trip.html` (again) | the 1 remaining dropdown rewired to `budgetCurrencies` |

---

### Task 1: Backend — per-user currency list

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/config/AppProperties.java`
- Modify: `planner-api/src/main/resources/application.yml`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/identity/domain/User.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/identity/api/UserService.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/config/BootstrapOwner.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/identity/web/AuthDtos.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/identity/web/AuthController.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/identity/web/AccountController.java`
- Modify: `planner-api/src/test/java/com/josephinealinea/planner/itinerary/BudgetSyncTest.java`
- Modify: `planner-api/src/test/java/com/josephinealinea/planner/publish/StaticSiteRendererTest.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/identity/UserServiceCurrenciesTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces, for Task 2: `GET /api/v1/auth/me` response gains `currencies: string[]`; `PATCH /api/v1/account/currencies` accepting `{ currencies: string[] }`, returning the same `MeResponse` shape.

- [ ] **Step 1: Add the config record**

In `AppProperties.java`, add `Currencies currencies` to the record's field list (after `bootstrap`):

```java
public record AppProperties(
        Storage storage,
        Publish publish,
        Mail mail,
        Security security,
        Cors cors,
        Geocoding geocoding,
        Bootstrap bootstrap,
        Currencies currencies
) {
```

Add the nested record at the end, alongside `Bootstrap`:

```java
    /** Seeds the very first account, because there is no self-signup. */
    public record Bootstrap(String ownerEmail, String ownerPassword) {}

    /**
     * The currency list a brand-new account starts with. Each user can freely
     * override their own from Account afterward — this only decides what a
     * fresh account is seeded with.
     */
    public record Currencies(List<String> defaults) {
        public Currencies {
            if (defaults == null || defaults.isEmpty()) defaults = List.of("EUR", "USD", "SGD");
        }
    }
}
```

- [ ] **Step 2: Add the config value**

In `application.yml`, add under the existing `app:` block, after `bootstrap`:

```yaml
  bootstrap:
    owner-email: ${BOOTSTRAP_OWNER_EMAIL:}
    owner-password: ${BOOTSTRAP_OWNER_PASSWORD:}
  currencies:
    defaults: [EUR, USD, SGD]
```

- [ ] **Step 3: Verify it compiles and binds**

```bash
cd planner-api && ./gradlew compileJava
```
Expected: `BUILD SUCCESSFUL`. (It will not be — Step 7 fixes the two test call sites. This step only proves the production code and config bind correctly; `compileJava` does not touch the test source set.)

- [ ] **Step 4: Add the field to `User`**

In `User.java`, add the import and field:

```java
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
```

```java
    private String id;
    private String email;
    private String screenName;
    private String passwordHash;
    private boolean mustChangePassword;
    private List<String> currencies = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
```

Add the accessor pair, next to `getScreenName`/`setScreenName`:

```java
    public List<String> getCurrencies() { return currencies; }
    public void setCurrencies(List<String> currencies) {
        this.currencies = currencies == null ? new ArrayList<>() : currencies;
    }
```

- [ ] **Step 5: Seed it on account creation, and add the update path**

In `UserService.java`, add the import, inject `AppProperties`, and seed on creation:

```java
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public UserService(UserRepository users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }
```

In `findOrCreate()`, add one line to the new-user branch:

```java
                .orElseGet(() -> {
                    String password = Ids.defaultPassword();
                    User user = new User();
                    user.setId(Ids.newId());
                    user.setEmail(normalised);
                    user.setScreenName(null);
                    user.setPasswordHash(encoder.encode(password));
                    user.setMustChangePassword(true);
                    user.setCurrencies(new ArrayList<>(props.currencies().defaults()));
                    return new Invited(users.save(user), password, true);
                });
```

Add the update method, after `updateScreenName`:

```java
    /**
     * A user's own working currencies — seeded from app.currencies.defaults
     * when their account is created, fully replaceable after that. This is
     * what the "record a cost" forms offer (a plan, an itinerary entry, an
     * expense, the trip's own display currency); it has nothing to do with
     * which currencies a particular trip's budget happens to contain, which
     * is derived from that trip's own data instead.
     */
    public User updateCurrencies(String userId, List<String> currencies) {
        User user = require(userId);
        if (currencies == null || currencies.isEmpty()) {
            throw ApiException.badRequest("Choose at least one currency.");
        }
        if (currencies.size() > 20) {
            throw ApiException.badRequest("That is too many currencies — choose 20 or fewer.");
        }
        List<String> normalised = new ArrayList<>();
        for (String code : currencies) {
            if (code == null || !code.trim().matches("[A-Za-z]{3}")) {
                throw ApiException.badRequest("\"" + code + "\" does not look like a currency code.");
            }
            String upper = code.trim().toUpperCase();
            if (!normalised.contains(upper)) normalised.add(upper);
        }
        user.setCurrencies(normalised);
        return users.save(user);
    }
```

- [ ] **Step 6: Seed the bootstrap owner the same way**

In `BootstrapOwner.java`, add the import and one line:

```java
import java.util.ArrayList;
```

```java
            String plaintext = (password == null || password.isBlank()) ? Ids.defaultPassword() : password;
            User owner = new User();
            owner.setId(Ids.newId());
            owner.setEmail(normalised);
            owner.setPasswordHash(encoder.encode(plaintext));
            owner.setCurrencies(new ArrayList<>(props.currencies().defaults()));
            // A configured password is deliberate, so do not force a change;
            // a generated one must be replaced on first sign-in.
            owner.setMustChangePassword(password == null || password.isBlank());
            users.save(owner);
```

- [ ] **Step 7: Fix the two test call sites the new record field breaks**

In `BudgetSyncTest.java`, find the `AppProperties` construction (around line 33) and add an 8th argument:

```java
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null));
```

In `StaticSiteRendererTest.java`, find the `AppProperties` construction (around line 51) and add the identical 8th argument:

```java
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(publishDir.toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null));
```

(Both pass `null`, exercising the record's own empty-list fallback to `["EUR","USD","SGD"]" — deliberately, so the compact constructor's defaulting is exercised by the existing suite too, not just by the new test.)

- [ ] **Step 8: Deduplicate `toMe()` onto the record itself**

In `AuthDtos.java`, add the import and extend `MeResponse`:

```java
import com.josephinealinea.planner.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
```

```java
    public record MeResponse(
            String id,
            String email,
            String screenName,
            String displayName,
            boolean mustChangePassword,
            List<String> currencies) {

        public static MeResponse from(User user) {
            return new MeResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getScreenName(),
                    user.displayName(),
                    user.isMustChangePassword(),
                    user.getCurrencies());
        }
    }
```

Add the new request DTO at the end of the file, before the closing brace:

```java
    public record UpdateCurrenciesRequest(List<String> currencies) {}
```

- [ ] **Step 9: Use the shared factory in both controllers**

In `AuthController.java`, replace every `toMe(x)` call with `AuthDtos.MeResponse.from(x)` and delete the private `toMe` method entirely:

```java
    @PostMapping("/login")
    AuthDtos.MeResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                              HttpServletResponse response) {
        User user = auth.authenticate(request.email(), request.password());
        cookies.setSession(response, jwt.issue(user.getId()), jwt.ttl().toSeconds());
        return AuthDtos.MeResponse.from(user);
    }
```

```java
    @GetMapping("/me")
    AuthDtos.MeResponse me() {
        return AuthDtos.MeResponse.from(userService.require(currentUser.userId()));
    }
```

Delete:
```java
    private AuthDtos.MeResponse toMe(User user) {
        return new AuthDtos.MeResponse(
                user.getId(),
                user.getEmail(),
                user.getScreenName(),
                user.displayName(),
                user.isMustChangePassword());
    }
```

In `AccountController.java`, same replacement, plus the new endpoint. Full new file body:

```java
package com.josephinealinea.planner.identity.web;

import com.josephinealinea.planner.config.AuthCookies;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.config.JwtService;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final UserService users;
    private final CurrentUserContext currentUser;
    private final JwtService jwt;
    private final AuthCookies cookies;

    public AccountController(UserService users,
                             CurrentUserContext currentUser,
                             JwtService jwt,
                             AuthCookies cookies) {
        this.users = users;
        this.currentUser = currentUser;
        this.jwt = jwt;
        this.cookies = cookies;
    }

    @PatchMapping("/profile")
    AuthDtos.MeResponse updateProfile(@RequestBody AuthDtos.ProfileRequest request) {
        return AuthDtos.MeResponse.from(users.updateScreenName(currentUser.userId(), request.screenName()));
    }

    /**
     * Also the exit from the forced-change gate, and it takes an optional screen
     * name so the first-run screen can set both in one request. A fresh cookie
     * goes out so the session clock restarts from the new password.
     */
    @PostMapping("/password")
    AuthDtos.MeResponse changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
                                       HttpServletResponse response) {
        User updated = users.changePassword(
                currentUser.userId(), request.currentPassword(), request.newPassword(), request.screenName());
        cookies.setSession(response, jwt.issue(updated.getId()), jwt.ttl().toSeconds());
        return AuthDtos.MeResponse.from(updated);
    }

    /**
     * Replaces the caller's own currency list wholesale — the same shape as
     * updateProfile, not a partial add/remove. This is what the "record a
     * cost" forms offer; it has no bearing on a trip's own "Show totals in"
     * list, which is derived from that trip's data instead.
     */
    @PatchMapping("/currencies")
    AuthDtos.MeResponse updateCurrencies(@RequestBody AuthDtos.UpdateCurrenciesRequest request) {
        return AuthDtos.MeResponse.from(users.updateCurrencies(currentUser.userId(), request.currencies()));
    }
}
```

- [ ] **Step 10: Write the failing test**

Create `planner-api/src/test/java/com/josephinealinea/planner/identity/UserServiceCurrenciesTest.java`:

```java
package com.josephinealinea.planner.identity;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A brand-new user is seeded with app.currencies.defaults; after that the
 * list is theirs to replace, independent of the trip they happen to be on.
 */
class UserServiceCurrenciesTest {

    private UserService users;
    private YamlUserRepository repository;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(null, null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(List.of("EUR", "USD", "SGD")));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        repository = new YamlUserRepository(store, paths, new TripLocks());
        users = new UserService(repository, new BCryptPasswordEncoder(), props);
    }

    @Test
    void aNewUserIsSeededWithTheConfiguredDefaults() {
        var invited = users.findOrCreate("sam@example.com");

        assertThat(invited.created()).isTrue();
        assertThat(invited.user().getCurrencies()).containsExactly("EUR", "USD", "SGD");
    }

    @Test
    void anExistingUserIsNotReSeeded() {
        users.findOrCreate("sam@example.com");
        users.updateCurrencies(
                repository.findByEmail("sam@example.com").orElseThrow().getId(), List.of("GBP"));

        var invited = users.findOrCreate("sam@example.com");

        assertThat(invited.created()).isFalse();
        assertThat(invited.user().getCurrencies()).containsExactly("GBP");
    }

    @Test
    void updateCurrenciesReplacesUppercasesAndDedupes() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        var updated = users.updateCurrencies(id, List.of("gbp", "GBP", "chf"));

        assertThat(updated.getCurrencies()).containsExactly("GBP", "CHF");
        assertThat(repository.findById(id).orElseThrow().getCurrencies())
                .containsExactly("GBP", "CHF");
    }

    @Test
    void updateCurrenciesRejectsAnEmptyList() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        assertThatThrownBy(() -> users.updateCurrencies(id, List.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void updateCurrenciesRejectsAMalformedCode() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        assertThatThrownBy(() -> users.updateCurrencies(id, List.of("EU")))
                .isInstanceOf(ApiException.class);
    }
}
```

- [ ] **Step 11: Run it to make sure it fails first, then implement, then pass**

```bash
cd planner-api && ./gradlew test --tests 'UserServiceCurrenciesTest'
```
Expected on the very first run, before Steps 1–9 exist: a compile error (the
production classes/methods it calls do not exist yet). Once Steps 1–9 are in
place: `BUILD SUCCESSFUL`, 5 tests passing.

- [ ] **Step 12: Run the full suite**

```bash
cd planner-api && ./gradlew test
```
Expected: `BUILD SUCCESSFUL`. This is what proves Step 7's fix actually
unblocked `BudgetSyncTest` and `StaticSiteRendererTest` — both must still pass
unchanged, since neither test's behaviour depends on currencies.

- [ ] **Step 13: Manually confirm the API surface**

Start the API fresh so a real new account gets seeded:

```bash
cd planner-api && rm -rf data && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```

In another terminal, sign in and check `/auth/me`:
```bash
curl -s -c /tmp/j -X POST http://localhost:8080/api/v1/auth/csrf
CSRF=$(grep XSRF-TOKEN /tmp/j | awk '{print $7}')
curl -s -b /tmp/j -c /tmp/j -X POST -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"email":"you@example.com","password":"password123"}' http://localhost:8080/api/v1/auth/login
```
Expected: the JSON response includes `"currencies":["EUR","USD","SGD"]`.

```bash
CSRF=$(grep XSRF-TOKEN /tmp/j | awk '{print $7}')
curl -s -b /tmp/j -c /tmp/j -X PATCH -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"currencies":["gbp","chf","chf"]}' http://localhost:8080/api/v1/account/currencies
```
Expected: `"currencies":["GBP","CHF"]` — uppercased, deduped.

- [ ] **Step 14: Commit**

Not run by you — the project's git guardrail blocks commits from an
assistant session. Leave the changes staged/unstaged for the user to commit.

---

### Task 2: Frontend — the four "record a cost" dropdowns, and the Account UI to edit them

**Files:**
- Modify: `planner-web/js/api.js`
- Modify: `planner-web/js/format.js`
- Modify: `planner-web/js/pages/trip.js`
- Modify: `planner-web/trip.html`
- Modify: `planner-web/account.html`
- Modify: `planner-web/scss/components/_badge.scss`

**Interfaces:**
- Consumes: `GET /auth/me` now returns `currencies`; `PATCH /account/currencies` from Task 1.
- Produces: the `entryCurrencies` state property on the trip page's Alpine component, which Task 3 does not touch (Task 3 adds a separate `budgetCurrencies` getter, not this one).

- [ ] **Step 1: Add the API call**

In `js/api.js`, add one line after `updateProfile`:

```js
  updateProfile: (data) => patch('/api/v1/account/profile', data),
  updateCurrencies: (data) => patch('/api/v1/account/currencies', data),
  changePassword: (data) => post('/api/v1/account/password', data),
```

- [ ] **Step 2: Remove the hardcoded list**

In `js/format.js`, delete the last two lines of the file:

```js
/** Currencies offered in the pickers. Any code the API accepts still works. */
export const CURRENCIES = ['EUR', 'USD', 'GBP', 'PEN', 'BOB', 'BRL', 'SGD', 'CHF', 'JPY', 'AUD', 'CAD'];
```

- [ ] **Step 3: Populate `entryCurrencies` from the signed-in user**

In `js/pages/trip.js`, change the import and add the new import:

```js
import { api } from '../api.js';
import { queryParam } from '../chrome.js';
import { dateRange, CATEGORIES } from '../format.js';
import { toast } from '../toast.js';
import { currentUser } from '../session.js';
```

Change the state property (was `currencies: CURRENCIES,`):

```js
  const base = {
    api,
    tabs: TABS,
    categories: CATEGORIES,
    entryCurrencies: ['EUR'],
```

In `init()`, fetch the cached user (this is a cache hit, not a new network
call — `bootPage()` already resolved and cached it before this component
exists) and set the list before the tab is resolved:

```js
    async init() {
      if (!this.tripId) {
        this.error = 'No trip was specified.';
        this.loading = false;
        return;
      }

      const user = await currentUser();
      if (user?.currencies?.length) this.entryCurrencies = user.currencies;

      this.tab = this.tabFromHash();
      window.addEventListener('hashchange', () => { this.tab = this.tabFromHash(); });

      await this.reload();
      this.loading = false;
    },
```

- [ ] **Step 4: Rewire 4 of the 5 dropdowns**

In `trip.html`, there are five `<template x-for="code in currencies" ...>`
blocks. Four of them switch to `entryCurrencies`; the fifth (Budget's "Show
totals in") is left as `currencies` for now — Task 3 both renames and rewires
it, so touching it here would just be undone.

Identify each by its surrounding `<select>` id or context, not by line number
(the file has shifted under prior edits):

1. The Checklist drawer's Plan form — `<select id="plan-currency" ...>`
2. The Edit Trip modal — `<select id="edit-currency" ...>` (labelled "Budget currency")
3. The itinerary entry modal — `<select id="entry-currency" ...>`
4. The expense modal — `<select id="expense-currency" ...>`

In each of these four, and **only** these four, change:
```html
                <template x-for="code in currencies" :key="code">
```
to:
```html
                <template x-for="code in entryCurrencies" :key="code">
```
(indentation varies per block — match what is already there; do not
reformat the surrounding markup.)

Leave the block whose `<select>` has `:value="budget.displayCurrency"
@change="setDisplayCurrency($event.target.value)"` — that is "Show totals
in," Task 3's.

- [ ] **Step 5: Verify the rewire**

```bash
cd planner-web
grep -c 'x-for="code in entryCurrencies"' trip.html
```
Expected: `4`.
```bash
grep -c 'x-for="code in currencies"' trip.html
```
Expected: `1` (the one Task 3 will rename).

- [ ] **Step 6: Add the removable-chip style**

In `scss/components/_badge.scss`, add after the `.chip-group` rule:

```scss
.chip-group { display: flex; gap: t.$space-1; flex-wrap: wrap; }

.chip-remove {
  padding: 0;
  margin-left: 2px;
  font-size: 0.85em;
  line-height: 1;
  color: inherit;
  background: none;
  border: 0;
  cursor: pointer;
}

.chip-remove:hover { color: var(--tp-danger); }
```

- [ ] **Step 7: Add the Currencies card to Account**

In `account.html`, add a new `<section class="card">` immediately after the
closing `</section>` of "Appearance" and before the closing `</div>` of
`.stack-lg`:

```html
    <section class="card">
      <div class="card-header"><h2 class="card-title">Currencies</h2></div>
      <p class="small muted">
        Offered when you record a cost — a plan, an itinerary entry, an expense,
        or a trip's own budget currency.
      </p>
      <div id="currency-alert" class="alert" hidden></div>

      <div id="currency-chips" class="chip-group" style="margin-bottom:12px"></div>

      <form id="currency-form" class="field-inline">
        <div class="field grow">
          <label for="new-currency">Add a currency</label>
          <input id="new-currency" maxlength="3" placeholder="GBP" style="text-transform:uppercase">
        </div>
        <div class="field flex-none" style="align-self:flex-start;padding-top:22px">
          <button type="submit" class="btn">Add</button>
        </div>
      </form>

      <button type="button" class="btn btn-primary" id="save-currencies">Save currencies</button>
    </section>
```

- [ ] **Step 8: Wire it up**

In `account.html`'s module script, add to the imports:

```js
  import { PANEL_MODES, savedPanelMode, applyPanelMode } from './js/panel-mode.js';
```
stays as-is; add nothing new here (no new import needed — `api`, `escapeHtml`,
`toast`, `clearCachedUser` are already imported).

Append at the very end of the script, after the panel-mode block:

```js
  // ── currencies ────────────────────────────────────
  let currencyDraft = [...(user.currencies || [])];

  function renderCurrencyChips() {
    const box = document.querySelector('#currency-chips');
    box.innerHTML = currencyDraft.length
      ? currencyDraft.map((code) => `
          <span class="chip">
            ${escapeHtml(code)}
            <button type="button" class="chip-remove" data-code="${escapeHtml(code)}"
                    aria-label="Remove ${escapeHtml(code)}">✕</button>
          </span>`).join('')
      : '<span class="small muted">No currencies yet — add one below.</span>';
  }
  renderCurrencyChips();

  document.querySelector('#currency-chips').addEventListener('click', (event) => {
    const code = event.target.closest('.chip-remove')?.dataset.code;
    if (!code) return;
    currencyDraft = currencyDraft.filter((c) => c !== code);
    renderCurrencyChips();
  });

  document.querySelector('#currency-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const input = document.querySelector('#new-currency');
    const code = input.value.trim().toUpperCase();
    if (/^[A-Z]{3}$/.test(code) && !currencyDraft.includes(code)) {
      currencyDraft.push(code);
      renderCurrencyChips();
    }
    input.value = '';
  });

  document.querySelector('#save-currencies').addEventListener('click', async () => {
    const button = document.querySelector('#save-currencies');
    button.disabled = true;
    try {
      await api.updateCurrencies({ currencies: currencyDraft });
      clearCachedUser();
      alertIn('#currency-alert', 'Saved.', 'info');
      toast.success('Currencies updated');
    } catch (error) {
      alertIn('#currency-alert', error.fullMessage, 'error');
    } finally {
      button.disabled = false;
    }
  });
```

- [ ] **Step 9: Rebuild CSS and verify in browser**

```bash
cd planner-web && npm run css
```
Expected: exits 0.

Start both servers if not already running (API from Task 1's Step 13, and
`cd planner-web && ./serve.sh`). Sign in as a **brand-new** member (add one
via an existing trip's Members tab, read their temporary password from
`data/outbox/*.eml`, sign in, set their password) so the account under test
has never had its currencies touched.

1. Open `account.html`. Expected: a "Currencies" card showing chips `EUR`,
   `USD`, `SGD`.
2. Type `GBP` in "Add a currency", click Add. Expected: a fourth chip
   appears. Click Save currencies. Expected: a success toast.
3. Reload the page. Expected: all four chips persist (proves the PATCH and
   the reload both work).
4. Remove the `SGD` chip, save, reload. Expected: three chips remain
   (`EUR`, `USD`, `GBP`).
5. Open a trip, go to Checklist, open any item's Plan form. Expected: the
   Currency select offers exactly `EUR`, `USD`, `GBP` — not the old
   eleven-code list.
6. Same check on: Itinerary → "+ Add itinerary item" → Currency; Budget →
   "+ Add expense" → Currency; the trip's "Edit trip" modal → "Budget
   currency".

- [ ] **Step 10: Confirm nothing else references the removed constant**

```bash
grep -rn "CURRENCIES" planner-web/js planner-web/*.html
```
Expected: no matches (Task 3 introduces `budgetCurrencies`, a different
name, so this stays clean after Task 3 too).

- [ ] **Step 11: Commit**

Not run by you — leave staged for the user, same as Task 1.

---

### Task 3: Frontend — "Show totals in" derives from the trip's own data

**Files:**
- Modify: `planner-web/js/pages/trip/budget.js`
- Modify: `planner-web/trip.html`

**Interfaces:**
- Consumes: `this.budget.items` and `this.budget.displayCurrency`, already
  present in the loaded trip bundle (`TripViews.BudgetView`) — no new backend
  call.
- Produces: nothing later tasks depend on.

- [ ] **Step 1: Add the getter**

In `js/pages/trip/budget.js`, add immediately after the `missingRates`
getter (both are budget-rollup getters, so they read naturally together):

```js
    /** Currencies in use that still have no rate — offered as one-click adds. */
    get missingRates() {
      return this.budget.currenciesMissingRates || [];
    },

    addMissingRate(currency) {
      this.openRates();
      if (!this.ratesDraft.some((row) => row.currency === currency)) {
        this.ratesDraft.push({ currency, rate: '' });
      }
    },

    /**
     * Currencies the "Show totals in" control offers — strictly what this
     * trip's budget already contains, plus the current display currency so
     * the control is never asked to show a value that is not one of its own
     * options. Deliberately independent of the signed-in user's own currency
     * list (entryCurrencies): this answers "what does this trip contain",
     * not "what do I usually work in".
     */
    get budgetCurrencies() {
      const used = new Set(
        this.budget.items.map((item) => item.currency).filter(Boolean));
      if (this.budget.displayCurrency) used.add(this.budget.displayCurrency);
      return [...used].sort();
    },
```

- [ ] **Step 2: Rewire the last dropdown**

In `trip.html`, find the one remaining `x-for="code in currencies"` — the
Budget tab's "Show totals in" `<select :value="budget.displayCurrency"
@change="setDisplayCurrency($event.target.value)">` — and change it to:

```html
            <template x-for="code in budgetCurrencies" :key="code">
```

- [ ] **Step 3: Verify no bare `currencies` reference remains**

```bash
cd planner-web
grep -c 'x-for="code in currencies"' trip.html
```
Expected: `0`.
```bash
grep -c 'x-for="code in entryCurrencies"\|x-for="code in budgetCurrencies"' trip.html
```
Expected: `5` (4 + 1).

- [ ] **Step 4: Verify in browser — the brief's exact worked example**

On a trip whose budget currently has both a EUR and a USD row (any two
existing expenses in those currencies, or add them via "+ Add expense"):

```js
Alpine.$data(document.querySelector('[x-data]')).budgetCurrencies
```
Expected: `["EUR", "USD"]` (both present, nothing else — no `SGD`, `GBP`,
etc. even though those may be in the signed-in user's own `entryCurrencies`).

Change the trip's display currency (via Edit Trip) to a third currency, say
`CHF`, that has no expenses yet. Re-run the same snippet.
Expected: `["CHF", "EUR", "USD"]` — the guardrail: the current display
currency is always present even with zero matching expenses.

Delete every EUR expense from the trip. Re-run the same snippet.
Expected: `EUR` is gone (no longer used), `CHF` and `USD` remain.

- [ ] **Step 5: Confirm the backend is untouched**

```bash
cd "/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner"
git diff planner-api
```
Expected: no output for this task's own changes (Task 1's backend changes
will already be present from earlier in the branch — this checks that Task
3 itself added none).

- [ ] **Step 6: Commit**

Not run by you — leave staged for the user, same as Tasks 1 and 2.

---

## Verification against the brief

The brief's Done means, re-read against what this plan actually builds:

- [ ] With expenses in EUR and USD only, "Show totals in" offers exactly EUR,
      USD, and the trip's current display currency — nothing else
      → Task 3, Step 4.
- [ ] Selecting USD converts each EUR row by the trip's stored rate, and the
      total matches a hand calculation → untouched code path
      (`convertedOf`), unaffected by this plan; re-verify it still works
      once Task 3 lands since the dropdown feeding `setDisplayCurrency` is
      now a different list.
- [ ] The expense-entry currency options derive from trip data, with no
      hardcoded list left in `js/format.js` → **superseded by the user's
      explicit instruction mid-planning**: expense-entry options now derive
      from the signed-in user's own configured list (seeded from
      `app.currencies.defaults`), not from trip data. The hardcoded-list
      removal itself still holds — Task 2, Step 2 and Step 10.
- [ ] A currency with no rate still appears in the warning and is still
      excluded → untouched (`missingRates`, `BudgetService.convert`).
- [ ] `./gradlew test` passes → Task 1, Step 12.
