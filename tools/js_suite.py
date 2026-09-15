#!/usr/bin/env python3
"""
Behaviour tests for the injected scripts, run in a real Chromium at phone width.

These run the *shipped* JavaScript — extracted from the Kotlin by
tools/extract_js.py — as document-start scripts, exactly as
WebViewCompat.addDocumentStartJavaScript would, against a fixture built from the
element names and attributes Reddit actually ships.

What this can prove: the suppressor removes every member of the xpromo family
including the one inside a shadow root, unfreezes a scroll-locked page, unblurs
the content behind the wall, catches an overlay injected after load, and leaves
ordinary content alone.

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
SCRIPTS = ("desktop_shim", "privacy_signals", "suppressor_style", "xpromo_suppressor")

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
        for name in ("privacy_signals", "suppressor_style", "xpromo_suppressor"):
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
        check("the blurred attribute is cleared",
              page.evaluate(
                  "!document.getElementById('highlight').hasAttribute('blurred')"))
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
        state = rp.evaluate("window.__pageInert()")
        check("18+ wall: configured-xpromo-modal is removed",
              rp.evaluate("!document.querySelector('configured-xpromo-modal')"))
        check("18+ wall: no modal dialog remains open", state["openDialogs"] == 0, state)
        check("18+ wall: the page is interactive", state["hitIsLink"] and state["focusable"], state)
        check("18+ wall: the posts behind it survive",
              rp.evaluate("document.querySelectorAll('.post').length") == 3)
        check("the empty experience partial itself is left alone",
              rp.evaluate("!!document.querySelector('faceplate-partial[name^=\"ActivateExperience\"]')"))

        if args.shots:
            os.makedirs(args.shots, exist_ok=True)
            page.evaluate("window.scrollTo(0,0)")
            page.screenshot(path=os.path.join(args.shots, "suppressed.png"))
            raw = browser.new_context(viewport={"width": 412, "height": 915},
                                      device_scale_factor=2)
            rp = raw.new_page()
            rp.goto(base + "/r/privacy/", wait_until="load")
            rp.screenshot(path=os.path.join(args.shots, "unsuppressed.png"))
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
