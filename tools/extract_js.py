#!/usr/bin/env python3
"""
Pull the injected JavaScript out of the Kotlin source, exactly as shipped.

The point is that the verification suite tests the *shipped* strings rather
than a copy that can drift. Kotlin interpolations are replaced with concrete
stand-ins so the result is runnable JS.

Usage:  python3 tools/extract_js.py [outdir]
Writes: <outdir>/{desktop_shim,skin,over18_fallback,privacy_signals}.js
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "threadbare", "client", "web")

# Stand-ins for the Kotlin interpolations, chosen to be representative.
SUBSTITUTIONS = {
    "${jsString(major)}": '"140"',
    "${jsString(css)}": None,  # filled from the real stylesheet
}


def raw_blocks(path):
    """Yield (name, body) for every triple-quoted Kotlin raw string."""
    text = open(path, encoding="utf-8").read()
    out = []
    for m in re.finditer(r'(?:val|fun)\s+(\w+)[^\n]*?"""(.*?)"""', text, re.S):
        out.append((m.group(1), m.group(2)))
    return out


def substitute(body, css):
    body = body.replace("${jsString(css)}", js_string(css))
    for needle, value in SUBSTITUTIONS.items():
        if value is not None:
            body = body.replace(needle, str(value))
    return body


def js_string(s):
    """Mirror SiteScripts.jsString so the escaping under test is the real one."""
    out = ['"']
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ch == " ":
            out.append("\\u2028")
        elif ch == " ":
            out.append("\\u2029")
        elif ch == "<":
            out.append("\\u003C")
        elif ch == ">":
            out.append("\\u003E")
        elif ch == "&":
            out.append("\\u0026")
        elif ord(ch) < 32:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "js")
    os.makedirs(outdir, exist_ok=True)

    css_path = os.path.join(ROOT, "app", "src", "main", "assets", "xpromo-suppress.css")
    css = open(css_path, encoding="utf-8").read()

    wanted = {
        "desktopShim": "desktop_shim.js",
        "suppressorStyle": "suppressor_style.js",
        "XPROMO_SUPPRESSOR": "xpromo_suppressor.js",
        "script": "privacy_signals.js",
    }

    found = {}
    for filename in ("SiteScripts.kt", "PrivacySignals.kt"):
        for name, body in raw_blocks(os.path.join(SRC, filename)):
            if name in wanted:
                found[name] = substitute(body, css)

    missing = set(wanted) - set(found)
    if missing:
        print("MISSING raw string blocks: %s" % sorted(missing))
        return 1

    for name, filename in wanted.items():
        path = os.path.join(outdir, filename)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(found[name])
        print("wrote %s (%d bytes)" % (filename, len(found[name])))

    # Leftover interpolation is a bug: it means a template changed and this
    # extractor did not keep up, and the JS under test would not be the JS
    # that ships.
    bad = [n for n, b in found.items() if "${" in b]
    if bad:
        print("FAIL: unsubstituted Kotlin interpolation in %s" % bad)
        return 1

    print("no unsubstituted interpolations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
