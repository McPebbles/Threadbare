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
     * What this deliberately does NOT do any more is touch content. Removing
     * a `<shreddit-blurred-container>`'s state at document-start is what made
     * adult-flagged posts open blank, and it is now [ADULT_REVEALER]'s job,
     * under its own setting. This script only removes prompts.
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
    fun xpromoSuppressor(acceptAgeGate: Boolean): String = """
        (function () {
          'use strict';

          // Whether to answer Reddit's 18+ confirmation rather than delete it.
          // Baked in from the setting rather than read off a global: a global
          // is something the page could look for.
          var ACCEPT_AGE_GATE = ${acceptAgeGate};

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

          // Post content. Nothing this script removes may contain any of it.
          var CONTENT = [
            'shreddit-post',
            'shreddit-player',
            'shreddit-blurred-container',
            '[slot="post-media-container"]',
            '[slot="text-body"]',
            '[data-aspect-ratio-container]',
            'shreddit-comment',
            '[slot="revealed"]'
          ].join(',');

          /**
           * Would removing this take the post with it?
           *
           * The rule this enforces: **removing a prompt must never remove
           * content.** Three releases in a row, an adult-flagged post opened as
           * a black box with nothing in it, and the suppressor turned out to be
           * the only setting that made the difference. Whatever the offending
           * element is called on a given day, if the post is inside it then
           * deleting it is the wrong move — close the dialog it carries and
           * hide that, and leave the rest of the subtree alone.
           */
          function holdsContent(node) {
            try { return !!(node.querySelector && node.querySelector(CONTENT)); }
            catch (e) { return false; }
          }

          /** Hide the dialog hosts inside a subtree without taking the subtree. */
          function defuse(node) {
            closeDialogsWithin(node, 0);
            var hosts;
            try {
              hosts = node.querySelectorAll(
                'rpl-dialog, rpl-bottom-sheet, [dialog-classname*="configured-xpromo"], dialog');
            } catch (e) { hosts = []; }
            for (var i = 0; i < hosts.length; i++) {
              if (holdsContent(hosts[i])) continue;
              try { hosts[i].remove(); } catch (e) {}
            }
          }

          function removeAll(root, selector) {
            var found;
            try { found = root.querySelectorAll(selector); } catch (e) { return 0; }
            var removed = 0;
            for (var i = 0; i < found.length; i++) {
              closeDialogsWithin(found[i], 0);
              if (holdsContent(found[i]) || holdsAgeGate(found[i])) {
                // Either the prompt is wrapped around the post, or it carries
                // the confirmation that unlocks it. Both are reasons to keep it
                // and take only the dialog.
                if (holdsAgeGate(found[i])) continue;
                defuse(found[i]);
                continue;
              }
              try { found[i].remove(); removed++; } catch (e) {}
            }
            return removed;
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
              if (holdsAgeGate(hosts[i])) continue;
              closeDialogsWithin(hosts[i], 0);
              if (holdsContent(hosts[i])) continue;
              try { hosts[i].remove(); } catch (e) {}
            }
          }

          // ------------------------------------------------- the 18+ confirmation
          //
          // Reddit's "Yes, I'm Over 18" button, from the captured markup, does
          // FOUR things — and only the first is a cookie:
          //
          //   <ac-set-cookie name="over18" value="true">
          //   <ac-track san="xpromo|dismiss|bypassable_xpromo_nsfw_bypassable">
          //   <ac-gql-mutate operation="StoreUxtargetingAction"
          //       variables='{"action":"DISMISS","eligibleExperience":
          //                   {"experienceName":"bypassable_xpromo_nsfw_bypassable"}}'>
          //   <ac-call method="location.reload" target="window">
          //
          // The mutation is server-side state against the reader's loid, and
          // **that** is what makes Reddit serve the post's media on the next
          // load. A seeded cookie cannot reproduce it. Which is why an
          // adult-flagged post opened as an empty frame for six versions: the
          // app was deleting the one control that unlocks the content, and no
          // amount of work on the page could conjure media the server had not
          // sent.
          //
          // So this presses it. Same principle as the reveal: press what a
          // reader would press, and let the site do its own work.
          var ACCEPT_KEY = 'tb-age-accepted';
          var ACCEPT_LIMIT = 2;

          function acceptCount() {
            try { return parseInt(sessionStorage.getItem(ACCEPT_KEY) || '0', 10) || 0; }
            catch (e) { return 0; }
          }
          function noteAccept() {
            try { sessionStorage.setItem(ACCEPT_KEY, String(acceptCount() + 1)); } catch (e) {}
          }

          /** The accept control, wherever it is, including inside shadow roots. */
          function findAccept(node, depth) {
            if (!node || depth > 8) return null;
            var marker;
            try { marker = node.querySelector ? node.querySelector('ac-set-cookie[name="over18"]') : null; }
            catch (e) { marker = null; }
            if (marker) {
              var button = marker.closest ? marker.closest('button, a') : null;
              if (button) return button;
            }
            var all;
            try { all = node.querySelectorAll ? node.querySelectorAll('*') : []; } catch (e) { all = []; }
            for (var i = 0; i < all.length; i++) {
              if (all[i].shadowRoot) {
                var found = findAccept(all[i].shadowRoot, depth + 1);
                if (found) return found;
              }
            }
            return null;
          }

          /**
           * True when this subtree carries the confirmation, so the removal
           * pass must leave it alone until it has been pressed. Deleting it is
           * how the app locked itself out of the content.
           */
          function holdsAgeGate(node) {
            if (!ACCEPT_AGE_GATE || acceptCount() >= ACCEPT_LIMIT) return false;
            return !!findAccept(node, 0);
          }

          /** Returns true when it pressed something, in which case a reload follows. */
          function acceptAgeGate() {
            if (!ACCEPT_AGE_GATE) return false;
            // Reddit's own handler reloads the page. Without a cap, a server
            // that keeps serving the experience would have the app reloading
            // for ever; twice is enough to tell "it worked" from "it did not".
            if (acceptCount() >= ACCEPT_LIMIT) return false;
            var button = findAccept(document, 0);
            if (!button) return false;
            noteAccept();
            try { button.click(); } catch (e) { return false; }
            return true;
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
                // Same rule: a container named for the block may be wrapped
                // around the thing being blocked.
                if (!holdsContent(host)) host.remove();
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

          function sweep() {
            // Before anything is removed: if the page is offering the 18+
            // confirmation, press it. A reload follows, and the page that comes
            // back is the one with the post in it.
            if (acceptAgeGate()) {
              // Reddit reloads the page from here, so usually nothing after
              // this matters. If it does not — a press that went nowhere —
              // nothing else would change either, and a sweep that only runs on
              // mutation would never look again. So: one check back.
              setTimeout(schedule, 800);
              return;
            }
            removeAll(document, KILL);
            closeStrayXpromoDialogs();
            pierceShadow();
            dropBackdrops();
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
                attributeFilter: ['class', 'style', 'bundlename', 'paint-group', 'open', 'dialog-classname']
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
     * Un-gating adult-flagged content — separate script, separate setting.
     *
     * ## What went wrong twice before this
     *
     * Reddit wraps adult-flagged media and self-text in
     * `<shreddit-blurred-container>`: two light-DOM children — `slot="blurred"`
     * (a server-blurred placeholder) and `slot="revealed"` (the real thing) —
     * and a shadow root that renders one `<slot>` or the other. 1.4.0 cleared
     * the host's `blurred` attribute; 1.5.0 also renamed the rendered slot.
     * Both worked against a captured DOM with no scripts, and both left post
     * pages **blank on device** — the user's report, twice.
     *
     * The difference between the capture and the device is that on the device
     * Reddit's own JavaScript is running. Revealing at document-start changes
     * the component's state before it has upgraded and before the lazy media
     * loader inside `slot="revealed"` has anything to load, so the reveal wins
     * the race and produces an empty box. Pressing the component's own button
     * later — what the user does by hand, and what works — does not.
     *
     * ## So this script asks rather than reaches
     *
     * 1. **Wait until the page has settled** (`load`, with a timeout for pages
     *    that never fire it). Nothing here runs at document-start.
     * 2. **Press the component's own "View NSFW content" button**, once the
     *    element has upgraded. That is Reddit's own code path, with whatever
     *    else it does — telling the player to load, in particular — intact.
     * 3. **Verify.** A reveal that did not produce laid-out content is not a
     *    reveal. Only then does it fall back to editing the DOM directly, and
     *    that fallback exists for the case where pressing cannot work at all:
     *    the definition never arrives, so the button is inert.
     * 4. **Put the button back** if neither worked. A blank box with no way to
     *    tap through is strictly worse than a blurred box with one, and that
     *    regression is exactly what 1.5.0 shipped.
     *
     * No cookie is written and no request is made: the button's own handler in
     * the captured markup sets nothing and fetches nothing.
     *
     * `reason="spoiler"` is left alone throughout. A spoiler is the poster's
     * blur, not Reddit's age gate.
     */
    val ADULT_REVEALER: String = """
        (function () {
          'use strict';

          // Light-DOM blurs, which are ordinary elements with a filter on them
          // and nothing to press. These are safe to clear immediately.
          var UNBLUR = [
            '.thumbnail-blur',
            'span.inner.blurred',
            'img.blurred',
            'video.blur'
          ].join(',');

          var COVERS = ['.thumbnail-shadow', '.bg-scrim'].join(',');

          var HOSTS = 'shreddit-blurred-container, [embed-obscured]';

          // How long the component is given to arrive before the app gives up
          // on pressing and reaches into the DOM instead; how long a press is
          // given to produce something; and how long after the fallback before
          // the button goes back. Generous throughout: a component bundle and
          // a video player fetching themselves on a phone are not instant, and
          // every one of this feature's bugs has been the app being too quick.
          var DEFINE_MS = 3000;
          var PRESS_MS = 1200;
          var PATCH_MS = 2500;

          var state = (typeof WeakMap === 'function') ? new WeakMap() : null;
          var settled = false;

          function now() { return Date.now ? Date.now() : +new Date(); }

          function isAgeBlur(host) {
            var reason = '';
            try { reason = (host.getAttribute('reason') || '').toLowerCase(); } catch (e) {}
            return !(reason.indexOf('spoiler') !== -1 && reason.indexOf('nsfw') === -1);
          }

          function gated(host) {
            try {
              if (host.hasAttribute('blurred') || host.hasAttribute('embed-obscured')) return true;
              var root = host.shadowRoot;
              return !!(root && (root.querySelector('slot[name="blurred"]') ||
                                 root.querySelector('.inner.blurred')));
            } catch (e) { return false; }
          }

          /**
           * Laid out, not merely present: the test the last two versions failed.
           *
           * The slotted wrapper's own box is not enough — Reddit's wrapper for
           * post media holds an absolutely positioned child and measures zero
           * itself while the media fills the frame. So the tallest thing inside
           * it counts, which is what the reader actually sees.
           */
          function revealedBox(host) {
            var child;
            try { child = host.querySelector('[slot="revealed"]'); } catch (e) { return 0; }
            if (!child || !child.assignedSlot) return 0;
            var best = 0;
            try { best = child.getBoundingClientRect().height; } catch (e) {}
            if (best > 0) return Math.round(best);
            var kids;
            try { kids = child.querySelectorAll('*'); } catch (e) { kids = []; }
            for (var i = 0; i < kids.length && i < 24; i++) {
              try {
                var h = kids[i].getBoundingClientRect().height;
                if (h > best) best = h;
              } catch (e) {}
            }
            return Math.round(best);
          }

          function upgraded(host) {
            try {
              if (typeof customElements === 'undefined') return false;
              var name = (host.tagName || '').toLowerCase();
              if (name && name.indexOf('-') !== -1 && !customElements.get(name)) return false;
              // An upgraded element is no longer a plain HTMLElement.
              return Object.getPrototypeOf(host) !== HTMLElement.prototype;
            } catch (e) { return false; }
          }

          function press(host) {
            var root = host.shadowRoot;
            if (!root) return false;
            var button;
            try { button = root.querySelector('.overlay button, button'); } catch (e) { return false; }
            if (!button) return false;
            try { button.click(); return true; } catch (e) { return false; }
          }

          function setCovers(root, display) {
            var covers;
            try { covers = root.querySelectorAll('.overlay, .bg-scrim'); } catch (e) { return; }
            for (var i = 0; i < covers.length; i++) {
              try {
                covers[i].style.display = display;
                covers[i].style.pointerEvents = display === 'none' ? 'none' : '';
              } catch (e) {}
            }
          }

          /**
           * The fallback, for a component that never upgraded and so has no
           * working button. Attributes and inline styles only — nothing is
           * removed from the shadow tree, because lit hydrates against the
           * marker comments in it.
           */
          function patch(host) {
            var root = host.shadowRoot;
            for (var a = 0, keys = ['blurred', 'embed-obscured', 'obscured']; a < keys.length; a++) {
              try { host.removeAttribute(keys[a]); } catch (e) {}
            }
            if (!root) return;
            var hasRevealed = false;
            try { hasRevealed = !!host.querySelector('[slot="revealed"]'); } catch (e) {}
            if (hasRevealed) {
              var slots;
              try { slots = root.querySelectorAll('slot[name="blurred"]'); } catch (e) { slots = []; }
              var st = stateFor(host);
              for (var s = 0; s < slots.length; s++) {
                try {
                  slots[s].setAttribute('name', 'revealed');
                  if (st) st.renamed.push(slots[s]);
                } catch (e) {}
              }
            }
            var inner;
            try { inner = root.querySelectorAll('.inner, .blurred'); } catch (e) { inner = []; }
            for (var i = 0; i < inner.length; i++) {
              try {
                inner[i].classList.remove('blurred');
                inner[i].style.filter = 'none';
                inner[i].style.webkitFilter = 'none';
                if (inner[i].getAttribute('aria-hidden') === 'true') {
                  inner[i].setAttribute('aria-hidden', 'false');
                }
              } catch (e) {}
            }
            // The blurred state clamps the box to the placeholder's height
            // (h-[88px] on a self-text post), which would crop what it has
            // just revealed. Only a fixed pixel height is touched.
            var outer;
            try { outer = root.querySelectorAll('.outer'); } catch (e) { outer = []; }
            for (var o = 0; o < outer.length; o++) {
              try {
                if (/h-\[\d+px\]/.test(outer[o].className || '')) {
                  outer[o].style.height = 'auto';
                  outer[o].style.maxHeight = 'none';
                }
              } catch (e) {}
            }
          }

          /**
           * Put the container back the way Reddit built it.
           *
           * Swapping the rendered slot also takes away the blurred placeholder,
           * so a fallback that reveals nothing leaves an empty frame — worse
           * than the blur it replaced. If neither route produced content, every
           * edit is rolled back: the placeholder returns and so does the button.
           */
          function undo(host) {
            var st = stateFor(host);
            if (st) {
              for (var i = 0; i < st.renamed.length; i++) {
                try { st.renamed[i].setAttribute('name', 'blurred'); } catch (e) {}
              }
              st.renamed = [];
            }
            if (host.shadowRoot) setCovers(host.shadowRoot, '');
          }

          function stateFor(host) {
            if (!state) return null;
            var st = state.get(host);
            if (!st) {
              st = {
                waitingSince: 0, pressedAt: 0, patchedAt: 0, renamed: [],
                presses: 0, done: false, gaveUp: false,
                doneAt: 0, hatched: 0, hatchedAll: false, filledOnce: false, fillPasses: 0,
              };
              state.set(host, st);
            }
            return st;
          }

          // ------------------------------------------------ deferred embeds
          //
          // From a device report (1.8.1): an adult-flagged post whose media is
          // a third-party embed. After the reveal, the frame held
          //
          //   <shreddit-embed providername="hgifs" data-embed-obscured-deferred
          //       html='<iframe src="https://www.hgifs.com/ifr/…" …>'>
          //
          // with NO iframe rendered anywhere, and a "View in app" button in its
          // place. The server sent the content — the provider's iframe markup
          // is right there in the attribute — and the component declined to
          // instantiate it. Third-party adult embeds on mobile web, logged out,
          // are handed to the app instead. That is the promotion machinery
          // again, and there is nothing to press: the only button opens an app
          // store.
          //
          // So this does what the component would have done: makes the iframe
          // from the `src` Reddit itself supplied. Only the src is taken —
          // the attribute's HTML is never injected — and only over https.
          var EMBED_GRACE_MS = 1500;
          var hatched = (typeof WeakSet === 'function') ? new WeakSet() : null;

          function embedsWithin(node, acc, depth) {
            if (!node || depth > 10) return acc;
            var found;
            try { found = node.querySelectorAll ? node.querySelectorAll('shreddit-embed') : []; }
            catch (e) { found = []; }
            for (var i = 0; i < found.length; i++) acc.push(found[i]);
            var all;
            try { all = node.querySelectorAll ? node.querySelectorAll('*') : []; } catch (e) { all = []; }
            for (var j = 0; j < all.length; j++) {
              if (all[j].shadowRoot) embedsWithin(all[j].shadowRoot, acc, depth + 1);
            }
            return acc;
          }

          function frameFor(embed) {
            try { return embed.closest ? embed.closest('[data-aspect-ratio-container]') : null; }
            catch (e) { return null; }
          }

          function hasIframe(embed) {
            try {
              if (embed.querySelector('iframe')) return true;
              if (embed.shadowRoot && embed.shadowRoot.querySelector('iframe')) return true;
              var next = embed.nextElementSibling;
              if (next && next.getAttribute && next.getAttribute('data-tb-embed')) return true;
              var frame = frameFor(embed);
              return !!(frame && frame.querySelector('[data-tb-embed]'));
            } catch (e) { return false; }
          }

          // ---- sizing, for the feed's half-black boxes
          //
          // Reddit's media frame carries an `aspect-ratio` chosen for the
          // placeholder, and its own embed component corrects it to the media's
          // shape when it renders. A hatched iframe did not, so a landscape gif
          // in a square frame played in the top half and left the rest black.
          // The ratio is taken from the iframe markup's own width/height when
          // they are numbers, else from the placeholder image Reddit chose for
          // the post, which has the media's shape.
          function ratioFromMarkup(html) {
            // A percentage is not a dimension: width="100%" says nothing about
            // the media's shape, and must not read as 100.
            var w = /<iframe\b[^>]*\bwidth=["']?(\d+)(?:px)?["']?(?=[\s>\/])/i.exec(html);
            var h = /<iframe\b[^>]*\bheight=["']?(\d+)(?:px)?["']?(?=[\s>\/])/i.exec(html);
            if (w && h && +w[1] > 0 && +h[1] > 0) return (+w[1]) / (+h[1]);
            return 0;
          }
          function ratioFromPlaceholder(frame) {
            if (!frame) return 0;
            var imgs;
            try { imgs = frame.querySelectorAll('img'); } catch (e) { return 0; }
            var best = 0, bestArea = 0;
            for (var i = 0; i < imgs.length; i++) {
              var nw = imgs[i].naturalWidth, nh = imgs[i].naturalHeight;
              if (nw > 0 && nh > 0 && nw * nh > bestArea) { bestArea = nw * nh; best = nw / nh; }
            }
            return best;
          }
          function fitFrame(frame, ratio) {
            if (!frame || !(ratio > 0)) return;
            var r;
            try { r = frame.getBoundingClientRect(); } catch (e) { return; }
            var current = (r.width > 0 && r.height > 0) ? r.width / r.height : 0;
            // Only when it is actually wrong. A frame already the right shape
            // is Reddit's layout and is left alone.
            if (current > 0 && Math.abs(current - ratio) / ratio < 0.12) return;
            try {
              frame.style.aspectRatio = String(ratio);
              frame.style.minHeight = '0';
            } catch (e) {}
          }

          function embedSrc(embed) {
            var html = '';
            try { html = embed.getAttribute('html') || ''; } catch (e) { return null; }
            var m = /<iframe\b[^>]*\bsrc=["']([^"']+)["']/i.exec(html);
            if (!m) return null;
            var src = m[1].replace(/&amp;/g, '\u0026');
            if (!/^https:\/\//i.test(src)) return null;
            return src;
          }

          function hatch(embed) {
            if (hatched && hatched.has(embed)) return false;
            var src = embedSrc(embed);
            if (!src) return false;
            var frame;
            try {
              frame = document.createElement('iframe');
              frame.setAttribute('data-tb-embed', '1');
              frame.setAttribute('src', src);
              frame.setAttribute('frameborder', '0');
              frame.setAttribute('scrolling', 'no');
              frame.setAttribute('allowfullscreen', '');
              frame.setAttribute('allow', 'fullscreen; autoplay');
              frame.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
              frame.style.cssText =
                'position:absolute;inset:0;width:100%;height:100%;border:0;background:#000;z-index:2;';
              // Into Reddit's own media frame when there is one — the element
              // whose size is the size the post is meant to be — else beside
              // the component. Never into a shadow tree lit owns, and the
              // component is only hidden, never removed.
              var box = frameFor(embed);
              if (box) box.appendChild(frame);
              else embed.parentNode.insertBefore(frame, embed.nextSibling);
              embed.style.display = 'none';
              var ratio = ratioFromMarkup(embed.getAttribute('html') || '') || ratioFromPlaceholder(box);
              fitFrame(box, ratio);
            } catch (e) { return false; }
            if (hatched) hatched.add(embed);
            return true;
          }

          // ---- the feed's 150px strip
          //
          // From a feed report (1.9.1): Reddit renders its own embed iframe in
          // a feed, `position:absolute; height:100%`, inside a wrapper
          // `<div class="relative">` that has no height of its own. 100% of
          // nothing is nothing, so the iframe falls back to an iframe's
          // intrinsic 150px — a strip across the top of a 379px frame, black
          // beneath. The wrapper is the iframe's containing block; give it the
          // frame's height and the iframe fills the frame.
          function iframeWithin(node, depth) {
            if (!node || depth > 10) return null;
            var f;
            try { f = node.querySelector ? node.querySelector('iframe') : null; } catch (e) { f = null; }
            if (f) return f;
            // The starting element's own shadow root, not only its
            // descendants': Reddit's embed keeps its iframe in exactly that.
            if (node.shadowRoot) {
              f = iframeWithin(node.shadowRoot, depth + 1);
              if (f) return f;
            }
            var all;
            try { all = node.querySelectorAll ? node.querySelectorAll('*') : []; } catch (e) { all = []; }
            for (var i = 0; i < all.length; i++) {
              if (all[i].shadowRoot) {
                var found = iframeWithin(all[i].shadowRoot, depth + 1);
                if (found) return found;
              }
            }
            return null;
          }

          /** The nearest positioned ancestor, crossing shadow boundaries. */
          function containingBlock(el, stopAt) {
            var node = el;
            for (var i = 0; i < 40 && node; i++) {
              node = node.parentNode;
              if (node && node.nodeType === 11) node = node.host;   // out of a shadow root
              if (!node || node === stopAt || node.nodeType !== 1) return node === stopAt ? stopAt : null;
              try { if (getComputedStyle(node).position !== 'static') return node; } catch (e) {}
            }
            return null;
          }

          /** Returns true when the iframe now fills its frame. */
          function fillFrame(embed, frame, st) {
            var iframe = iframeWithin(embed, 0);
            if (!iframe || !frame) return true;
            var fr, ir;
            try { fr = frame.getBoundingClientRect(); ir = iframe.getBoundingClientRect(); } catch (e) { return true; }
            if (fr.height <= 0) return false;              // not laid out yet
            if (ir.height >= fr.height - 4) return true;   // already fine
            var cb = containingBlock(iframe, frame);
            if (cb && cb !== frame) {
              try {
                // Percent first, so it follows the frame; pixels only if the
                // percent had nothing to resolve against.
                if (!st.filledOnce) { cb.style.height = '100%'; cb.style.minHeight = '100%'; }
                else { cb.style.height = Math.round(fr.height) + 'px'; }
              } catch (e) {}
            }
            try {
              if (iframe.style) {
                if (!iframe.style.height || iframe.style.height === '150px') iframe.style.height = '100%';
                if (!iframe.style.width) iframe.style.width = '100%';
              }
            } catch (e) {}
            st.filledOnce = true;
            return false;
          }

          /**
           * Once a container is revealed: give its embed a moment, then help —
           * with an iframe if it has none, with a size if it has one.
           */
          function hatchEmbeds(host, st) {
            if (st.hatchedAll) return;
            if (!st.doneAt) { st.doneAt = now(); return; }
            if (now() - st.doneAt < EMBED_GRACE_MS) return;
            var embeds = embedsWithin(host, [], 0);
            var settled = true;
            for (var i = 0; i < embeds.length; i++) {
              var embed = embeds[i];
              var frame = frameFor(embed);
              if (!hasIframe(embed)) {
                if (hatch(embed)) st.hatched = (st.hatched || 0) + 1;
                continue;
              }
              // Reddit's own iframe. Make it fill the frame, and give the frame
              // the media's shape as the post page gets.
              if (!fillFrame(embed, frame, st)) settled = false;
              var ratio = ratioFromMarkup(embed.getAttribute('html') || '') || ratioFromPlaceholder(frame);
              fitFrame(frame, ratio);
            }
            // Two passes at most for the sizing: the second confirms the first
            // took, or falls back to pixels. Then this container is finished.
            st.fillPasses = (st.fillPasses || 0) + 1;
            st.hatchedAll = settled || st.fillPasses >= 2;
          }

          /**
           * One container, one small step per pass.
           *
           * The order is the whole point: ask (press), then check, then reach
           * in, then check again, then put everything back. Each version that
           * skipped straight to reaching in shipped a blank post page.
           */
          function step(host) {
            if (!isAgeBlur(host)) return;
            var st = stateFor(host);
            if (!st || st.gaveUp) return;

            var t = now();
            var box = revealedBox(host);

            // Revealed, and something is actually there. Now — and only now —
            // is it safe to take the tap target away.
            if (box > 0 && !gated(host)) {
              if (!st.done && host.shadowRoot) setCovers(host.shadowRoot, 'none');
              st.done = true;
              hatchEmbeds(host, st);
              return;
            }

            // The fallback has run and produced nothing. Give it a moment, then
            // hand the page back rather than leaving an empty frame.
            if (st.patchedAt) {
              if (t - st.patchedAt > PATCH_MS) {
                undo(host);
                st.gaveUp = true;
              }
              return;
            }

            if (!gated(host)) return;

            if (!st.pressedAt) {
              if (!upgraded(host)) {
                // No definition YET. Reaching in now is the 1.5.0 mistake in a
                // new costume: Reddit loads component bundles lazily, so an
                // element that has not upgraded by the time the page settles
                // usually still will, and clearing its state first means it
                // upgrades already-revealed and never tells the media to load.
                // Wait for it, and only fall back when nothing is coming.
                if (!st.waitingSince) st.waitingSince = t;
                if (t - st.waitingSince < DEFINE_MS) return;
                patch(host);
                st.patchedAt = t;
                return;
              }
              if (press(host)) { st.pressedAt = t; st.presses++; }
              return;
            }

            if (t - st.pressedAt < PRESS_MS) return;
            if (st.presses < 2 && press(host)) { st.pressedAt = t; st.presses++; return; }
            patch(host);
            st.patchedAt = t;
          }

          function plainBlurs() {
            var found;
            try { found = document.querySelectorAll(UNBLUR); } catch (e) { found = []; }
            for (var i = 0; i < found.length; i++) {
              var el = found[i];
              try {
                if (el.closest && el.closest('shreddit-blurred-container')) continue;
                el.style.filter = 'none';
                el.style.webkitFilter = 'none';
                el.classList.remove('thumbnail-blur', 'blurred', 'blur');
              } catch (e) {}
            }
            var covers;
            try { covers = document.querySelectorAll(COVERS); } catch (e) { covers = []; }
            for (var j = 0; j < covers.length; j++) {
              try { covers[j].style.display = 'none'; } catch (e) {}
            }
          }

          /**
           * What this script did to each container, for the diagnostics menu.
           *
           * Exposed on `window` ONLY when the diagnostics switch is on: a
           * global this app defines is a thing a page could look for, and the
           * default has to be that Reddit cannot tell this app from a browser.
           */
          function report() {
            var hosts;
            try { hosts = document.querySelectorAll(HOSTS); } catch (e) { hosts = []; }
            var out = {
              settled: settled,
              readyState: document.readyState,
              defined: (typeof customElements !== 'undefined' &&
                        !!customElements.get('shreddit-blurred-container')),
              containers: []
            };
            for (var i = 0; i < hosts.length; i++) {
              var host = hosts[i];
              var st = state ? state.get(host) : null;
              var root = host.shadowRoot;
              var slot = null, child = null;
              try {
                var s = root ? root.querySelector('slot') : null;
                slot = s ? (s.getAttribute('name') || '(unnamed)') : null;
              } catch (e) {}
              try { child = host.querySelector('[slot="revealed"]'); } catch (e) {}
              out.containers.push({
                mode: host.getAttribute('mode'),
                reason: host.getAttribute('reason'),
                gated: gated(host),
                upgraded: upgraded(host),
                shadow: !!root,
                renderedSlot: slot,
                revealedChild: !!child,
                revealedChildTag: (child && child.firstElementChild)
                  ? child.firstElementChild.tagName.toLowerCase() : null,
                revealedHeight: revealedBox(host),
                presses: st ? st.presses : 0,
                patched: !!(st && st.patchedAt),
                gaveUp: !!(st && st.gaveUp),
                done: !!(st && st.done),
                embedsHatched: st ? (st.hatched || 0) : 0
              });
            }
            return out;
          }

          try { if (window.__tbDiag) window.__tbReveal = report; } catch (e) {}

          /** Returns true while any container is still waiting on a step. */
          function sweep() {
            plainBlurs();
            if (!settled) return false;
            var hosts;
            try { hosts = document.querySelectorAll(HOSTS); } catch (e) { return false; }
            var pending = false;
            for (var i = 0; i < hosts.length; i++) {
              try {
                step(hosts[i]);
                var st = stateFor(hosts[i]);
                if (st && !st.gaveUp && isAgeBlur(hosts[i]) && (!st.done || !st.hatchedAll)) pending = true;
              } catch (e) {}
            }
            return pending;
          }

          // Passes are scheduled on mutation, and while work is outstanding on
          // a slow timer as well, because the press-verify-fall-back steps are
          // driven by elapsed time rather than by the page changing. The timer
          // stops itself as soon as every container has resolved, so an
          // ordinary page pays for a few hundred milliseconds of ticking and
          // nothing after that.
          var queued = false;
          var ticking = false;
          // A feed can hold thirty gated containers, most below the fold with
          // loaders that will not run until scrolled to. Ticking for those for
          // ever would be a background cost on every page; so the timer runs
          // for a while after the last mutation and then stops, and the next
          // mutation — a scroll bringing a loader to life — re-arms it.
          var TICK_FOR_MS = 20000;
          var tickUntil = 0;

          function schedule() {
            tickUntil = now() + TICK_FOR_MS;
            if (queued) return;
            queued = true;
            var run = function () {
              queued = false;
              var pending = sweep();
              if (pending && !ticking && now() < tickUntil) {
                ticking = true;
                setTimeout(function () {
                  ticking = false;
                  if (now() < tickUntil) { queued = false; run(); }
                }, 400);
              }
            };
            if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run);
            else setTimeout(run, 16);
          }

          var observer = new MutationObserver(schedule);
          function start() {
            if (!document.documentElement) return false;
            try {
              observer.observe(document.documentElement, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['blurred', 'embed-obscured', 'class', 'slot']
              });
            } catch (e) { return false; }
            sweep();
            return true;
          }

          if (!start()) {
            var boot = new MutationObserver(function () { if (start()) boot.disconnect(); });
            try { boot.observe(document, { childList: true, subtree: true }); } catch (e) {}
          }

          // A definition arriving is the event this is mostly waiting on, so
          // wake on it rather than only on the timer.
          try {
            if (typeof customElements !== 'undefined' && customElements.whenDefined) {
              customElements.whenDefined('shreddit-blurred-container').then(schedule, function () {});
            }
          } catch (e) {}

          function settle() { settled = true; schedule(); }
          if (document.readyState === 'complete') settle();
          else window.addEventListener('load', settle, { once: true });
          // A page whose media never finishes loading never fires load, and
          // waiting forever would be its own bug.
          setTimeout(settle, 6000);
        })();
    """.trimIndent()

    /**
     * A stylesheet's delivery. Used for both sheets; [id] keeps them apart
     * and makes each injection idempotent on its own.
     *
     * Appended to `document.documentElement`, NOT to `<head>` — at
     * document-start time `<head>` may not have been parsed yet, and a style
     * element is honoured wherever it sits in the document. That removes the
     * race entirely and means the rules are live before first paint, so an
     * overlay never flashes on its way to being hidden.
     */
    fun styleInjector(id: String, css: String): String = """
        (function () {
          'use strict';
          var CSS = ${jsString(css)};
          var ID = ${jsString(id)};
          function addStyle() {
            if (document.getElementById(ID)) return true;
            var root = document.documentElement;
            if (!root) return false;
            var style = document.createElement('style');
            style.id = ID;
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
     * Turns the diagnostic surface on, injected before the other scripts and
     * only when the diagnostics switch is on.
     *
     * One global, set deliberately. A page that can see `window.__tbReveal`
     * can tell it is being read in this app, which is not something to leave
     * switched on by default in a client whose premise is anonymity.
     */
    val DIAGNOSTICS_FLAG: String = """
        (function () { try { window.__tbDiag = true; } catch (e) {} })();
    """.trimIndent()

    /**
     * What the app believes about the adult-content gate on this page.
     *
     * Run on demand from the overflow, not injected. It answers what three
     * rounds of inference could not: are there any blurred containers at all,
     * did the component upgrade, was the button pressed, is there anything in
     * the revealed slot — and, separately, what is left in the post's media
     * frame. **No containers and an empty frame means the media was never in
     * the page**, and no client-side work will conjure it.
     */
    val REVEAL_REPORT: String = """
        (function () {
          var out = { url: location.href, readyState: document.readyState, containersFound: 0 };
          try {
            out.reveal = (typeof window.__tbReveal === 'function')
              ? window.__tbReveal() : 'revealer not running (setting off?)';
          } catch (e) { out.reveal = 'error: ' + e; }
          try {
            out.containersFound = document.querySelectorAll('shreddit-blurred-container').length;
          } catch (e) {}

          // THE post, not whichever one happens to be first in the document.
          // An earlier version of this report picked the first media frame on
          // the page and described a recommended post instead of the one being
          // read, which cost a round.
          function postIdFromUrl() {
            var m = /\/comments\/([a-z0-9]+)/i.exec(location.pathname);
            return m ? 't3_' + m[1] : null;
          }
          // Through shadow roots, because that is where an embed, a consent
          // card or a click-to-play overlay lives — and a light-DOM count of
          // players and images says nothing about any of them.
          function deep(root, acc, depth) {
            if (!root || depth > 10 || acc.nodes > 4000) return acc;
            var all;
            try { all = root.querySelectorAll ? root.querySelectorAll('*') : []; } catch (e) { return acc; }
            for (var i = 0; i < all.length; i++) {
              var el = all[i];
              acc.nodes++;
              var tag = el.tagName.toLowerCase();
              if (tag.indexOf('-') !== -1 || tag === 'iframe' || tag === 'video' ||
                  tag === 'button' || tag === 'dialog') {
                acc.tags[tag] = (acc.tags[tag] || 0) + 1;
              }
              if (tag === 'iframe') {
                var rr = el.getBoundingClientRect();
                var src = el.getAttribute('src') || el.getAttribute('data-src') || '';
                var host = '';
                try { host = src ? new URL(src, location.href).host : '(no src)'; } catch (e) { host = src.slice(0, 40); }
                acc.iframes.push({
                  host: host, w: Math.round(rr.width), h: Math.round(rr.height),
                  display: getComputedStyle(el).display,
                  sandbox: el.getAttribute('sandbox')
                });
              }
              if (tag === 'shreddit-embed' || tag === 'shreddit-embed-consent' ||
                  tag.indexOf('embed') !== -1 || tag.indexOf('consent') !== -1) {
                var attrs = {};
                for (var a = 0; a < el.attributes.length && a < 12; a++) {
                  var at = el.attributes[a];
                  if (at.name === 'html') { attrs.html = at.value.slice(0, 200); continue; }
                  attrs[at.name] = at.value.slice(0, 80);
                }
                acc.embeds.push({ tag: tag, attrs: attrs });
              }
              if (tag === 'button' || tag === 'a') {
                var label = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60);
                if (label) acc.buttons.push(label);
              }
              if (el.shadowRoot) deep(el.shadowRoot, acc, depth + 1);
            }
            return acc;
          }

          function frameOf(post) {
            if (!post) return null;
            var frame = post.querySelector('[slot="post-media-container"]') ||
                        post.querySelector('[data-aspect-ratio-container]');
            if (!frame) return null;
            var r = frame.getBoundingClientRect();
            var acc = deep(frame, { nodes: 0, tags: {}, iframes: [], embeds: [], buttons: [] }, 0);
            var text = '';
            try { text = (frame.innerText || frame.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 300); } catch (e) {}
            return {
              height: Math.round(r.height),
              blurredContainers: frame.querySelectorAll('shreddit-blurred-container').length,
              players: frame.querySelectorAll('shreddit-player, video').length,
              images: frame.querySelectorAll('img').length,
              loaders: (function () {
                var names = [], l = frame.querySelectorAll('shreddit-async-loader');
                for (var i = 0; i < l.length; i++) {
                  names.push((l[i].getAttribute('bundlename') || '?') + '/' +
                             (l[i].getAttribute('loading') || 'eager'));
                }
                return names;
              })(),
              deepTags: acc.tags,
              iframes: acc.iframes,
              embeds: acc.embeds,
              buttons: acc.buttons.slice(0, 12),
              visibleText: text,
              html: frame.innerHTML.replace(/\s+/g, ' ').slice(0, 400)
            };
          }
          try {
            var wanted = postIdFromUrl();
            var main = wanted ? document.getElementById(wanted) : null;
            if (!main) {
              var posts = document.querySelectorAll('shreddit-post');
              main = posts.length ? posts[0] : null;
            }
            out.mainPost = main ? {
              id: main.id,
              matchesUrl: !!wanted && main.id === wanted,
              postType: main.getAttribute('post-type'),
              domain: main.getAttribute('domain'),
              contentHref: main.getAttribute('content-href'),
              nsfw: main.hasAttribute('nsfw'),
              removed: main.hasAttribute('removed') || main.hasAttribute('deleted'),
              frame: frameOf(main)
            } : 'no shreddit-post element for ' + wanted;
          } catch (e) { out.mainPost = 'error: ' + e; }

          // Everything else on the page, so a recommendation is never mistaken
          // for the post being read.
          try {
            var others = [], all = document.querySelectorAll('shreddit-post');
            for (var i = 0; i < all.length && i < 12; i++) {
              var post = all[i];
              var line = post.id + ':' + (post.getAttribute('post-type') || '?') +
                         ':' + (post.getAttribute('domain') || '');
              var fr = post.querySelector('[data-aspect-ratio-container]');
              if (fr) {
                var fb = fr.getBoundingClientRect();
                line += ' frame=' + Math.round(fb.width) + 'x' + Math.round(fb.height) +
                        ' ar=' + (getComputedStyle(fr).aspectRatio || '?');
                var tb = fr.querySelector('[data-tb-embed]');
                if (tb) {
                  var ib = tb.getBoundingClientRect();
                  line += ' hatched=' + Math.round(ib.width) + 'x' + Math.round(ib.height);
                }
                var im = fr.querySelector('img');
                if (im && im.naturalWidth) line += ' placeholder=' + im.naturalWidth + 'x' + im.naturalHeight;
              }
              others.push(line);
            }
            out.postsOnPage = others;
          } catch (e) {}

          try {
            out.xpromo = {
              modals: document.querySelectorAll('configured-xpromo-modal').length,
              nsfwContainers: document.querySelectorAll('xpromo-nsfw-blocking-container').length,
              openDialogs: document.querySelectorAll('dialog[open]').length,
              // How many times this session pressed "Yes, I'm Over 18".
              ageGatePresses: (function () {
                try { return sessionStorage.getItem('tb-age-accepted') || '0'; } catch (e) { return '?'; }
              })(),
              consentPartials: document.querySelectorAll('faceplate-partial[name^="DataProtectionConsent"]').length
            };
          } catch (e) {}
          try { return JSON.stringify(out, null, 1); } catch (e) { return 'report failed: ' + e; }
        })();
    """.trimIndent()

    /**
     * The page as this WebView actually has it, shadow roots included.
     *
     * Every fix for the adult-content gate so far was derived from a DOM
     * captured in a browser, and the app's page turned out not to be that page.
     * `documentElement.outerHTML` would not do: it omits shadow roots, and they
     * are where the whole mechanism lives. Script bodies are dropped — megabytes
     * of Reddit's bundle that nobody reading the dump needs.
     *
     * Session identifiers are scrubbed before the string leaves the page. This
     * file is meant to be sent to someone, and `loid` identifies the reader
     * even when logged out.
     */
    val PAGE_DUMP: String = """
        (function () {
          var VOID = {
            AREA: 1, BASE: 1, BR: 1, COL: 1, EMBED: 1, HR: 1, IMG: 1, INPUT: 1,
            LINK: 1, META: 1, PARAM: 1, SOURCE: 1, TRACK: 1, WBR: 1
          };
          var AMP = String.fromCharCode(38);
          function esc(s) {
            return String(s).split(AMP).join(AMP + 'amp;')
                            .split('<').join(AMP + 'lt;')
                            .split('>').join(AMP + 'gt;');
          }
          function attrs(el) {
            var out = '';
            for (var i = 0; i < el.attributes.length; i++) {
              var a = el.attributes[i];
              out += ' ' + a.name + '="' + esc(a.value).split('"').join(AMP + 'quot;') + '"';
            }
            return out;
          }
          function ser(node, depth) {
            if (!node || depth > 60) return '';
            if (node.nodeType === 3) return esc(node.nodeValue);
            if (node.nodeType === 8) return '<!--' + String(node.nodeValue) + '-->';
            if (node.nodeType !== 1) return '';
            var tag = node.tagName.toLowerCase();
            if (tag === 'script') return '<script' + attrs(node) + '></' + 'script>';
            var out = '<' + tag + attrs(node) + '>';
            if (VOID[node.tagName]) return out;
            if (node.shadowRoot) {
              out += '<template shadowrootmode="open">';
              var sk = node.shadowRoot.childNodes;
              for (var s = 0; s < sk.length; s++) out += ser(sk[s], depth + 1);
              out += '</template>';
            }
            var kids = (tag === 'template' && node.content)
              ? node.content.childNodes : node.childNodes;
            for (var i = 0; i < kids.length; i++) out += ser(kids[i], depth + 1);
            return out + '</' + tag + '>';
          }
          var html;
          try { html = '<!doctype html>' + ser(document.documentElement, 0); }
          catch (e) { return 'dump failed: ' + e; }
          function blank(re) {
            html = html.replace(re, function (m, lead) { return (lead || '') + 'scrubbed'; });
          }
          blank(/(loid=")[^"]*/g);
          blank(/(correlation-id=")[^"]*/g);
          blank(/(serverrenderid=")[^"]*/g);
          blank(/(sig=)[A-Za-z0-9_.-]{16,}/g);
          blank(/(session[-_]?(?:id|tracker)=)[^"' ]{8,}/gi);
          return html;
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
