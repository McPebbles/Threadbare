#!/usr/bin/env python3
"""
Behaviour tests for the injected scripts, run in a real Chromium at phone width.

These run the *shipped* JavaScript — extracted from the Kotlin by
tools/extract_js.py — as document-start scripts, exactly as
WebViewCompat.addDocumentStartJavaScript would, against a fixture built from the
element names and attributes Reddit actually ships.

What this can prove: the suppressor removes every member of the xpromo family
including the one inside a shadow root, unfreezes a scroll-locked page, unblurs
the content behind the wall, renders an adult-flagged post that the site
withheld by slotting it nowhere, catches an overlay injected after load, and
leaves ordinary content — and a spoiler blur — alone.

What it cannot prove: that the fixture still matches Reddit. The element names
came from community userscripts updated monthly, and Reddit rebuilds this app
continuously. When the suppressor stops working on device, the first thing to do
is re-derive the fixture, not patch the CSS blindly.

Usage: python3 tools/js_suite.py [--shots outdir]
"""
import argparse
import functools
import http.server
import os
import socketserver
import subprocess
import sys
import threading

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIXTURE = os.path.join(ROOT, "tools", "fixture")
JSDIR = os.path.join(ROOT, "build", "js")
SCRIPTS = ("desktop_shim", "privacy_signals", "suppressor_style", "xpromo_suppressor",
           "reveal_style", "adult_revealer", "diagnostics_flag", "reveal_report")

PASS, FAIL = [], []


def check(name, condition, detail=""):
    (PASS if condition else FAIL).append(name)
    mark = "ok  " if condition else "FAIL"
    print("  %s %s%s" % (mark, name, (" — " + str(detail)) if detail and not condition else ""))


def serve(directory):
    handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=directory)
    handler.log_message = lambda *a, **k: None
    httpd = socketserver.TCPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd, httpd.server_address[1]


def gone(page, selector):
    """True when nothing matching is present AND rendered."""
    return page.evaluate(
        """(sel) => {
            const els = [...document.querySelectorAll(sel)];
            if (els.length === 0) return true;
            return els.every(e => getComputedStyle(e).display === 'none');
        }""", selector)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", default=None)
    args = ap.parse_args()

    if not os.path.isdir(JSDIR):
        subprocess.check_call([sys.executable, os.path.join(ROOT, "tools", "extract_js.py")])

    scripts = {}
    for name in SCRIPTS:
        with open(os.path.join(JSDIR, name + ".js"), encoding="utf-8") as fh:
            scripts[name] = fh.read()

    from playwright.sync_api import sync_playwright

    httpd, port = serve(FIXTURE)
    base = "http://127.0.0.1:%d" % port

    with sync_playwright() as pw:
        browser = pw.chromium.launch()

        # -------- default (mobile) context: no desktop shim, as shipped
        ctx = browser.new_context(viewport={"width": 412, "height": 915},
                                  device_scale_factor=2)
        for name in ("privacy_signals", "suppressor_style", "xpromo_suppressor",
                     "reveal_style", "adult_revealer"):
            ctx.add_init_script(scripts[name])
        page = ctx.new_page()
        page.goto(base + "/r/privacy/", wait_until="load")
        page.wait_for_timeout(300)

        print("\nthe xpromo family")
        check("paint-group=xpromo loaders are gone",
              gone(page, 'shreddit-async-loader[paint-group="xpromo"]'))
        check("the bottom bar is gone", gone(page, "xpromo-bottom-bar"))
        check("the app selector is gone", gone(page, "xpromo-app-selector"))
        check("the bottom sheet is gone", gone(page, "#xpromo-bottom-sheet"))
        check("the nsfw blocking dialog is gone",
              gone(page, '[id*="xpromo_nsfw_blocking"]'))
        check("the blocking modal is gone", gone(page, "#blocking-modal"))
        check("the QR dialog is gone", gone(page, "#nsfw-qr-dialog"))
        check("legacy XPromoBottomBar is gone", gone(page, ".XPromoBottomBar"))
        check("legacy #get-app is gone", gone(page, "#get-app"))

        print("\nthe shadow root (the case CSS cannot reach)")
        # Built on demand: a statically-authored host is removed at
        # document-start before the page can attach a shadow root to it, which
        # makes the assertion below pass against a page where nothing was ever
        # there. Create it, prove it exists, then prove it goes.
        # Created and asserted in ONE evaluate. Split across two, the
        # suppressor's rAF fires in between and removes the probe before the
        # assertion runs — which reads as "the fixture failed to build it" when
        # in fact the suppressor simply won the race. Correct behaviour, wrong
        # test; this is the same shape of mistake as a vacuous assertion.
        check("the fixture really did build a shadow root to remove",
              page.evaluate(
                  """(() => {
                      window.__makeNsfw('probe');
                      const h = document.getElementById('probe');
                      return !!h && !!h.shadowRoot
                             && !!h.shadowRoot.querySelector('.prompt');
                  })()"""))
        page.wait_for_timeout(250)
        check("the nsfw container host is removed entirely",
              page.evaluate(
                  "document.querySelectorAll('xpromo-nsfw-blocking-container').length") == 0)

        # Removing the host supersedes piercing, so force the case where it
        # cannot be removed — otherwise pierceShadow() is never under test.
        page.evaluate("window.__makeStubbornNsfw()")
        page.wait_for_timeout(250)
        check("a host that refuses removal still has its prompt pierced",
              page.evaluate(
                  """(() => { const h = document.getElementById('stubborn');
                      if (!h || !h.shadowRoot) return false;
                      return !h.shadowRoot.querySelector('.prompt'); })()"""))

        print("\nthe scroll lock")
        check("the lock class is off body",
              page.evaluate("!document.body.classList.contains('rpl-scroll-lock')"))
        check("body overflow is not hidden",
              page.evaluate("getComputedStyle(document.body).overflow") != "hidden")
        check("body position is not fixed",
              page.evaluate("getComputedStyle(document.body).position") != "fixed")
        # The real question is not what the styles say but whether it scrolls.
        scrolled = page.evaluate(
            """() => { window.scrollTo(0, 400); return window.scrollY; }""")
        check("the page actually scrolls", scrolled > 0, scrolled)
        page.evaluate("window.scrollTo(0,0)")

        print("\nthe backdrop and the blur")
        check("the fixed backdrop is gone", gone(page, "#backdrop"))
        check("the thumbnail is unblurred",
              page.evaluate(
                  "getComputedStyle(document.getElementById('shot')).filter") in ("none", ""))
        check("spoilered text is unblurred",
              page.evaluate(
                  "getComputedStyle(document.getElementById('spoiler')).filter") in ("none", ""))
        # 1.6.0 no longer strips [blurred] from arbitrary elements — editing a
        # live component's state is what emptied post pages. A plain blurred
        # element is unblurred by the stylesheet instead, which changes nothing
        # the site can read.
        check("a plain blurred element is unblurred without touching its state",
              page.evaluate(
                  "getComputedStyle(document.getElementById('highlight')).filter")
              in ("none", ""))
        check("the scrim is gone", gone(page, ".bg-scrim"))
        check("the thumbnail shadow is gone", gone(page, ".thumbnail-shadow"))

        print("\nordinary content survives (the test that matters most)")
        # A suppressor that takes the page with the overlay is not a fix.
        check("all three posts are still present",
              page.evaluate("document.querySelectorAll('.post').length") == 3)
        check("the site header survives",
              page.evaluate("!!document.getElementById('site-header')"))
        check("the search box survives",
              page.evaluate("!!document.querySelector('input[type=search]')"))
        check("the code block survives",
              page.evaluate("!!document.querySelector('.post pre code')"))
        check("shreddit-app itself is untouched",
              page.evaluate("!!document.querySelector('shreddit-app')"))
        check("the image element is kept, not deleted",
              page.evaluate("!!document.getElementById('shot')"))

        print("\na route change injecting a fresh overlay (layer 3's whole job)")
        page.evaluate("window.__injectLate()")
        page.wait_for_timeout(300)
        check("the late overlay is removed", gone(page, "#late-sheet"))
        check("a late nsfw container is removed too",
              page.evaluate(
                  "!document.getElementById('late-nsfw')"))
        check("the late scroll lock is released",
              page.evaluate("!document.body.classList.contains('rpl-scroll-lock')"))
        check("the page still scrolls afterwards",
              page.evaluate("() => { window.scrollTo(0, 300); return window.scrollY; }") > 0)

        print("\nprivacy signals")
        check("doNotTrack is '1'", page.evaluate("navigator.doNotTrack") == "1")
        check("globalPrivacyControl is true",
              page.evaluate("navigator.globalPrivacyControl") is True)
        check("the UA is NOT spoofed in the default mobile mode",
              "Windows" not in page.evaluate("navigator.userAgent"))

        # ------------------------------------------ the REAL overlay
        # Everything above ran against selectors derived from userscripts. None
        # of those matched the DOM Reddit actually shipped in September 2026.
        # This section runs against markup captured from that DOM, with stub
        # components that reproduce its one important property: a native
        # <dialog> opened with showModal(), which makes the page inert.
        print("\nthe overlay as actually shipped (captured DOM, inert modal)")
        rp = ctx.new_page()
        rp.goto(base + "/r/real/", wait_until="load")
        rp.wait_for_timeout(200)

        # Prove the fixture is faithful BEFORE the suppressor acts: with the
        # suppressor absent the modal must make the page inert, or the
        # assertions after it are vacuous. Done in a context with no scripts.
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/real/", wait_until="load")
        state = pp.evaluate("(() => { window.__activateExperience('app'); return window.__pageInert(); })()")
        check("fixture: with no suppressor the app overlay makes the page inert",
              state["openDialogs"] == 1 and not state["hitIsLink"], state)
        plain.close()

        # Now the shipped scripts. The overlay arrives late, as on Reddit.
        rp.evaluate("window.__activateExperience('app')")
        rp.wait_for_timeout(400)
        state = rp.evaluate("window.__pageInert()")
        check("app overlay: configured-xpromo-modal is removed",
              rp.evaluate("!document.querySelector('configured-xpromo-modal')"))
        check("app overlay: no modal dialog remains open anywhere (incl. shadow roots)",
              state["openDialogs"] == 0, state)
        check("app overlay: a post link is hit-testable again",
              state["hitIsLink"], state)
        check("app overlay: a post link can take focus again",
              state["focusable"], state)
        check("app overlay: the page still scrolls",
              rp.evaluate("() => { window.scrollTo(0, 300); return window.scrollY; }") > 0)
        rp.evaluate("window.scrollTo(0,0)")

        rp.evaluate("window.__activateExperience('nsfw')")
        rp.wait_for_timeout(400)
        # 1.8.0 changed the contract here, and the captured markup is what
        # forced it: the 18+ variant carries "Yes, I'm Over 18", whose handlers
        # record the dismissal that makes Reddit send the post's media. So it is
        # pressed first. Deleting it — what every earlier version did — is what
        # left adult-flagged posts empty.
        check("18+ wall: the confirmation is pressed, not deleted",
              rp.evaluate("window.__over18Clicks") >= 1,
              rp.evaluate("window.__over18Clicks"))
        # In this fixture the press goes nowhere (no handlers, no reload), which
        # is the case the retry cap exists for: give up and remove it.
        rp.wait_for_timeout(2600)
        state = rp.evaluate("window.__pageInert()")
        check("18+ wall: a press that goes nowhere ends in removal",
              rp.evaluate("!document.querySelector('configured-xpromo-modal')"))
        check("18+ wall: no modal dialog remains open", state["openDialogs"] == 0, state)
        check("18+ wall: the page is interactive", state["hitIsLink"] and state["focusable"], state)
        check("18+ wall: it stopped pressing rather than looping",
              rp.evaluate("window.__over18Clicks") <= 2,
              rp.evaluate("window.__over18Clicks"))
        check("18+ wall: the posts behind it survive",
              rp.evaluate("document.querySelectorAll('.post').length") == 3)
        check("the empty experience partial itself is left alone",
              rp.evaluate("!!document.querySelector('faceplate-partial[name^=\"ActivateExperience\"]')"))

        # A context with everything the default install runs: both settings on.
        rctx = browser.new_context(viewport={"width": 412, "height": 915})
        for name in ("suppressor_style", "xpromo_suppressor", "reveal_style", "adult_revealer"):
            rctx.add_init_script(scripts[name])

        # ------------------------------------- un-gating adult-flagged content
        # Two fixtures, because the last two versions each passed against one
        # world and failed in the other: ../capture/ is the user's own markup
        # with no component definitions, ../hydrate/ is a live component with a
        # lazily-initialised player.
        # ---------------------------------------- a deferred third-party embed
        # The device's last report: the reveal worked, and inside it sat a
        # <shreddit-embed> holding the provider's iframe markup in an attribute,
        # rendering a "View in app" button instead. Content the server sent and
        # the client declined to show.
        print("\na deferred third-party embed")
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/embed/", wait_until="load")
        pp.wait_for_timeout(300)
        raw = pp.evaluate("window.__state()")
        check("fixture: with no scripts nothing is revealed and no iframe exists",
              raw["nagStillGated"] and not raw["nag"] and not raw["good"], raw)
        pp.evaluate("document.querySelector('#frame-own shreddit-blurred-container').shadowRoot.querySelector('button').click()")
        pp.wait_for_timeout(200)
        strip = pp.evaluate("window.__state()")
        # On the device the strip measured 150px (an iframe's intrinsic height);
        # in this stub the wrapper has no in-flow content at all, so it is 0.
        # Either way: far short of the frame.
        check("fixture: with no scripts Reddit's own feed iframe is a strip, not the frame",
              strip["ownIframeHeight"] <= 150 and strip["own"]["h"] > 250,
              (strip["own"], strip["ownIframeHeight"]))
        check("fixture: the feed frames start square, as Reddit sized them",
              abs(raw["declared"]["ratio"] - 1.0) < 0.05 and abs(raw["placeholder"]["ratio"] - 1.0) < 0.05,
              (raw["declared"], raw["placeholder"]))
        plain.close()

        ep = rctx.new_page()
        ep.goto(base + "/r/embed/", wait_until="load")
        ep.wait_for_timeout(4500)
        st = ep.evaluate("window.__state()")
        tb = [f for f in st["nag"] if f["tb"]]
        check("the nagging embed gets an iframe made from Reddit's own src",
              len(tb) == 1 and tb[0]["src"] == "https://provider.invalid/ifr/880431425030949422", st)
        check("that iframe fills the frame", tb and tb[0]["h"] > 0, st)
        check("the 'View in app' component is hidden, not removed", st["nagHidden"], st)
        own = [f for f in st["good"] if f["own"]]
        check("a well-behaved embed renders its own iframe", len(own) == 1, st)
        check("and is not doubled up", len(st["good"]) == 1, st)

        # The feed: Reddit sized the frame square for the placeholder; the
        # media is 16:9. Left alone, the gif plays in half the box and the rest
        # is black. The frame takes the media's shape, from the markup's own
        # width/height when it has them, else from Reddit's preview image.
        check("a landscape embed with declared dimensions reshapes its frame",
              abs(st["declared"]["ratio"] - 1.78) < 0.06 and st["declared"]["iframeFills"], st["declared"])
        check("one without them takes the preview image's shape instead",
              abs(st["placeholder"]["ratio"] - 1.78) < 0.06 and st["placeholder"]["iframeFills"], st["placeholder"])

        # The feed as reported: Reddit's own iframe, 150px tall in a 379px
        # frame because its wrapper has no height. Nothing is hatched; the app
        # gives the wrapper the frame's height and the frame the media's shape
        # (a 9:16 placeholder, clamped by Reddit's own max-height).
        check("Reddit's own feed iframe fills its frame instead of a 150px strip",
              st["own"]["iframeFills"] and st["ownIframeHeight"] > 150, st["own"])
        check("and the frame takes the portrait media's shape, within Reddit's clamp",
              st["own"]["h"] > st["own"]["w"], st["own"])
        check("nothing was hatched for it",
              ep.evaluate("!document.querySelector('#frame-own [data-tb-embed]')"))

        # ------------------------------------------- answering the 18+ wall
        # The last piece, and the one that explains six versions of an empty
        # post: Reddit withholds the media until the confirmation's mutation is
        # recorded, and the app was deleting the confirmation.
        print("\nthe 18+ confirmation")
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/agegate/", wait_until="load")
        raw = pp.evaluate("(() => { window.__serveWall(); return window.__state(); })()")
        check("fixture: the wall opens a modal dialog and fires nothing yet",
              raw["openDialogs"] == 1 and raw["fired"]["cookie"] == 0, raw)
        plain.close()

        ap = rctx.new_page()
        ap.goto(base + "/r/agegate/", wait_until="load")
        ap.evaluate("window.__serveWall()")
        ap.wait_for_timeout(600)
        st = ap.evaluate("window.__state()")
        check("the app presses Reddit's own confirmation", st["fired"]["cookie"] == 1, st)
        check("which is what records the dismissal server-side",
              st["fired"]["mutation"] == 1, st)
        check("and Reddit's own reload runs", st["fired"]["reload"] == 1, st)
        check("it was pressed, not deleted first", st["accepted"] == "1", st)

        # A server that keeps serving the wall must not put the app in a reload
        # loop: two presses, then it goes back to removing the thing.
        ap.evaluate("window.__serveWall()")
        ap.wait_for_timeout(600)
        ap.evaluate("window.__serveWall()")
        ap.wait_for_timeout(1200)
        st = ap.evaluate("window.__state()")
        check("pressing stops after two attempts", st["fired"]["cookie"] <= 2, st)
        check("and the wall is removed instead once it has stopped",
              st["openDialogs"] == 0, st)

        # ------------------------------ removing a prompt must not remove a post
        # The failure this guards: on device, the ONLY setting that changed
        # anything was the prompt suppression, and against the captured page the
        # suppressor removes nothing — so what it removes arrives at runtime.
        # Whatever that element turns out to be, deleting a subtree that holds
        # the post is never the right move.
        print("\na prompt wrapped around the post")
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/wrapped/", wait_until="load")
        raw = pp.evaluate("(() => { window.__activateWrapped(); return window.__state(); })()")
        check("fixture: with no scripts the wall makes the page inert",
              raw["openDialogs"] == 1 and not raw["pageUsable"], raw)
        check("fixture: and the post is inside the thing the app deletes",
              raw["containerPresent"] and raw["modalPresent"], raw)
        plain.close()

        wp = rctx.new_page()
        wp.goto(base + "/r/wrapped/", wait_until="load")
        wp.evaluate("window.__activateWrapped()")
        wp.wait_for_timeout(5000)
        st = wp.evaluate("window.__state()")
        check("the wall's dialog is closed", st["openDialogs"] == 0, st)
        check("the page is usable again", st["pageUsable"], st)
        check("the post survived the prompt removal", st["containerPresent"], st)
        check("and the stylesheet did not hide it either", st["modalHidden"] is False, st)
        check("the media inside it is revealed", st["mediaHeight"] > 0, st)

        print("\nthe diagnostics report")
        dctx2 = browser.new_context(viewport={"width": 412, "height": 915})
        for name in ("diagnostics_flag", "suppressor_style", "xpromo_suppressor",
                     "reveal_style", "adult_revealer"):
            dctx2.add_init_script(scripts[name])
        dp2 = dctx2.new_page()
        dp2.goto(base + "/r/capture/", wait_until="load")
        dp2.wait_for_timeout(4500)
        report = dp2.evaluate(scripts["reveal_report"])
        check("the report is JSON and names the containers it found",
              '"containersFound": 2' in report, report[:200])
        check("the report says whether the component ever upgraded",
              '"upgraded"' in report and '"presses"' in report, report[:200])
        check("the report names which post it is describing",
              '"mainPost"' in report and '"postsOnPage"' in report, report[:300])
        # Without the flag the page must not be able to see the app at all.
        quiet = rctx.new_page()
        quiet.goto(base + "/r/capture/", wait_until="load")
        quiet.wait_for_timeout(300)
        check("no diagnostics flag means no global for a page to find",
              quiet.evaluate("typeof window.__tbReveal") == "undefined")
        dctx2.close()

        print("\nthe reveal, against the user's captured markup (no definitions)")
        cp = rctx.new_page()
        cp.goto(base + "/r/capture/", wait_until="load")
        cp.wait_for_timeout(4500)
        st = cp.evaluate("window.__state()")
        check("the captured post's media is slotted", st["assigned"], st)
        check("the captured post's media is laid out", st["playerHeight"] > 0, st)
        check("the captured post is not blurred", (st["filter"] or "none") in ("none", ""), st)
        check("the captured post's state attributes are cleared", not st["gated"], st)
        # The captured post's self-text is a single line, so this asserts it is
        # laid out at all; the 88px clamp is covered by ../post/, whose body is
        # long enough for the clamp to cut it.
        check("the captured post's self-text is laid out", st["textHeight"] > 0, st)
        check("the tap target is gone once the content is really there",
              not st["buttonShown"], st)

        # The control: no scripts, the same file. If this passes the fixture is
        # not gating anything and everything above is vacuous.
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/capture/", wait_until="load")
        pp.wait_for_timeout(200)
        raw = pp.evaluate("window.__state()")
        check("fixture: with no scripts the captured post stays withheld",
              raw["gated"] and not raw["assigned"] and raw["playerHeight"] == 0, raw)
        plain.close()

        print("\nthe reveal, against a live component with lazy media")
        # First the control, in a context with no scripts: clearing state before
        # the definition arrives is what 1.4.0 and 1.5.0 did, and it must leave
        # the player empty — otherwise this fixture cannot tell the versions
        # apart and the assertions after it mean nothing.
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/hydrate/", wait_until="load")
        pp.evaluate("window.__editStateEarly()")
        pp.evaluate("window.__hydrate()")
        pp.wait_for_timeout(300)
        broken = pp.evaluate("window.__state()")
        check("fixture: editing state before hydration leaves the media empty",
              not broken["gated"] and broken["mediaHeight"] == 0, broken)
        plain.close()

        hp = rctx.new_page()
        hp.goto(base + "/r/hydrate/", wait_until="load")
        hp.wait_for_timeout(300)
        hp.evaluate("window.__hydrate()")
        hp.wait_for_timeout(1600)
        st = hp.evaluate("window.__state()")
        check("the live component's media is actually loaded", st["mediaHeight"] > 0, st)
        check("the live component is no longer gated", not st["gated"], st)
        check("the self-text is revealed too", st["textHeight"] > 0, st)
        check("the tap target is taken away only after that",
              not st["buttonShown"], st)
        check("a spoiler is still blurred", st["spoilerBlurred"], st)

        # And the promise that matters when the diagnosis is wrong: if nothing
        # the app does produces content, the reader gets Reddit's own button
        # back rather than a dead box.
        print("\nwhen the reveal cannot work at all")
        sp = rctx.new_page()
        sp.goto(base + "/r/stubborn/", wait_until="load")
        sp.wait_for_timeout(7000)
        st = sp.evaluate("window.__state()")
        check("a container that never yields keeps its button",
              st["buttonShown"], st)
        check("and gets its blurred placeholder back, not an empty frame",
              st["slotName"] == "blurred" and st["placeholderHeight"] > 0, st)

        # --------------------------------- the blur that withholds, not covers
        # The bug this section exists for: every assertion above could pass and
        # a post still open empty. In mode="slot" the container renders
        # <slot name="blurred">, so the real post is in the document assigned
        # to no slot — not blurred, not rendered. The fixture carries no
        # component definitions, which is the case the app cannot rule out on
        # device: server-rendered markup that has not hydrated, where removing
        # an attribute makes nothing happen.
        print("\nthe post page: content behind the adult flag (captured DOM)")
        plain = browser.new_context(viewport={"width": 412, "height": 915})
        pp = plain.new_page()
        pp.goto(base + "/r/post/", wait_until="load")
        pp.wait_for_timeout(150)
        before = pp.evaluate("window.__mediaState()")
        check("fixture: with no suppressor the post media is withheld entirely",
              before["present"] and not before["assigned"] and before["height"] == 0, before)
        tbefore = pp.evaluate("window.__textState()")
        check("fixture: with no suppressor the self-text is blurred",
              "blur" in (tbefore["filter"] or ""), tbefore)
        check("fixture: with no suppressor the button is what the finger lands on",
              before["hostTopmost"] == "reveal-button", before)
        plain.close()

        op = ctx.new_page()
        op.goto(base + "/r/post/", wait_until="load")
        op.wait_for_timeout(4500)
        m = op.evaluate("window.__mediaState()")
        check("the post media is assigned to the rendered slot", m["assigned"], m)
        check("the post media occupies the page", m["height"] > 0, m)
        check("the post media is not blurred", (m["filter"] or "none") in ("none", ""), m)
        check("the post media is what the finger lands on, not the button",
              m["topmost"] == "post-media", m)
        check("the host's obscured attributes are cleared", not m["hostBlurred"], m)
        t = op.evaluate("window.__textState()")
        check("the self-text is unblurred", (t["filter"] or "none") in ("none", ""), t)
        check("the self-text is not left clipped to the placeholder's 88px",
              t["height"] > 88, t)
        s = op.evaluate("window.__spoilerState()")
        check("a spoiler blur is left alone — it is not the age gate",
              "blur" in (s["filter"] or ""), s)
        check("a spoiler container keeps its state attribute", s["hostBlurred"], s)
        check("the comments below are untouched",
              op.evaluate("!!document.getElementById('comment-1')"))

        # A container that arrives on an SPA route change, with no definition.
        op.evaluate("window.__injectLateBlurred()")
        op.wait_for_timeout(4000)
        check("a container injected after load is revealed too",
              op.evaluate(
                  """(() => { const el = document.getElementById('late-media');
                      return !!el && !!el.closest('[slot]').assignedSlot; })()"""))

        # And the other half: the definition arrives late and re-renders from
        # its own attributes. The end state has to be revealed either way —
        # if it reacts, because the attributes are gone; if it does not, because
        # the shadow tree was patched.
        hp = ctx.new_page()
        hp.goto(base + "/r/post/", wait_until="load")
        hp.wait_for_timeout(300)
        # This stub's button has no handler, so pressing it does nothing — the
        # "asked nicely and got nowhere" path, which must still end revealed.
        hp.evaluate("window.__defineHydrating()")
        hp.wait_for_timeout(6000)
        hm = hp.evaluate("window.__mediaState()")
        check("after the component hydrates the media stays revealed",
              hm["assigned"] and hm["height"] > 0, hm)
        ht = hp.evaluate("window.__textState()")
        check("after the component hydrates the self-text stays unblurred",
              (ht["filter"] or "none") in ("none", ""), ht)
        hs = hp.evaluate("window.__spoilerState()")
        check("after hydration a spoiler is still blurred",
              "blur" in (hs["filter"] or ""), hs)


        if args.shots:
            os.makedirs(args.shots, exist_ok=True)
            page.evaluate("window.scrollTo(0,0)")
            page.screenshot(path=os.path.join(args.shots, "suppressed.png"))
            raw = browser.new_context(viewport={"width": 412, "height": 915},
                                      device_scale_factor=2)
            rp = raw.new_page()
            rp.goto(base + "/r/privacy/", wait_until="load")
            rp.screenshot(path=os.path.join(args.shots, "unsuppressed.png"))
            # The post page, which is the pair worth looking at: the same
            # fixture with and without the scripts.
            rp2 = raw.new_page()
            rp2.goto(base + "/r/post/", wait_until="load")
            rp2.wait_for_timeout(200)
            rp2.screenshot(path=os.path.join(args.shots, "post-blocked.png"))
            op.screenshot(path=os.path.join(args.shots, "post-revealed.png"))
            print("\nscreenshots in %s" % args.shots)

        # -------- desktop mode: the escape hatch still shims client hints
        print("\ndesktop mode (the manual escape hatch)")
        dctx = browser.new_context(viewport={"width": 412, "height": 915})
        for name in SCRIPTS:
            dctx.add_init_script(scripts[name])
        dp = dctx.new_page()
        dp.goto(base + "/r/privacy/", wait_until="load")
        check("userAgentData reports desktop",
              dp.evaluate("navigator.userAgentData.mobile") is False)
        check("platform is shimmed to Windows",
              dp.evaluate("navigator.userAgentData.platform") == "Windows")
        check("touch is still NOT spoofed away",
              dp.evaluate("typeof navigator.maxTouchPoints") == "number")

        browser.close()

    httpd.shutdown()
    print("\n%d passed, %d failed" % (len(PASS), len(FAIL)))
    if FAIL:
        for f in FAIL:
            print("  FAILED: %s" % f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
