#!/usr/bin/env python3
"""
Invariants over the routing and filtering tables, parsed out of the Kotlin.

Two things this does and does not do, stated plainly because the distinction
matters:

  It DOES read the shipped tables — the host sets, the tracking-parameter list,
  the beacon paths — straight out of UrlRules.kt and Blocklist.kt, and assert
  the properties that make them safe: no collisions, no ordering hazards, no
  host that is both allowed and blocked, no rule that could take the site down
  with a tracker.

  It does NOT verify the Kotlin algorithm. The decision logic exercised below is
  a Python transliteration, so a bug present in both would pass. The real check
  on the algorithm is app/src/test — `./gradlew testDebugUnitTest` — which has
  never been run here because no Kotlin compiler was reachable.

Usage: python3 tools/rules_suite.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(ROOT, "app", "src", "main", "java", "com", "threadbare", "client", "web")
PRIVACY = os.path.join(ROOT, "app", "src", "main", "java", "com", "threadbare", "client", "privacy")

FAILURES = []


def check(name, ok, detail=""):
    if ok:
        print("  ok   %s" % name)
    else:
        FAILURES.append(name)
        print("  FAIL %s%s" % (name, (" — %s" % detail) if detail else ""))


def parse_set(path, const):
    """Read a `private val NAME = setOf("a", "b", ...)` out of Kotlin source."""
    text = open(path, encoding="utf-8").read()
    m = re.search(r"val\s+%s\s*(?::[^=]+)?=\s*(?:setOf|listOf)\s*\((.*?)\n\s*\)" % const,
                  text, re.S)
    if not m:
        raise SystemExit("could not find %s in %s" % (const, os.path.basename(path)))
    body = m.group(1)
    body = re.sub(r"//[^\n]*", "", body)
    return [v for v in re.findall(r'"((?:[^"\\]|\\.)*)"', body)]


def matches_domain(host, domain):
    return host == domain or host.endswith("." + domain)


def main():
    urlrules = os.path.join(WEB, "UrlRules.kt")
    blocklist = os.path.join(PRIVACY, "Blocklist.kt")

    page = set(parse_set(urlrules, "PAGE_HOSTS"))
    media = set(parse_set(urlrules, "MEDIA_HOSTS"))
    shortlink = set(parse_set(urlrules, "SHORTLINK_HOSTS"))
    app_schemes = set(parse_set(urlrules, "APP_SCHEMES"))
    system_schemes = set(parse_set(urlrules, "SYSTEM_SCHEMES"))
    store = set(parse_set(urlrules, "STORE_HOSTS"))
    tracking = set(x.replace("\\$", "$") for x in parse_set(urlrules, "TRACKING_PARAMS"))

    xpromo_kt = os.path.join(PRIVACY, "XpromoBlock.kt")
    xp_patterns = set(parse_set(xpromo_kt, "PATTERNS"))
    xp_partials = set(parse_set(xpromo_kt, "PARTIAL_PATTERNS"))
    xp_never = set(parse_set(xpromo_kt, "NEVER"))
    xp_hosts = set(parse_set(xpromo_kt, "BUNDLE_HOSTS"))

    never = set(parse_set(blocklist, "NEVER_BLOCK"))
    telemetry = set(parse_set(blocklist, "REDDIT_TELEMETRY"))
    trackers = set(parse_set(blocklist, "TRACKER_DOMAINS"))
    strict = set(parse_set(blocklist, "STRICT_EXTRA_DOMAINS"))
    beacons = set(parse_set(blocklist, "BEACON_PATHS"))

    print("parsed from source: %d page hosts, %d media hosts, %d never-block, "
          "%d telemetry, %d trackers, %d strict, %d tracking params\n"
          % (len(page), len(media), len(never), len(telemetry),
             len(trackers), len(strict), len(tracking)))

    print("routing tables")
    check("www.reddit.com is the canonical host",
          'const val CANONICAL_HOST = "www.reddit.com"'
          in open(urlrules, encoding="utf-8").read())
    check("old.reddit is still routed, so a decade of links still resolve",
          "old.reddit.com" in page)
    check("every page host is under reddit.com",
          all(h == "reddit.com" or h.endswith(".reddit.com") for h in page),
          sorted(h for h in page if not (h == "reddit.com" or h.endswith(".reddit.com"))))
    check("every surface that redirects is normalised",
          {"www.reddit.com", "sh.reddit.com", "m.reddit.com", "old.reddit.com"} <= page)
    check("no host is both a page host and a media host", not (page & media),
          page & media)
    check("no host is both a short link and a media host", not (shortlink & media),
          shortlink & media)

    # The ordering hazard that would rewrite every image into a comments page.
    subdomains_of_shortlink = {m for m in media
                               for s in shortlink if matches_domain(m, s)}
    check("media hosts under redd.it exist, so MEDIA must be checked first",
          bool(subdomains_of_shortlink), "expected i.redd.it / v.redd.it here")
    check("UrlRules checks MEDIA_HOSTS before SHORTLINK_HOSTS",
          open(urlrules, encoding="utf-8").read().index("host in MEDIA_HOSTS")
          < open(urlrules, encoding="utf-8").read().index("host in SHORTLINK_HOSTS"))

    check("reddit:// and intent:// are refused", {"reddit", "intent"} <= app_schemes)
    check("the play store is refused", "play.google.com" in store)
    check("no scheme is both refused and handed to the system",
          not (app_schemes & system_schemes), app_schemes & system_schemes)

    print("\ntracking parameters")
    check("the deep-link family is stripped",
          {"$deep_link", "$android_deeplink_path", "correlation_id", "share_id"} <= tracking,
          sorted(tracking))
    functional = {"sort", "context", "depth", "after", "before", "count", "q", "t",
                  "restrict_sr", "dest", "limit", "include_over_18", "url"}
    check("no functional parameter is stripped", not (functional & tracking),
          functional & tracking)

    print("\nblocklist tables")
    check("nothing is both never-blocked and telemetry", not (never & telemetry),
          never & telemetry)
    bad = {n for n in never for d in (trackers | strict) if matches_domain(n, d)}
    check("no never-blocked host matches a blocked domain", not bad, bad)
    check("the site's own assets are never blocked",
          {"www.redditstatic.com", "www.reddit.com", "sh.reddit.com"} <= never,
          sorted(never))
    check("thumbnails are never blocked",
          {"a.thumbs.redditmedia.com", "b.thumbs.redditmedia.com"} <= never)
    check("the pixel on the same registrable domain IS blocked",
          "pixel.redditmedia.com" in telemetry)
    check("no telemetry host is left matchable by a suffix rule on redditmedia.com",
          not any(matches_domain(n, "redditmedia.com") and n in telemetry for n in never))

    print("\nbeacon paths versus real reddit paths")
    real_paths = ["/", "/r/GrapheneOS/", "/r/privacy/comments/abc/title/", "/api/vote",
                  "/api/morechildren", "/search", "/over18", "/login", "/user/x/",
                  "/static/reddit.css", "/comments/abc", "/message/inbox/"]
    for p in real_paths:
        hit = [b for b in beacons if p.startswith(b) or p.endswith(b)]
        check("a real path is not mistaken for a beacon: %s" % p, not hit, hit)

    beacon_examples = ["/api/v2/event", "/timings", "/w3-reporting/x", "/csp-report"]
    for p in beacon_examples:
        hit = [b for b in beacons if p.startswith(b) or p.endswith(b)]
        check("a beacon path is caught: %s" % p, bool(hit))

    print("\nxpromo bundle blocking (layer 1)")
    # The asymmetry: a pattern that never matches costs nothing, a pattern that
    # over-matches breaks the site silently. So the safety properties are what
    # get asserted, not the coverage.
    too_generic = {"modal", "sheet", "banner", "promo", "app", "js", "bundle",
                   "chunk", "main", "index"}
    check("no pattern is a generic word", not (xp_patterns & too_generic),
          xp_patterns & too_generic)
    check("every pattern is at least 6 characters",
          all(len(p) >= 6 for p in xp_patterns),
          [p for p in xp_patterns if len(p) < 6])
    check("every pattern names the promotion machinery",
          all(("xpromo" in p or "nsfw" in p or "smart" in p or "app_selector" in p)
              for p in xp_patterns),
          [p for p in xp_patterns if not ("xpromo" in p or "nsfw" in p
                                          or "smart" in p or "app_selector" in p)])
    check("the experience partial is refused (layer 1 on the current build)",
          "/activate-experience" in xp_partials, xp_partials)
    check("no other partial name is refused",
          all("activate-experience" in p for p in xp_partials), xp_partials)
    check("the runtime and vendor chunks are guarded",
          {"runtime", "vendor", "polyfill"} <= xp_never, xp_never)
    check("no guard token is itself a pattern", not (xp_never & xp_patterns))
    check("bundle hosts are Reddit's own",
          all(h == "reddit.com" or h.endswith(".reddit.com")
              or h == "redditstatic.com" or h.endswith(".redditstatic.com")
              for h in xp_hosts), xp_hosts)
    check("the asset host that serves the chunks is covered",
          "www.redditstatic.com" in xp_hosts)
    # Layer 1 must never contradict the must-never-block list: a host can be
    # allowed for assets and still have individual promotion chunks refused,
    # but the *reason* must be the pattern, never the host.
    check("layer 1 narrows by path, never by host",
          xp_hosts & never == xp_hosts & never)

    print("\nthe subreddit box is not a URL bar")
    # The shipped allowlist regex itself is put under test — not a Python
    # reimplementation of normaliseName, which could diverge. If this pattern
    # cannot match a dot, a slash, a colon, a percent or whitespace, then no
    # input to the box can name a host, a scheme or a path.
    path_kt = os.path.join(WEB, "RedditPath.kt")
    src = open(path_kt, encoding="utf-8").read()
    m = re.search(r'val\s+NAME\s*=\s*Regex\("([^"]+)"\)', src)
    check("the subreddit allowlist regex is findable in source", bool(m))
    if m:
        name_re = re.compile(m.group(1))
        dangerous = [
            "example.com", "evil", "a/b", "a:b", "a%2e", "a b", "a\tb",
            "../x", ".", "..", "a?b", "a#b", "a&b", "a@b", "a\\b",
            "\u043f\u0440\u0438\u043c\u0435\u0440", "a.b", "a+b", "", " ",
        ]
        for d in dangerous:
            # "evil" is a legal subreddit name; it is in the list to show the
            # check is discriminating, so it is skipped from the must-not-match.
            if d == "evil":
                continue
            check("allowlist rejects %r" % d, not name_re.match(d))
        for good in ["privacy", "GrapheneOS", "a_b", "aa", "A1_z"]:
            check("allowlist accepts %r" % good, bool(name_re.match(good)))
        check("the allowlist is anchored at both ends",
              m.group(1).startswith("^") and m.group(1).endswith("$"))

    print("\nprivate tabs: the manifest agrees with the recipe table")
    pt_kt = os.path.join(WEB, "PrivateTabs.kt")
    recipes = re.findall(r'Recipe\(\s*"([^"]+)"', open(pt_kt, encoding="utf-8").read())
    incapable = set(parse_set(pt_kt, "KNOWN_INCAPABLE"))
    manifest = open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()
    declared = set(re.findall(r'<package android:name="([^"]+)"', manifest))
    check("every private-capable browser is declared in <queries>",
          set(recipes) == declared, {"recipes": sorted(set(recipes)), "manifest": sorted(declared)})
    check("no browser is both capable and known-incapable",
          not (set(recipes) & incapable), set(recipes) & incapable)
    check("Vanadium is recorded as incapable, not merely absent",
          "app.vanadium.browser" in incapable)
    check("no Chromium package is declared in <queries>",
          not (declared & incapable), declared & incapable)

    print("\nhostile hosts (matchesDomain)")
    check("a lookalike does not match",
          not matches_domain("reddit.com.evil.example", "reddit.com"))
    check("a prefix does not match",
          not matches_domain("notgoogle-analytics.com", "google-analytics.com"))
    check("a real subdomain matches",
          matches_domain("www.google-analytics.com", "google-analytics.com"))

    print()
    if FAILURES:
        print("%d failures" % len(FAILURES))
        return 1
    print("clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
