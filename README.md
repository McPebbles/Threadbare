# Threadbare

A WebView wrapper for reading Reddit without an account, without the Reddit app,
and without Reddit's telemetry. Fifth app in the suite, after TastyWrap,
SupplyChain, Tombot and Spoticap; same toolchain and architecture.

- Package `com.threadbare.client`, minSdk 30, targetSdk 36
- AGP 8.11.1 / Kotlin 2.1.21 / Gradle 8.14.3
- versionCode 5 / 1.4.0
- No Play services, no Firebase, no analytics, no background service, no push,
  no `@JavascriptInterface`, and no network request the app makes on its own

## What it does

Wraps `www.reddit.com` — the only surface that still serves a logged-out reader,
since Reddit put `old.reddit.com` behind a login in July 2026 — with Reddit's
own mobile layout intact, and removes the app-promotion system that sits on top
of it.

**The "open in app" prompt and the 18+ wall are the same system**, and as of
1.3.0 the app targets what a captured DOM actually contains: a
`<configured-xpromo-modal>` fetched from `/svc/shreddit/partial/…/activate-experience`
after a delay, wrapping an `<rpl-bottom-sheet>` (app promo) or `<rpl-dialog>`
(18+). Both open a native `<dialog>` with `showModal()`, which makes the whole
page inert — that is the "nothing is clickable". The 18+ variant's own "Yes,
I'm Over 18" button sets `over18=true`; the app seeds that exact cookie first.

Three layers, weakest last:

1. **Refuse the request** (`privacy/XpromoBlock.kt`) — `shouldInterceptRequest`
   drops `activate-experience`, so the overlay never arrives. A userscript
   cannot do this; it is the reason this is an app.
2. **A document-start stylesheet** (`assets/xpromo-suppress.css`) — hides the
   family before first paint and forces the scroll lock open.
3. **An observer** (`SiteScripts.XPROMO_SUPPRESSOR`) — closes the shadow-root
   `<dialog>` and removes the host, ending the inertness if anything got through.

The 1.1.0–1.2.0 selectors came from community userscripts and matched nothing
on the real page. See `DESIGN.md` for that and for why a fixture built from
secondary sources proves nothing.

Plus an escape hatch: **Settings > Site version > Desktop**. xpromo is
cross-promotion to the mobile app, so a desktop client should never be offered
it. Reach for this when a future Reddit build outruns the other three.

`DESIGN.md` has the full reasoning, including the old.reddit dead end and why it
happened. Read it before changing anything in `web/` or `privacy/`.

## The app's own chrome

Three things the site cannot give a logged-out reader, so the app does.

**A subreddit box** in the top bar. Type a name, press Go. It is deliberately
**not a URL bar**: the `r/` is a fixed label outside the field, and the field
only ever yields a name that has passed an allowlist of letters, digits and
underscore (plus `+` for multireddits). A host, a scheme or a path all need
characters the allowlist does not contain, so there is no input that navigates
off Reddit. Pasting a whole reddit.com link is supported and works by reading
the subreddit *out* of it — a non-Reddit URL yields nothing.

Whether the community exists is left to Reddit to answer. Checking first would
mean the app making a request of its own, and "no request the app makes on its
own" is worth more than a marginally nicer error.

**Saved communities and posts**, in the overflow. Reddit's own save needs an
account, which is the one thing this app exists to avoid, so the list is local
and never leaves the device. Save/remove appears only where it applies, and says
which way it will go. Posts are keyed by permalink with the slug stripped, so
the same post reached two ways is one bookmark. Titles come from the page title
with Reddit's `: r/sub` suffix removed; rows show the title only and carry the
URL underneath.

**External links leave the app, in a browser you choose** — where "external"
means anything not under `reddit.com`. `Settings > Browser for external links`
lists the installed browsers and is independent of the system default, so
Vanadium can stay the daily driver while links from here go somewhere
disposable. Picking a browser that is **always private** — Firefox Focus, Klar,
Tor Browser — also stops the private-tab prompt appearing at all, since there is
nothing to request and nothing to opt out of. Reddit routes most taps through `applink.reddit.com`, an
app-first redirector, and the app-store bounces through `reddit.onelink.me`
carry the web destination as `deep_link_value`; both are unwrapped and stay in
the app. Ad clicks (`alb.reddit.com`) are refused. A Never / Ask / Always
setting covers private tabs — and one firm rule behind it, below.

## Private tabs, and a thing the app refuses to fake

Most guides say you open an incognito tab with
`com.google.android.apps.chrome.EXTRA_OPEN_NEW_INCOGNITO_TAB`. From an app like
this one **that does nothing**: Chromium's `IntentHandler.isAllowedIncognitoIntent()`
limits it to Chrome's own intents and to validated Custom Tabs. Chrome, Brave
and — most importantly here — **Vanadium** all inherit that. The link opens in an
ordinary tab.

So sending it would be worse than having no feature: the user reads as though
they are protected when they are not. The app therefore:

- uses `private_browsing_mode`, which **Firefox for Android honours from
  third-party apps** since v112 (Bugzilla 1807531, RESOLVED FIXED), and which
  its forks — Mull, IronFox — inherit; Tor Browser needs nothing, being private
  throughout;
- records Chromium packages as *known incapable* rather than merely omitting
  them, so a future edit cannot quietly add one;
- **never falls back from private to normal silently.** `openPrivately` returns
  false rather than downgrading, and the caller says what happened;
- offers **Copy link** as the escape hatch that always works, marked
  `IS_SENSITIVE` so Android 13+ does not put the URL in its clipboard preview —
  though with a browser picker there is much less reason to reach for it.

*Ephemeral Custom Tabs* (`androidx.browser.customtabs.extra.ENABLE_EPHEMERAL_BROWSING`)
would cover Chromium and are open to any app. They are not used because androidx
offers **no way to ask whether the browser supports it** — a browser that ignores
the extra renders a tab identical to one that honours it, which is the exact
silent failure this section exists to prevent.

## Anonymity

Logged-out by default, sign-in permitted. Ephemeral session (cookies, storage
and cache cleared on exit) is the default and is what makes signing in a
deliberate choice rather than an accident. The default user agent is the
device's own, untouched — the least distinctive thing to send. Third-party
cookies off, geolocation and every permission request denied unconditionally,
`DNT` and `Sec-GPC` sent, Safe Browsing and WebView metrics disabled at the
manifest, Reddit's telemetry endpoints blocked.

**Quarantined communities are out of reach** and always will be: a quarantine is
not the adult flag, and lifting one requires a signed-in account with a verified
email. Don't read a quarantine refusal as the 18+ fix having failed.

## Upgrading from an earlier build — read this

The first build wrapped `old.reddit.com`. The second moved to `www.reddit.com`
but **left `SCHEMA_VERSION` at 1 — and left the version at 1.0.0 as well**. So on
a device that had already run the first build, the stored `start_page` still
pointed at old.reddit, `materialiseDefaults` returned early without touching it,
and the app opened on Reddit's "log in to use old Reddit" wall. Fresh installs
were fine, which is what let it through; and because the version string never
moved, nothing on the device hinted that anything had changed.

**1.1.0 (versionCode 2)** fixes it three ways:

1. `SCHEMA_VERSION` is 2 and there is a real migration that rehomes a stored
   start page onto www and drops the retired keys.
2. `Prefs.startPage()` canonicalises on read, so a stale value is harmless even
   if the migration never ran.
3. `MainActivity.loadInApp()` canonicalises every load. Links and intents always
   went through `UrlRules`; the app's own loads did not, which is the actual
   hole. Now nothing reaches the WebView unnormalised.

The version is also finally bumped, so `Settings > Apps` can distinguish the
builds — a second thing that should have happened when the surface changed.

`tools/verify_resources.py` now fails the build on a non-canonical Reddit URL in
`Prefs.kt`, `arrays.xml` or `preferences.xml`, and on a ListPreference whose
default is not among its own entryValues. Negative-tested against the exact
1.1.0 tree: three failures.

**If you are still seeing the login wall:** rebuild from this zip
(`./gradlew assembleDebug`) and reinstall — the migration runs on the new
build's first launch. Clearing the app's data is a faster way to confirm the
diagnosis: if a data wipe fixes it on the *old* build, the stored preference was
the cause.

## Building

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Release signing reads `keystore.properties` in the repo root, or
`KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` from the
environment. CI lives in `workflows/` and needs moving to `.github/workflows/`.

## Verification, and what it is worth

**This code has never been compiled.** No Kotlin compiler and no Android SDK
were reachable from the environment it was written in, and the device bridge
shell to the Windows box is still down from the September 8 update. Run
everything with `tools/verify_all.sh`.

| Check | What it actually proves |
|---|---|
| `tools/verify_kotlin.py` | 27 files: delimiter balance through raw strings, backtick identifiers and nested comments; package/directory agreement; imports resolve. Not a type checker. |
| `tools/verify_resources.py` | Every `@ref` and `R.x` resolves, manifest classes exist, preference keys and defaults agree with `Prefs.kt`, array parity, no leftover template identifiers. Negative-tested against 6 injected faults. |
| `tools/verify_frame.py` | Every `<activity>` satisfies all four requirements of `claude/frame-rule.md`. Validated by running it against a deliberately broken copy reproducing Tombot's settings-screen bug: 6 failures naming the exact causes. |
| `tools/rules_suite.py` | 73 invariants over the routing, filtering and bundle-blocking tables **parsed out of the Kotlin**, so the data under test is the data that ships. Weighted towards what must *not* be blocked. |
| `tools/js_suite.py` | 49 behaviour tests in a real Chromium at 412px. Eleven run against markup **captured from a real Reddit page**, with stub components that reproduce `showModal()` inertness; the suite first proves the fixture blocks the page, then that the suppressor makes it clickable again. |
| `app/src/test/` | JUnit: UrlRules, Blocklist, CookieSeed, SiteScripts, XpromoBlock, PrefsMigration, RedditPath, SavedCodec, PrivateTabs, BrowserChoice. **Never executed** — the first real check to run. |

The browser suite proves, specifically: every member of the xpromo family is
removed including the one inside a shadow root; a page that arrives
scroll-locked actually scrolls; the content behind the wall is unblurred; an
overlay injected *after* load by a route change is caught; and — the assertion
that matters most — ordinary posts, header, search and code blocks all survive.

Six real bugs. Five were found by these checks; **the sixth was found by the
user on device, and is the one worth dwelling on** — none of the tooling looked
at what an upgrade does to stored state, so a suite that was entirely green
shipped an app that opened on a login wall. There is now a test for it.

1. The suppressor's predecessor bailed silently at document-start when
   `document.documentElement` did not exist yet, then threw.
2. A `.thing { display: flow-root }` rule sat after `.promotedlink { display:
   none }` at equal specificity and un-hid every sponsored post.
3. The Kotlin tokenizer read the apostrophe in a backtick test name as a char
   literal and swallowed sixty lines of braces.
4. **A vacuous assertion.** The shadow-root test passed against a page where no
   shadow root had ever been created, because the suppressor removed the static
   host before the fixture's own script could attach one. It now builds the host
   on demand and asserts it exists before asserting it is gone.
5. A test that read as a failure when the suppressor was simply faster than it —
   the rAF fired between creating the probe and asserting it existed.
6. **Found on device, not here:** changing a default without bumping
   `SCHEMA_VERSION`, so upgraded devices kept the old.reddit start page. The
   lesson generalises past this app: *a green suite that only ever sees a fresh
   install says nothing about the upgrade path.*
7. **Found on device, not here:** every overlay selector was wrong. They came
   from community userscripts; the real page had none of them. The fixture was
   built from the same sources, so the suite was green. *A fixture from
   secondary sources proves the code matches the sources, not the site.*
8. **Found on device:** saved-item rows did not respond to taps — a focusable
   remove button inside a ListView row swallows the row's click.
9. **Found on device:** the "no private browser" prompt offered no way to
   continue — AlertDialog silently drops `setItems` when `setMessage` is set.

**What none of it proves:** that the app compiles; how it renders in Android's
WebView inside the frame; and, most importantly, **that the fixture still
matches Reddit**. The element names came from userscripts updated monthly. This
suite will keep passing long after the selectors stop working on device. Only
the device can tell you that.

## First things to check on device, in order

0. That it opens on www at all. If you see "log in to use old Reddit", you are
   running a build from before 1.1.0 — or the app's stored data predates it and
   needs the new build's first launch to migrate.
1. Scroll a listing for several minutes. Nothing should ever offer the app.
2. Open an adult-flagged privacy or security subreddit. No wall, and the content
   behind it not blurred.
3. If either fails, turn on **Settings > Site version > Desktop** and retry.
   That isolates whether the problem is the mobile surface or something wider.
4. Turn off **Block the prompt code from loading** and see whether a broken part
   of the site comes back — that is the one setting that could break things
   invisibly.
5. The settings screen: does the list start below the orange bar and stay there?
   This is the screen the suite gets wrong.
6. Sign in, close the app, reopen: signed out unless "keep session" was accepted.
7. The subreddit box: type `GrapheneOS`, press Go. Then try to break it — type
   `example.com`, paste a non-Reddit URL. Neither should navigate anywhere.
8. Save a community and a post, reopen them from the overflow, remove one.
9. Tap an external link. With Ask, the private option should name a browser if
   you have Firefox or a fork installed, and explain itself if you only have
   Vanadium.
10. `Settings > Browser for external links`: the list should be browsers only —
    not every app that claims some https domain — and Focus should be marked
    "always private". Pick it, then tap an external link: it should open in
    Focus with no prompt at all.
11. Uninstall the browser you picked, then tap an external link: you should be
    told it is gone and get the system default, not silence.

## When it breaks

It will; the selectors churn monthly. The order is in `DESIGN.md` under "What
will break, and what to do about it" — the short version is: desktop mode first
to stay usable, then re-derive the element names from the community userscripts,
then **update `tools/fixture/` and watch the suite fail before fixing anything**.
A fixture updated after the fix proves nothing.

## Layout

```
app/src/main/java/com/threadbare/client/
  MainActivity.kt        the host, back handling, session lifecycle
  privacy/               BlockMode, Blocklist, XpromoBlock   (android-free)
  ui/                    SavedDialog
  shell/                 Frame, TopBar
  ui/                    SettingsActivity, SettingsFragment
  util/                  Prefs, Safely, Sites, Urls
  util/                  SavedItems (codec, android-free), SavedStore
  web/                   UrlRules, CookieSeed, SiteScripts, RedditPath,
                         PrivateTabs                         (android-free)
                         WebViewSetup, AppWebViewClient, AppChromeClient,
                         BrowserLauncher, PrivacySignals, ErrorPage, WebHost
app/src/main/assets/xpromo-suppress.css
app/src/test/            JUnit suites
tools/                   the verification suite above
```
