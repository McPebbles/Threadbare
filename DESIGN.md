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

## The third and fourth failures: the same flag, twice misread

1.3.0 and 1.4.0 handled the 18+ block as an *overlay* — a dialog to close, a
filter to clear. On a post page it is neither.

From two captures of one post, blocked and then unblocked by pressing its own
"View NSFW content" button:

| | host attributes | shadow root |
|---|---|---|
| blocked | `mode="slot" reason="nsfw" embed-obscured blurred` | `.overlay` with the button, `span.inner.blurred` at `filter:blur(40px)`, **`<slot name="blurred">`**, `.bg-scrim` |
| unblocked | `mode="slot" reason="nsfw"` | `span.inner`, **`<slot name="revealed">`** |

The light DOM is identical in both: `<div slot="blurred">` holding a
server-blurred placeholder, and `<div slot="revealed">` holding the post. So in
`mode="slot"` the real post is **assigned to no slot** — absent from the layout,
not blurred in it. The feed reads fine because feed items use the `mode="wrap"`
variant, where the content *is* slotted and merely filtered.

**1.5.0 acted on that and was still wrong**, which is the more interesting half.
It cleared the host's state attributes and renamed the rendered slot at
document-start, and the suite — running the shipped script against the captured
markup — showed the post revealed. On the phone the same post came up blank.

> **A capture is the page, not the program running on it.** The DOM the user
> sends has no JavaScript in it. Reddit's `<shreddit-blurred-container>` is a
> live component whose bundle arrives late and whose revealed slot holds a
> *lazily initialised* player. Editing the element's state before it upgrades
> wins a race nobody wanted to win: the component comes up already-revealed and
> never runs the path that tells the media to load.

The tell was in the user's report and went unread twice: *a blank gap with no
button*. Reddit's blurred state always offers a button. A page with neither
content nor button is not Reddit's gate — it is the app's edit of it.

### What 1.6.0 does instead

`SiteScripts.ADULT_REVEALER`, under its own setting, in this order:

1. **Wait for the page to settle.** Nothing runs at document-start.
2. **Wait for the component**, up to three seconds after that, rather than
   assuming an element that has not upgraded yet never will.
3. **Press its own button.** Reddit's reveal path, with everything else it does
   still happening.
4. **Verify** — the revealed child must be assigned *and laid out*. Measuring
   the wrapper alone is not enough: Reddit's media wrapper holds an absolutely
   positioned child and measures zero itself while the media fills the frame.
5. **Fall back** to editing attributes and the rendered slot only when no
   component ever arrived, so pressing could not have worked.
6. **Roll it all back** if that produced nothing — placeholder and button
   restored. 1.5.0 hid the button before checking, which turned a page you
   could tap through into one you could not.

The tap target is hidden only after step 4 succeeds. Nothing is ever removed
from the shadow tree; lit hydrates against the marker comments in it.

Two fixtures, because each earlier version passed against one world and failed
in the other: `tools/fixture/r/capture/` is the user's own markup with no
definitions at all, and `tools/fixture/r/hydrate/` is a live component with
lazily-initialised media. The second is explicitly a **model of a hypothesis**,
and says so in its own comments — it reproduces a mechanism consistent with what
the device did, which is not the same as being the device. The four behaviours
above are mutation-tested; neutering any one of them fails the suite, and
neutering the grace period reproduces the 1.5.0 bug exactly.

### Why it is a separate setting

Until 1.6.0 this lived inside *Remove app prompts and the 18+ wall*, so the only
way to get a post page back was to give up the wall suppression too — and with
it the feed previews. Two jobs, two switches: one removes Reddit's nagging, the
other changes what the reader is shown. The new key inherits the old switch's
value on upgrade rather than defaulting to on, so anyone who had turned the old
one off to cope does not find the new one on underneath them.

## And one more, for third-party embeds

The 18+ confirmation explains Reddit-hosted media. The post that had been
failing all along turned out to be `post-type="link"` from `hgifs.com`, and
after the reveal its frame held:

    <shreddit-embed providername="hgifs" data-embed-obscured-deferred
        html='<iframe src="https://www.hgifs.com/ifr/…" …>'>

no iframe anywhere, and a "View in app" button. The provider's iframe markup
is in the attribute — the server sent it — and the component would not
instantiate it for a logged-out mobile reader. The promotion machinery again,
one layer deeper, and with nothing to press.

`hatchEmbeds` in the revealer waits a grace period after a container is
revealed, and for any `shreddit-embed` that still has no iframe, makes one from
the `src` in its own `html` attribute. Only the src (never the attribute's HTML),
only `https:`, inserted beside the component rather than into a shadow tree lit
owns, with the component hidden rather than removed. An embed that renders its
own iframe in time is left alone. `tools/fixture/r/embed/` has both, and the
suite asserts neither is doubled up.

> **"The content is in the page" is the test for whether reaching in is
> legitimate.** Pressing is preferred because it runs the site's own path. When
> the site's own path ends at an app-store link and the content is sitting in
> an attribute, using that content is not a guess about structure — it is what
> the component was going to do before it decided not to.

## The answer, after six versions

The control that settled it: the same post in Vanadium, logged out, shows the
blurred content and loads it **once the prompts are clicked through**. So the
server will send it, and the app was the difference.

Reddit's "Yes, I'm Over 18" button does four things, from the captured markup:

| action | what it is |
|---|---|
| `<ac-set-cookie name="over18" value="true">` | the cookie the app already seeded |
| `<ac-track san="xpromo\|dismiss\|bypassable_xpromo_nsfw_bypassable">` | telemetry, blocked, harmless |
| `<ac-gql-mutate operation="StoreUxtargetingAction" … action DISMISS>` | **server-side state against the loid** |
| `<ac-call method="location.reload">` | fetch the page again, now unlocked |

The third is the one that matters and the one a cookie cannot fake. Until that
mutation is recorded, an adult-flagged post's media is never in the page. Every
version of this app deleted the dialog carrying that button — that is the whole
bug, and it explains every symptom: the frame with the right height, the gate
that reveals correctly, the loader with nothing to load, and the refusal log
showing nothing refused.

> **A prompt can be a door.** Removing an interstitial is not always the same as
> getting past it. Ask what the accept button *does* before deleting it — here
> it was the only thing that would make the server send the content.

1.8.0 presses it, from the observer, before any removal pass runs. Capped at two
presses per session in `sessionStorage`, because the button reloads the page and
a server that kept re-serving the wall would otherwise loop; past the cap the
old removal behaviour resumes. Nothing that carries the button is removed before
it has been pressed. The fixture `tools/fixture/r/agegate/` reproduces the
button with its four `ac-*` actions as stubs, and the suite asserts which ones
fired — and the `r/real/` fixture's captured modal now asserts the press too,
where it used to assert deletion. Mutation-tested: stop the press and four
assertions fail.

## What the device finally said (1.7.0 diagnostics)

The report that ended four rounds of inference, from an adult-flagged post with
every setting on:

- one `shreddit-blurred-container`, `gated=false`, `upgraded=true`,
  `renderedSlot=revealed`, `presses=1`, `done=true`, `revealedHeight=411` —
  **the reveal works**, by pressing Reddit's own button, exactly as designed;
- the media frame is 411px and contains
  `<xpromo-nsfw-blocking-container><shreddit-blurred-container …>` —
  **that element is real and it wraps the post**. It came from a userscript in
  1.1.0 and `pierceShadow()` removed the host outright. That is what emptied
  post pages;
- the revealed slot holds a `shreddit-async-loader` and the only media elements
  present are the two placeholder images — **the post itself had never been
  fetched**, with the experience partial refused at the network.

Two lessons, neither of which is about Reddit:

> **An element named for the block may be wrapped around the thing being
> blocked.** A name in a kill list is a guess about structure. Guard the
> structure instead: never delete a subtree that holds content.

> **A request-level defence needs a request-level report.** The DOM report was
> built first and could say the page was empty but not why. One list of refused
> URLs would have settled it in a round, so the report now carries both.

## The fifth failure: the suppressor was eating the post

The reveal work above was built on the wrong suspect. With 1.6.1 on the phone,
turning the *reveal* off changed nothing; turning **prompt suppression** off was
the only thing that brought an adult-flagged post back. So the element being
destroyed is one the prompt suppressor destroys, not one the revealer touches.

Against the captured page the suppressor matches nothing — no KILL selector, no
stylesheet rule, verified by running the shipped scripts over it and walking the
media frame's ancestors. Therefore what it removes is delivered at runtime, and
the only runtime-delivered thing it removes is the experience partial's
`<configured-xpromo-modal>`. On a post page that response evidently carries the
post as well as the wall. Remove the wrapper and both go; what remains is
Reddit's `bg-black` media frame, which is exactly the "black box with no
content, no button" in the report.

The fix is a rule rather than a selector, because the selector will change:

> **Removing a prompt must never remove content.** Before deleting anything,
> ask whether the post is inside it. If it is, close the dialog it carries,
> remove that, and leave the subtree alone.

`holdsContent()` in the observer, and `:not(:has(...))` on every `display:none`
rule in the stylesheet. A browser without `:has()` drops the rule entirely,
which shows a prompt that the observer then removes — the safe direction. The
fixture `tools/fixture/r/wrapped/` builds the wrapped shape and the guard is
mutation-tested: with it disabled the post vanishes and the media measures zero,
which is the device report reproduced in the suite.

This is the third distinct mechanism behind one symptom, which is why 1.7.0 also
ships diagnostics: "Why is this blank?" and "Save page HTML", off by default.
Guessing has now cost more than the feature.

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
4. **The withheld post** is deliberately NOT handled here any more. It is
   content rather than a prompt, it needs the page to have settled, and it
   belongs to a different setting: `ADULT_REVEALER`, above.

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

## Which browser gets external links

`Settings > Browser for external links`, independent of the system default. The
case it exists for: Vanadium as the daily driver, Firefox Focus for whatever a
thread links out to — a disposable session for links the reader did not choose
and has no relationship with. The alternative was copy, switch app, paste, which
is tedious and leaves the URL on the clipboard.

Three things worth knowing about how it is built:

**Enumerating browsers uses a scheme-only `http:` probe.** A URI with no host
cannot match an intent filter that constrains the host, so this separates real
browsers from every app that claims one domain — including this app's own Reddit
filter. Without it the picker would offer to send external links to a video app.

**An always-private browser skips the prompt.** Focus, Klar and Tor have no
ordinary mode: "ask each time" would be a prompt with one answer, and "never
open privately" cannot make Focus keep history. So choosing one is how a reader
stops being asked, which is most of the point. The private-tab setting keeps
applying to browsers that genuinely have both modes.

**A browser that is chosen and then uninstalled is announced, not swallowed.**
Falling back silently would change behaviour without telling anyone, and if the
missing browser was private-by-design it would be a silent privacy change.
`BrowserChoice.resolve` distinguishes "never chose one" from "chose one, it is
gone" so the caller can say which happened.

The picker labels each browser with what it can actually do — "always private",
"can open private tabs", or nothing. Those are claims made to someone deciding
what to trust, so `BrowserChoiceTest` asserts the mapping rather than leaving it
to be assumed, and "always private" is only ever attached to a browser with no
ordinary mode at all.

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
