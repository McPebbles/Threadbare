package com.threadbare.client.web

/**
 * Everything injected into the page, as document-start scripts.
 *
 * There is no `@JavascriptInterface` anywhere in this app. Nothing here calls
 * back into Kotlin; the scripts only shape the page. That keeps the bridge
 * surface at zero, which for a client whose premise is anonymity is worth more
 * than the convenience a bridge would buy.
 *
 * Kept free of `android.*` so the script bodies can be run through `node` and a
 * real Chromium in the verification suite.
 */
object SiteScripts {

    /**
     * Layer 3 of the xpromo defence: the observer.
     *
     * Layers 1 (blocking the bundle request) and 2 (the document-start
     * stylesheet) do the structural work. This exists for the two jobs neither
     * of them can do:
     *
     *   1. **The modal dialog.** The overlay Reddit ships (a captured DOM,
     *      September 2026) is `<configured-xpromo-modal>` wrapping an
     *      `<rpl-bottom-sheet>` or `<rpl-dialog>`, and those open a native
     *      `<dialog>` with `showModal()`. That puts it in the top layer and makes
     *      the rest of the document *inert* — which is why "nothing is
     *      clickable" while it is up. Inertness survives `display: none`. Only
     *      closing the dialog or removing it from the document ends it, and the
     *      dialog lives in a shadow root, so this has to be script.
     *   2. **Shadow roots generally.** An injected stylesheet does not cross
     *      the boundary, so prompts rendered inside one are reached here.
     *   3. **The scroll lock.** CSS forces the overflow back open, but a class
     *      left on `<body>` can still affect other behaviour, so it is removed.
     *
     * Design constraints, because this is the part that can misbehave:
     *
     * - rAF-coalesced. A MutationObserver on a Lit application fires constantly;
     *   doing the work synchronously per mutation would make scrolling stutter.
     * - Idempotent and cheap on the common path: the first thing each pass does
     *   is a single `querySelector`, and it returns immediately when there is
     *   nothing to do, which is almost always.
     * - Self-disabling. When `shreddit-app` is gone the observer disconnects
     *   rather than running for the life of a page it no longer understands.
     * - No `setInterval`. The scripts this was derived from poll every 300ms for
     *   ten seconds; an observer fires when something actually changes and costs
     *   nothing when nothing does.
     */
    val XPROMO_SUPPRESSOR: String = """
        (function () {
          'use strict';

          // The first five are what a captured DOM (September 2026) actually
          // contains: a <configured-xpromo-modal> wrapping an <rpl-bottom-sheet>
          // (app promo) or <rpl-dialog> (18+ wall). Everything after them came
          // from community userscripts and matched nothing on that build; kept
          // for older markup, at no cost.
          var KILL = [
            'configured-xpromo-modal',
            'rpl-dialog[dialog-classname*="configured-xpromo"]',
            'rpl-bottom-sheet[dialog-classname*="configured-xpromo"]',
            '[dialog-id^="configured-xpromo"]',
            'faceplate-partial[name^="ActivateExperience"] > configured-xpromo-modal',
            'shreddit-async-loader[paint-group="xpromo"]',
            'shreddit-async-loader[bundlename*="xpromo"]',
            'shreddit-async-loader[bundlename*="nsfw_blocking"]',
            'xpromo-bottom-sheet',
            'xpromo-bottom-bar',
            'xpromo-app-selector',
            'smart-banner',
            '#xpromo-bottom-sheet',
            '#blocking-modal',
            '#nsfw-qr-dialog',
            '[id*="xpromo_nsfw_blocking"]',
            '.XPromoPopupRpl',
            '.XPromoBottomBar'
          ].join(',');

          var SCROLL_LOCK_CLASSES = ['rpl-scroll-lock', 'scroll-disabled'];

          var UNBLUR = [
            '.thumbnail-blur',
            'span.inner.blurred',
            'img.blurred',
            'video.blur',
            '.thumbnail-shadow',
            '.bg-scrim'
          ].join(',');

          // The rpl components open a native <dialog> with showModal(), which
          // puts it in the top layer and makes the rest of the document inert.
          // That is what "nothing is clickable" is. display:none does not undo
          // inertness; close() does, and so does removing the dialog from the
          // document. Both are done, because a dialog inside a shadow root is
          // only reachable by walking shadow roots.
          function closeDialogsWithin(node, depth) {
            if (!node || depth > 8) return;
            var dialogs;
            try {
              dialogs = node.querySelectorAll ? node.querySelectorAll('dialog') : [];
            } catch (e) { dialogs = []; }
            for (var i = 0; i < dialogs.length; i++) {
              try { if (dialogs[i].open) dialogs[i].close(); } catch (e) {}
              try { dialogs[i].removeAttribute('open'); } catch (e) {}
            }
            var all;
            try { all = node.querySelectorAll ? node.querySelectorAll('*') : []; } catch (e) { all = []; }
            for (var j = 0; j < all.length; j++) {
              if (all[j].shadowRoot) closeDialogsWithin(all[j].shadowRoot, depth + 1);
            }
            if (node.shadowRoot) closeDialogsWithin(node.shadowRoot, depth + 1);
          }

          function removeAll(root, selector) {
            var found;
            try { found = root.querySelectorAll(selector); } catch (e) { return 0; }
            for (var i = 0; i < found.length; i++) {
              closeDialogsWithin(found[i], 0);
              try { found[i].remove(); } catch (e) {}
            }
            return found.length;
          }

          // Belt and braces: any modal dialog still open anywhere after the
          // sweep, in light DOM or shadow, that belongs to the promotion
          // family. Scoped by the host chain so an ordinary dialog — a share
          // sheet, a report form — is left alone.
          function closeStrayXpromoDialogs() {
            var hosts;
            try { hosts = document.querySelectorAll('[dialog-classname*="configured-xpromo"], [id^="configured-xpromo"]'); }
            catch (e) { return; }
            for (var i = 0; i < hosts.length; i++) {
              closeDialogsWithin(hosts[i], 0);
              try { hosts[i].remove(); } catch (e) {}
            }
          }

          function freeScroll() {
            var body = document.body;
            if (!body) return;
            for (var i = 0; i < SCROLL_LOCK_CLASSES.length; i++) {
              if (body.classList.contains(SCROLL_LOCK_CLASSES[i])) {
                try { body.classList.remove(SCROLL_LOCK_CLASSES[i]); } catch (e) {}
              }
            }
            // The lock is also applied as inline style, which outranks a
            // stylesheet rule without !important and is cheapest to just clear.
            var roots = [document.documentElement, body];
            for (var r = 0; r < roots.length; r++) {
              var el = roots[r];
              if (!el || !el.style) continue;
              if (el.style.overflow === 'hidden') el.style.overflow = '';
              if (el.style.overflowY === 'hidden') el.style.overflowY = '';
              if (el.style.position === 'fixed') el.style.position = '';
            }
          }

          function pierceShadow() {
            // The NSFW prompt lives inside a shadow root, where an injected
            // stylesheet cannot reach it. This is the one case that genuinely
            // requires script.
            var hosts;
            try {
              hosts = document.querySelectorAll('xpromo-nsfw-blocking-container');
            } catch (e) { return; }
            for (var i = 0; i < hosts.length; i++) {
              var host = hosts[i];
              try {
                if (host.shadowRoot) {
                  removeAll(host.shadowRoot, '.prompt');
                }
                host.remove();
              } catch (e) {}
            }
          }

          function dropBackdrops() {
            var divs;
            try { divs = document.querySelectorAll('div[style]'); } catch (e) { return; }
            for (var i = 0; i < divs.length; i++) {
              var s = divs[i].style;
              if (!s) continue;
              var filter = s.backdropFilter || s.webkitBackdropFilter || '';
              // Only a fixed, full-viewport blur is a takeover backdrop. A
              // blurred thumbnail is also backdrop-filtered and must survive.
              if (filter && s.position === 'fixed') {
                try { divs[i].remove(); } catch (e) {}
              }
            }
          }

          function unblur() {
            var found;
            try { found = document.querySelectorAll(UNBLUR); } catch (e) { return; }
            for (var i = 0; i < found.length; i++) {
              var el = found[i];
              try {
                if (el.classList.contains('thumbnail-shadow') ||
                    el.classList.contains('bg-scrim')) {
                  el.remove();
                  continue;
                }
                el.style.filter = 'none';
                el.style.webkitFilter = 'none';
                el.classList.remove('thumbnail-blur', 'blurred', 'blur');
              } catch (e) {}
            }
            var flagged;
            try { flagged = document.querySelectorAll('[blurred]'); } catch (e) { return; }
            for (var j = 0; j < flagged.length; j++) {
              try { flagged[j].removeAttribute('blurred'); } catch (e) {}
            }
          }

          function sweep() {
            removeAll(document, KILL);
            closeStrayXpromoDialogs();
            pierceShadow();
            dropBackdrops();
            unblur();
            freeScroll();
          }

          var queued = false;
          function schedule() {
            if (queued) return;
            queued = true;
            var run = function () {
              queued = false;
              sweep();
              // Once the app element is gone there is nothing left to watch.
              if (!document.querySelector('shreddit-app') && document.body) {
                try { observer.disconnect(); } catch (e) {}
              }
            };
            if (typeof requestAnimationFrame === 'function') {
              requestAnimationFrame(run);
            } else {
              setTimeout(run, 16);
            }
          }

          var observer = new MutationObserver(schedule);

          function start() {
            if (!document.documentElement) return false;
            try {
              observer.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['class', 'style', 'blurred', 'bundlename', 'paint-group', 'open', 'dialog-classname']
              });
            } catch (e) { return false; }
            sweep();
            return true;
          }

          if (!start()) {
            // document-start can run before <html> exists.
            var boot = new MutationObserver(function () {
              if (start()) boot.disconnect();
            });
            try { boot.observe(document, { childList: true, subtree: true }); } catch (e) {}
          }

          document.addEventListener('DOMContentLoaded', sweep, { once: true });
        })();
    """.trimIndent()

    /**
     * Layer 2's delivery: the stylesheet.
     *
     * Appended to `document.documentElement`, NOT to `<head>` — at
     * document-start time `<head>` may not have been parsed yet, and a style
     * element is honoured wherever it sits in the document. That removes the
     * race entirely and means the rules are live before first paint, so an
     * overlay never flashes on its way to being hidden.
     */
    fun suppressorStyle(css: String): String = """
        (function () {
          'use strict';
          var CSS = ${jsString(css)};
          function addStyle() {
            if (document.getElementById('tb-suppress')) return true;
            var root = document.documentElement;
            if (!root) return false;
            var style = document.createElement('style');
            style.id = 'tb-suppress';
            style.textContent = CSS;
            root.appendChild(style);
            return true;
          }
          if (!addStyle()) {
            var obs = new MutationObserver(function () {
              if (addStyle()) obs.disconnect();
            });
            try { obs.observe(document, { childList: true, subtree: true }); } catch (e) {}
            setTimeout(function () { try { obs.disconnect(); } catch (e) {} }, 10000);
          }
          // A document that replaces documentElement wholesale would drop the
          // style element. One re-check, not a sweep.
          document.addEventListener('DOMContentLoaded', addStyle, { once: true });
        })();
    """.trimIndent()

    /**
     * The manual escape hatch, off by default.
     *
     * xpromo is cross-promotion to the mobile app, so a desktop client should
     * never be served any of it. That makes "request desktop site" the thing to
     * reach for when a future Reddit build outruns layers 1–3, and it is the
     * reason this app has a UA setting at all.
     *
     * The UA string alone is not enough: client hints are not derived from it,
     * so `navigator.userAgentData`, `platform` and `vendor` are shimmed to
     * agree. That lesson is SupplyChain's, recorded in the project docs.
     *
     * Touch is deliberately NOT spoofed. Reddit's layout does branch on pointer
     * capability, and claiming no touch on a phone produces a page that expects
     * hover. [major] is read from the engine at runtime; a hard-coded version
     * would itself become a fingerprint as it went stale.
     */
    fun desktopShim(major: String): String = """
        (function () {
          'use strict';
          var MAJOR = ${jsString(major)};
          function def(obj, name, value) {
            try {
              Object.defineProperty(obj, name, {
                get: function () { return value; },
                configurable: true,
                enumerable: true
              });
            } catch (e) { /* a frozen navigator is not fatal */ }
          }
          var brands = [
            { brand: 'Chromium', version: MAJOR },
            { brand: 'Google Chrome', version: MAJOR },
            { brand: 'Not_A Brand', version: '24' }
          ];
          var uaData = {
            brands: brands,
            mobile: false,
            platform: 'Windows',
            getHighEntropyValues: function (hints) {
              var full = {
                architecture: 'x86',
                bitness: '64',
                brands: brands,
                fullVersionList: brands,
                mobile: false,
                model: '',
                platform: 'Windows',
                platformVersion: '15.0.0',
                uaFullVersion: MAJOR + '.0.0.0',
                wow64: false
              };
              var out = {};
              (hints || []).forEach(function (h) { if (h in full) out[h] = full[h]; });
              return Promise.resolve(out);
            },
            toJSON: function () {
              return { brands: brands, mobile: false, platform: 'Windows' };
            }
          };
          def(navigator, 'userAgentData', uaData);
          def(navigator, 'platform', 'Win32');
          def(navigator, 'vendor', 'Google Inc.');
        })();
    """.trimIndent()

    /**
     * Read the Chrome major version out of the WebView's real UA, so a spoof
     * never disagrees with the engine actually running.
     */
    fun majorVersionOf(realUserAgent: String?, fallback: String = "140"): String {
        val m = Regex("Chrome/(\\d+)").find(realUserAgent.orEmpty()) ?: return fallback
        return m.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: fallback
    }

    fun desktopUserAgent(major: String): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

    /** JSON-safe string literal, including the separators JS treats as breaks. */
    fun jsString(s: String): String {
        val out = StringBuilder(s.length + 16)
        out.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                '<' -> out.append("\\u003C")
                '>' -> out.append("\\u003E")
                '&' -> out.append("\\u0026")
                else -> if (ch < ' ') {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}
