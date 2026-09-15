# Threadbare — design notes

Fifth app in the suite. Package `com.threadbare.client`, minSdk 30, targetSdk 36,
AGP 8.11.1 / Kotlin 2.1.21 / Gradle 8.14.3 — same toolchain and architecture as
TastyWrap, SupplyChain and Tombot.

A WebView wrapper for reading Reddit without an account, without the Reddit app,
and without Reddit's telemetry.

---

## The surface: why www, and the dead end that came first

**Version 0 of this app wrapped `old.reddit.com` and was wrong.** The reasoning
was sound on its face — Reddit never shipped its app-promotion machinery into
the legacy site, so wrapping it would make both of the user's problems not exist
rather than be suppressed. The reasoning was also untestable from the
environment the app was built in: Reddit is unreachable there, from both
browsers, from WebFetch and through the egress proxy. It was built on the
inference anyway.

**Reddit began requiring an account to read old.reddit in July 2026.** A client
whose entire premise is reading without an account cannot use a surface that
demands one, so the whole design was void. The lesson is not "old.reddit was a
bad choice" — it was a good choice until it wasn't. The lesson is that *an
unverifiable premise is a blocker, not a caveat*, and it should have stopped the
build rather than being noted in the README.

`www.reddit.com` is explicitly unaffected by that change and still serves a
logged-out reader, so it is the surface this app wraps. `UrlRules` normalises
every Reddit host to it — including `old.reddit.com`, so that a decade of
existing links lands on a page instead of a login wall.

## The second failure: the upgrade path

Moving the app from old.reddit to www was done properly everywhere a fresh
install looks — the default, the entryValues, the host tables, the tests. It was
not done in the one place an *upgraded* device looks: `SCHEMA_VERSION` stayed at
1, so `materialiseDefaults` returned early and the start page stored by the
previous build survived. The app opened on old.reddit and Reddit answered with a
login wall. The version string was not bumped either, so the two builds were
indistinguishable on device — which made the symptom that much harder to place.

Every check in `tools/` passed, because every check looks at a tree, not at a
device with history. **A green suite that only ever sees a fresh install says
nothing about the upgrade path.** Two rules out of it:

- *Changing a default is a schema change.* Bump the version and write the
  migration, or the change reaches new installs only.
- *Canonicalise at the boundary, not at each caller.* Links and intents were
  normalised; the app's own loads were not. One unnormalised entry point was
  enough. `loadInApp` now canonicalises, which removes the category.

## The two blocks are one system — and what they actually are

The user asked for two things to be defeated: the "open in app" interstitial,
and the 18+ wall. On mobile web these are the same system, and as of 1.3.0 the
description below comes from **a DOM captured on a real Reddit page with each
overlay up** (September 2026), not from secondary sources.

**How it arrives.** The page ships an empty
`<faceplate-partial name="ActivateExperience_…" loading="programmatic"
src="/svc/shreddit/partial/<id>/activate-experience?…">`. After the trigger —
thirty seconds on a feed (`global_feeds_30_sec`), immediately on an adult-flagged
community — Reddit fetches that partial and slots the response into the page.
The response *is* the overlay:

| what the user sees | what it is |
|---|---|
| the app takeover | `<configured-xpromo-modal>` › `<rpl-bottom-sheet blocking open dialog-classname="configured-xpromo …">` — experience `mweb3x_feeds_blocking_xpromo_lo_page_fade` |
| the 18+ wall | `<configured-xpromo-modal>` › `<rpl-dialog blocking open overlay-blur …>` — experience `bypassable_xpromo_nsfw_bypassable` |

Both are `configured-xpromo`. The 18+ wall is an *xpromo experience* — Reddit
using age as a lever to push the app — with one difference: it is "bypassable".
Its "Yes, I'm Over 18" button does exactly three things, all visible in the
markup: `<ac-set-cookie name="over18" value="true">`, a `StoreUxtargetingAction
DISMISS` mutation, and `location.reload`. So the cookie the app seeds is now
`over18=true` — the identical cookie — and the server never selects that
experience at all.

**Why "nothing was clickable".** The rpl components open a native `<dialog>`
with `showModal()`. That puts it in the top layer and makes the rest of the
document **inert** — every other element becomes non-hit-testable and
unfocusable. Inertness survives `display: none`. Only closing the dialog, or
removing it from the document, ends it. The dialog lives in a shadow root.

**What the earlier version got wrong, and why.** 1.1.0–1.2.0 targeted
`shreddit-async-loader[paint-group="xpromo"]` and friends, taken from three
community userscripts. The captured DOM contains **no `paint-group` attribute at
all** and no element those scripts name. The app's stylesheet and observer
matched nothing, the request-level rule matched nothing (chunks are opaque
hashes like `concat:Bexg8Kzcx2,…`), and the user saw the overlay exactly as if
the app did nothing — because it did. The fixture the suite ran against was
built from the same stale names, so 38 green tests said nothing.

> **A fixture built from secondary sources proves the code matches the
> sources, not the site.** The two captures the user supplied were worth more
> than everything derived before them. Ask for a capture first.

The stale selectors are kept, at no cost, in case older markup is still served
somewhere; they are no longer what the defence rests on.

## Three layers, weakest last

### Layer 1 — refuse the request whose response is the overlay (`privacy/XpromoBlock.kt`)

**This is the layer that justifies building an app rather than installing a
userscript.** A userscript can only delete an overlay after the page has built
it. A WebView owns `shouldInterceptRequest` and can refuse the response that
contains it.

On the current build that means one request:
`/svc/shreddit/partial/*/activate-experience`. Refuse it and the
`configured-xpromo-modal` never arrives — no element, no modal dialog, no
inertness, nothing to fight. `activate-experience` is Reddit's own name for the
endpoint that returns whichever nag the experiment framework has picked, and it
has no other job on a page a logged-out reader is looking at. Every *other*
partial (`user-drawer-menu`, `theme-switcher-modal`, `login-step`, …) delivers
real UI and is left alone; the test suite asserts that.

The older chunk-name rule is kept beneath it. On this build the chunks are
opaque hashes and it matches nothing — which is fine, because it was written to
be a no-op rather than an over-match: only Reddit's asset hosts, only `.js`,
only tokens naming the promotion machinery, never `runtime`/`vendor`/`polyfill`.
A rule that misses costs nothing; a rule that over-matches breaks the site
invisibly. The suite still spends more assertions on what must *not* be blocked.

### Layer 2 — the stylesheet (`assets/xpromo-suppress.css`)

Injected at document start by appending a `<style>` to `document.documentElement`
— not to `<head>`, which may not exist yet at that point. Rules are therefore
live before first paint, so an overlay never flashes on its way to being hidden.

It hides the family by `paint-group` first, then by explicit ids and bundle
names, then by the older pre-shreddit class names. It also forces the overflow
back open unconditionally rather than chasing scroll-lock class names, because
an `!important` declaration beats the inline style the lock is applied with.

### Layer 3 — the observer (`SiteScripts.XPROMO_SUPPRESSOR`)

For what the first two layers cannot do:

1. **The modal dialog.** If the partial gets through anyway, the overlay is a
   native `<dialog>` opened with `showModal()` inside a shadow root, and the
   page is inert until it is closed. The observer walks the host's shadow roots,
   calls `close()` on every open dialog, then removes the host. Both, because
   either alone has a gap.
2. **Shadow roots generally.** An injected stylesheet does not cross the boundary.
3. **Classes on `<body>`.** CSS can force the overflow open but cannot remove a
   class the site may read.

The fixture for this (`tools/fixture/r/real/`) is built from the captured
markup, scrubbed of the user's identifiers, with stub `rpl-dialog` /
`rpl-bottom-sheet` elements that do what Reddit's do — stamp the template into
a shadow `<dialog>` and `showModal()`. The suite first proves, with no scripts
loaded, that the fixture really does make the page inert; only then does it
assert that the suppressor makes a post link hit-testable and focusable again.

rAF-coalesced, idempotent, no `setInterval`, and it disconnects when
`shreddit-app` is gone. The scripts it was derived from poll every 300 ms for
ten seconds; an observer fires when something changes and costs nothing when
nothing does, which on a phone is the difference that matters.

### The escape hatch

`Settings > Site version > Desktop`. xpromo is cross-promotion to the mobile
app, so a desktop client should never be offered any of it. When a future Reddit
build outruns all three layers — and on a monthly churn cycle it will — this is
the thing to reach for, and it is why the app has a UA setting at all. Because
client hints are not derived from the UA string, turning it on also installs a
`navigator.userAgentData` / `platform` / `vendor` shim; that is SupplyChain's
recorded lesson, not a hypothetical.

Touch is never spoofed. Reddit's layout branches on pointer capability, and
claiming no touch on a phone produces a page that expects hover.

## Anonymity

Logged-out by default, sign-in permitted (the user's call).

- **Ephemeral session by default** — cookies, DOM storage and cache cleared on
  exit. Turning on sign-in offers to switch this off, because the two cannot
  both be true and a silent logout every launch would look like a bug.
- **The default UA is the device's own, untouched.** That is both the native
  mobile layout and the least distinctive thing to send. The desktop UA is an
  opt-in escape hatch, and it *is* a fingerprinting mismatch on a phone — a
  deliberate trade, taken only when the user chooses it.
- Third-party cookies off; geolocation and every `onPermissionRequest` denied
  unconditionally; `DNT: 1` and `Sec-GPC: 1` as headers and as shimmed navigator
  properties.
- **Safe Browsing disabled at the manifest.** It sends URL hashes to Google,
  which is the whole reason this suite exists. WebView metrics opted out too.
- Reddit's telemetry endpoints blocked in `privacy/Blocklist.kt`, with a
  must-never-block list checked first.
- No `@JavascriptInterface` anywhere. Nothing in the page can call into Kotlin.

`CookieSeed` still writes `over18` and the `_options` opt-ins. On the legacy
site those *were* the entire 18+ defeat; here they are best-effort — they cost
one header each and Reddit has honoured them across hosts historically, but
neither was observable against a live Reddit while this was written, and the
overlay suppression is what the app actually relies on.

**Quarantined communities remain out of reach.** A quarantine is not the adult
flag; lifting one requires a signed-in account with a verified email and no
cookie or selector defeats it. If a quarantined community refuses, that is
expected, not a failure of any of the above.

## What will break, and what to do about it

When an overlay reappears on device, the order is:

1. Turn on **Desktop** in settings. If that fixes it, the problem is confined to
   the mobile surface and the app is usable while the rest is sorted out.
2. Re-derive the element names — the community userscripts above are the best
   available source, and they are maintained by people who can see the site.
3. Update `tools/fixture/` **first**, watch the suite fail, then fix the
   selectors. A fixture updated after the fix proves nothing.
4. Only then consider widening `XpromoBlock.PATTERNS`, and re-read the
   over-matching warning before doing it.
