# Threadbare

A WebView wrapper for reading Reddit without an account, without the Reddit app,
and without Reddit's telemetry. Fifth app in the suite, after TastyWrap,
SupplyChain, Tombot and Spoticap; same toolchain and architecture.

- Package `com.threadbare.client`, minSdk 30, targetSdk 36
- AGP 8.11.1 / Kotlin 2.1.21 / Gradle 8.14.3
- versionCode 16 / 1.9.2
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

### The other 18+ block: the one on the post itself

Clearing the wall is not the whole job. Once a post is *opened*, the adult flag
comes back in a different shape — and this one withholds the post rather than
covering it.

The media and the self-text are each wrapped in `<shreddit-blurred-container>`:
two light-DOM children, `<div slot="blurred">` (a placeholder Reddit blurred
server-side) and `<div slot="revealed">` (the real thing), and a shadow root
that renders one `<slot>` or the other. While `embed-obscured` is set, the
`mode="slot"` variant renders `<slot name="blurred">`, so the real post is in
the document assigned to no slot.

**1.4.0 cleared the container's state; 1.5.0 also renamed the rendered slot.
Both worked against a captured DOM and both left post pages blank on device.**
The captures have no JavaScript in them. On the phone, Reddit's own component
owns this element, its bundle arrives late, and the media inside the revealed
slot is loaded lazily *by the component's reveal path*. Editing the element's
state at document-start wins that race: the component upgrades already-revealed
and never tells the media to load.

**1.6.0 asks instead of reaching.** Once the page has settled it presses the
component's own "View NSFW content" button — the same thing a reader does by
hand, with whatever else that code path does intact. Then it checks that
something is actually laid out. Only if the component never arrives at all does
it edit the DOM, and only if that produces nothing does it roll every edit back,
so the reader gets Reddit's blurred placeholder and its button rather than an
empty frame. A `reason="spoiler"` blur is left alone throughout: that is the
poster's blur, not Reddit's age gate.

This is now **its own setting** — *Show adult content without tapping through* —
separate from the prompt suppression it used to be bundled with, because the two
do different jobs and the only way to get a post page back in 1.4.0 and 1.5.0
was to give up the 18+ suppression as well.

### The last gate: a third-party embed that declines to render

The report from 1.8.1, on the post that had been failing all along:

```
postType: "link"   domain: "hgifs.com"
embeds: shreddit-embed  data-embed-obscured-deferred
        html='<iframe src="https://www.hgifs.com/ifr/…" …>'
iframes: []          buttons: ["View in app"]
```

The reveal had worked. Inside it sat Reddit's embed component holding the
provider's iframe markup **in an attribute**, having rendered a "View in app"
button instead of the iframe. The server sent the content; the client declined
to show a third-party adult embed to a logged-out mobile reader. Nothing to
press — the only button opens an app store.

**1.9.0 makes the iframe from the `src` Reddit itself supplied.** Only the src
is taken, only over https, the iframe goes beside the component rather than
into any tree lit owns, and the component is hidden rather than removed. A
well-behaved embed that renders its own iframe within a grace period is left
alone; the suite checks both, and that nothing is doubled up.

### The feed's 150px strip (1.9.2)

1.9.1 guessed the half-black feed boxes were hatched iframes in the wrong
shape. The feed report said otherwise: `embedsHatched: 0` on every container —
in a feed Reddit renders its own embeds — and the iframe it renders is
**379 × 150** in a 379 × 379 frame:

```
<div class="absolute inset-0 h-full w-full"><div class="relative"> <a class="absolute inset-0" …
iframe: w 379, h 150     placeholder: 480x854
```

The iframe is `position:absolute; height:100%` inside a wrapper with no height
of its own, so `100%` has nothing to resolve against and the iframe falls back
to an iframe's intrinsic 150px. A strip across the top, black below.

1.9.2 finds the iframe's containing block — the nearest positioned ancestor,
across shadow boundaries — and gives it the frame's height (`100%`, then pixels
if the percent had nothing to resolve against), then reshapes the frame to the
media's ratio from the placeholder, within Reddit's own `max-height`. The same
reshaping applies to hatched embeds. Nothing is hatched in a feed; the app only
contributes a size. The suite reproduces the strip with a stub that renders its
iframe exactly as reported, and the report prints per-post frame, iframe and
placeholder sizes.

### The answer: the app was deleting the thing that unlocks the content

Reddit's "Yes, I'm Over 18" button, from the captured markup, does four things,
and only the first is a cookie:

```
<ac-set-cookie name="over18" value="true">
<ac-track san="xpromo|dismiss|bypassable_xpromo_nsfw_bypassable">
<ac-gql-mutate operation="StoreUxtargetingAction"
    variables='{"action":"DISMISS", …"experienceName":"bypassable_xpromo_nsfw_bypassable"}'>
<ac-call method="location.reload" target="window">
```

The mutation is **server-side state against the reader's loid**, and it is what
makes Reddit send an adult-flagged post's media on the next load. Seeding
`over18=true` does not reproduce it — which is why the post frame was the right
size, the gate was revealed, the app refused nothing, and the media still was
not there. It had never been sent, and it never would be, because the app had
been deleting the one control that asks for it.

**1.8.0 presses it.** Same principle as the reveal: press what a reader would
press, and let the site do its own work. The press is capped at two per session
(`sessionStorage`), because Reddit's own handler reloads the page and a server
that kept re-serving the wall would otherwise have the app reloading forever;
after the cap it goes back to removing the wall as before. The removal passes
also refuse to delete any subtree carrying that button before it has been
pressed.

It lives under **Settings > Set Reddit's adult-content cookie**, renamed to
*Confirm you are over 18 automatically*, since that is now what it does.

### What the earlier device reports showed

A diagnostic report from the phone (1.7.0, all settings on, an adult-flagged
post) said this, and it is worth reading before touching any of the above:

```
containersFound: 1
container: mode=slot reason=nsfw gated=false upgraded=true
           renderedSlot=revealed revealedChildTag=shreddit-async-loader
           revealedHeight=411 presses=1 patched=false done=true
mediaFrame: height 411, and inside it:
           <xpromo-nsfw-blocking-container><shreddit-blurred-container …>
asyncLoaders: […, "embed"]
```

Two facts, both settled at last:

1. **`<xpromo-nsfw-blocking-container>` is real and it wraps the post.** It was
   in the suppressor's kill list from 1.1.0, taken from a userscript, and
   `pierceShadow()` removed the host outright. That is what emptied post pages —
   not the reveal, which the same report shows working: pressed once, slot
   swapped to `revealed`, 411px of it. The content guard below is what stops it.
2. **The media had still never arrived.** The revealed slot holds a
   `shreddit-async-loader`, the frame is the right size, and the only things in
   it are the two placeholder images. On a post page the experience partial's
   response carries the post as well as the wall, and 1.7.0 was still refusing
   that request at the network. **1.7.1 allows it on post pages only** — the
   page type is in the URL (`postId` in the signed params on a post; a feed has
   `query=` and no params; a subreddit has `subredditName`), checked against
   three captured pages, with anything unrecognised still refused. The wall on a
   post page falls to the observer instead, which now cannot take content with
   it.

### Removing a prompt must never remove a post

The rule 1.7.0 adds, and the reason for it: three releases running, an
adult-flagged post opened as a **black box with nothing in it**, and the only
setting that changed anything was *Remove app prompts and the 18+ wall* — not
the reveal, not the network blocking. Against the captured page the suppressor
removes nothing at all, so whatever it deletes arrives at runtime, and the one
runtime-delivered thing it deletes is the experience partial's
`<configured-xpromo-modal>`. On a post page that response evidently carries the
post as well as the wall; taking the wrapper takes both, and what is left is
Reddit's `bg-black` media frame — a black box, no content, no button.

So the observer now checks, before deleting anything, whether the post is inside
it (`shreddit-post`, `shreddit-player`, `shreddit-blurred-container`, the media
and text-body slots, comments). If it is, the subtree stays and only the dialog
inside it is closed and removed. Every `display: none` rule in the stylesheet
carries the same guard as `:not(:has(...))`, and a browser without `:has()`
drops the rule, which shows a prompt the observer then removes — the safe
direction to fail in.

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

## Diagnostics, and why they exist

**Settings > Diagnostics > Diagnostic tools**, off by default. It adds two items
to the overflow and opens the WebView to `chrome://inspect` over adb.

- **Why is this blank?** — what the app believes about the adult gate on this
  page: how many blurred containers exist, whether their component ever
  upgraded, whether the button was pressed, what is in the media frame, which
  async-loader bundles are on the page. If it reports no containers and an empty
  frame, the media was never in the page and nothing client-side will conjure it.
- The report describes **the post named in the URL**, not whichever media
  frame happens to be first in the document. A post page carries recommended
  posts too, and an earlier version of this report described one of those — a
  frame belonging to a different post id — which cost a round of diagnosis. It
  now prints the main post's id, `post-type`, `domain` and `content-href`, and
  lists the other posts on the page so the two can never be confused again.
- The report also lists **what the app refused on this page** — the half the
  DOM cannot tell you, and the half that turned out to matter in the end.
- **Save page HTML** — the page as this WebView actually has it, shadow roots
  serialised as `<template shadowrootmode="open">` (which `outerHTML` omits, and
  they are where the whole mechanism lives). Script bodies are dropped and
  `loid`, correlation ids and signed URLs are scrubbed in the page before the
  string reaches Kotlin, because the file exists to be sent to someone.

It is off by default because it sets one global the page could look for, and
because remote debugging is the opposite of what the rest of this app does. It
exists because three fixes in a row were derived from a DOM captured in a
browser, and the app's page kept turning out not to be that page.

`evaluateJavascript` is a callback into Kotlin, not a bridge: the app still has
no `@JavascriptInterface` and the page still cannot call into it.

## Settings that need a restart

Four switches — *Remove app prompts and the 18+ wall*, *Show adult content
without tapping through*, *Block the prompt code from loading* and *Diagnostic
tools* — are read
when the page viewer is built, because that is when the document-start scripts
are attached. Toggling one changes the stored value and nothing on screen, which
has already cost this project a round of confusion: a setting was flipped, the
page did not change, and the conclusion drawn was about Reddit.

So changing one now offers a restart. Decline it and the new value is kept with
the preference marked *saved, but not in effect until the app is restarted*.
Put it back to what it was when the app started and the note goes away, because
at that point the running app already matches the setting — the baseline is the
launch snapshot, not the previous value, so two toggles that cancel out leave
nothing pending.

The restart finishes the task and starts it again rather than killing the
process: ending the process would skip `onDestroy`, which is where an ephemeral
session clears its cookies and storage.

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
| `tools/verify_kotlin.py` | 42 files: delimiter balance through raw strings, backtick identifiers and nested comments; package/directory agreement; imports resolve; `@Volatile`/`lateinit` on a `val`. Not a type checker. |
| `tools/verify_resources.py` | Every `@ref` and `R.x` resolves, manifest classes exist, preference keys and defaults agree with `Prefs.kt`, array parity, no leftover template identifiers. Negative-tested against 6 injected faults. |
| `tools/verify_frame.py` | Every `<activity>` satisfies all four requirements of `claude/frame-rule.md`. Validated by running it against a deliberately broken copy reproducing Tombot's settings-screen bug: 6 failures naming the exact causes. |
| `tools/rules_suite.py` | 73 invariants over the routing, filtering and bundle-blocking tables **parsed out of the Kotlin**, so the data under test is the data that ships. Weighted towards what must *not* be blocked. |
| `tools/js_suite.py` | 114 behaviour tests in a real Chromium at 412px. They run against markup **captured from real Reddit pages** — including the user's own blocked post, trimmed and scrubbed, in `fixture/r/capture/` — plus a live-component fixture with lazily-initialised media. Each group first proves the fixture really does block the page, then that the scripts undo it, and the four load-bearing behaviours are mutation-tested: neutering any one of them fails the suite. |
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
10. **Found on device:** an adult-flagged post opened blank. Everything the app
    did to the 18+ block treated it as *blur*, and on a post page it is
    *withholding* — a slot that is never rendered. The general lesson, again
    about fixtures: the feed and the post page are different pages, and a
    fixture of one says nothing about the other.
12. **Found by the compiler, which is the point:** 1.6.0 did not build.
    Changing a `@Volatile var` cache into a `val` map left the annotation
    behind, and `@Volatile` on a `val` is a compile error. Every check here was
    green, because **none of them compile anything** — the one honest line in
    this table is "not a type checker". `verify_kotlin.py` now rejects that
    pairing and `lateinit val` with it, negative-tested against the exact fault.
    The general form: when a build failure is something a regex could have
    seen, add the regex rather than just fixing the line.
11. **Found on device, twice:** the fix for (10) did not work either, and for a
    reason no fixture could show: **a captured DOM has no JavaScript in it.**
    Against the capture, editing the container's state reveals the post; on the
    phone it races Reddit's own component and empties it. *A fixture captures
    the page, not the program running on it.* The suite now carries a live
    component with lazily-loaded media alongside the captured markup, and the
    app prefers pressing the site's own button to editing its state.

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
   behind it not blurred. **Then open one of its posts** — media and self-text
   both there, within a second or two of the page settling rather than instantly,
   which is the fix working rather than failing. A spoiler-marked post should
   still ask. If a post ever comes up blank, turn off **Show adult content
   without tapping through**: the 18+ wall stays gone and the button comes back.
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
