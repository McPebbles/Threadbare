#!/usr/bin/env python3
"""
Resource audit.

Every @ref in a resource, every R.x in Kotlin, every class named in the
manifest, every preference key: does it resolve? And in the other direction: is
anything defined and never used, or left over from the template?

A missing resource is a build failure, which is cheap. The expensive one is the
opposite — a preference whose key does not match the constant Prefs reads, so
the switch moves and nothing happens.

Usage: python3 tools/verify_resources.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(MAIN, "res")
JAVA = os.path.join(MAIN, "java")
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP_NS = "{http://schemas.android.com/apk/res-auto}"

# ListPreferences whose choices come from the device rather than arrays.xml.
RUNTIME_POPULATED = {"link_browser"}

FAILURES = []
WARNINGS = []


def fail(msg):
    FAILURES.append(msg)


def warn(msg):
    WARNINGS.append(msg)


def collect_defined():
    """type -> set(names)"""
    defined = {t: set() for t in
               ("string", "color", "style", "drawable", "layout", "mipmap",
                "menu", "xml", "array", "id", "integer", "dimen", "bool")}

    for dirpath, _, names in os.walk(RES):
        folder = os.path.basename(dirpath)
        kind = folder.split("-")[0]
        for name in names:
            stem, ext = os.path.splitext(name)
            if kind in ("drawable", "layout", "mipmap", "menu", "xml"):
                defined[kind].add(stem)
            if ext != ".xml":
                continue
            path = os.path.join(dirpath, name)
            if kind == "values":
                try:
                    root = ET.parse(path).getroot()
                except ET.ParseError as e:
                    fail("%s does not parse: %s" % (os.path.relpath(path, ROOT), e))
                    continue
                for child in root:
                    tag = child.tag
                    n = child.get("name")
                    if not n:
                        continue
                    if tag == "string":
                        defined["string"].add(n)
                    elif tag == "color":
                        defined["color"].add(n)
                    elif tag == "style":
                        defined["style"].add(n)
                    elif tag in ("string-array", "integer-array", "array"):
                        defined["array"].add(n)
                    elif tag == "integer":
                        defined["integer"].add(n)
                    elif tag == "dimen":
                        defined["dimen"].add(n)
                    elif tag == "bool":
                        defined["bool"].add(n)
            else:
                # @+id/... declarations anywhere in a layout or menu
                text = open(path, encoding="utf-8").read()
                for i in re.findall(r'@\+id/(\w+)', text):
                    defined["id"].add(i)
    return defined


def check_refs(defined):
    """@type/name in every resource file."""
    android_builtin = re.compile(r'@android:')
    for dirpath, _, names in os.walk(RES):
        for name in names:
            if not name.endswith(".xml"):
                continue
            path = os.path.join(dirpath, name)
            text = open(path, encoding="utf-8").read()
            for kind, ref in re.findall(r'"@(\w+)/(\w+)"', text):
                if android_builtin.search('@%s/' % kind):
                    continue
                if kind == "id":
                    continue  # @+id declarations and @id uses both land here
                if kind in defined and ref not in defined[kind]:
                    # AppCompat and preference styles are not ours.
                    if kind == "style" and (ref.startswith("Theme.AppCompat")
                                            or ref.startswith("Preference")):
                        continue
                    fail("%s references @%s/%s which is not defined"
                         % (os.path.relpath(path, ROOT), kind, ref))
            for kind, ref in re.findall(r'"@android:(\w+)/(\w+)"', text):
                pass  # platform resources, nothing to check


def check_kotlin(defined):
    for dirpath, _, names in os.walk(JAVA):
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            text = open(path, encoding="utf-8").read()
            for kind, ref in re.findall(r'\bR\.(\w+)\.(\w+)', text):
                if kind not in defined:
                    continue
                if ref not in defined[kind]:
                    fail("%s uses R.%s.%s which is not defined"
                         % (os.path.relpath(path, ROOT), kind, ref))


def check_manifest():
    manifest = os.path.join(MAIN, "AndroidManifest.xml")
    root = ET.parse(manifest).getroot()
    app = root.find("application")
    pkg_dir = os.path.join(JAVA, "com", "threadbare", "client")

    names = [app.get(ANDROID + "name")]
    names += [a.get(ANDROID + "name") for a in app.findall("activity")]
    for n in names:
        if not n:
            continue
        rel = n.lstrip(".").replace(".", os.sep) + ".kt"
        if not os.path.exists(os.path.join(pkg_dir, rel)):
            fail("manifest names %s but %s does not exist" % (n, rel))


def check_preferences():
    """
    Preference keys and defaults must agree with Prefs.kt, in both directions.

    This is the check worth having: a mismatch here compiles, installs, and
    silently does nothing when the user flips the switch.
    """
    prefs_kt = os.path.join(JAVA, "com", "threadbare", "client", "util", "Prefs.kt")
    text = open(prefs_kt, encoding="utf-8").read()
    kotlin_keys = set(re.findall(r'const val KEY_\w+ = "(\w+)"', text))

    xml_path = os.path.join(RES, "xml", "preferences.xml")
    root = ET.parse(xml_path).getroot()
    xml_keys = set()
    for el in root.iter():
        key = el.get(APP_NS + "key") or el.get(ANDROID + "key")
        if key:
            xml_keys.add(key)

    for k in xml_keys - kotlin_keys:
        fail("preferences.xml defines key %r that Prefs.kt never reads" % k)
    for k in kotlin_keys - xml_keys - {"schema_version"}:
        warn("Prefs.kt declares key %r with no preference in preferences.xml" % k)

    # Defaults must agree, or a fresh install behaves differently from the UI.
    for el in root.iter():
        key = el.get(APP_NS + "key")
        default = el.get(APP_NS + "defaultValue")
        if not key or default is None:
            continue
        if key == "block_mode" and 'BlockMode.BALANCED.key' in text:
            continue
        pattern = r'put\w+\(KEY_[A-Z_]+,\s*(.+?)\)'
        # A loose agreement check: the default must appear somewhere in Prefs.
        token = default.strip()
        if token in ("true", "false"):
            if token not in text:
                fail("preferences.xml default %r for %s not found in Prefs.kt"
                     % (token, key))
        elif token not in text and token.strip('"') not in text:
            fail("preferences.xml default %r for %s not found in Prefs.kt"
                 % (token, key))

    # Every ListPreference's entries and entryValues must be the same length —
    # the suite has hit this before (entries/values array parity).
    #
    # A preference whose choices come from the device cannot declare them in
    # XML, so it is exempt from the arity check — but only in exchange for a
    # stricter one: the fragment must actually populate it. A ListPreference
    # with entries from neither source is an empty dialog, which is exactly the
    # kind of fault that ships because it looks fine in the tree.
    fragment = os.path.join(JAVA, "com", "threadbare", "client", "ui",
                            "SettingsFragment.kt")
    fragment_src = open(fragment, encoding="utf-8").read() if os.path.exists(fragment) else ""

    for el in root.iter():
        if not el.tag.endswith("ListPreference"):
            continue
        key = el.get(APP_NS + "key")
        entries = (el.get(APP_NS + "entries") or "").replace("@array/", "")
        values = (el.get(APP_NS + "entryValues") or "").replace("@array/", "")
        if not entries and not values:
            if key in RUNTIME_POPULATED:
                if ('"%s"' % key) not in fragment_src:
                    fail("ListPreference %s is declared runtime-populated but "
                         "SettingsFragment never names it" % key)
                elif ".entries =" not in fragment_src or ".entryValues =" not in fragment_src:
                    fail("ListPreference %s is runtime-populated but "
                         "SettingsFragment sets no entries/entryValues" % key)
                continue
            fail("ListPreference %s declares no entries; if that is deliberate, "
                 "add it to RUNTIME_POPULATED in this script" % key)
            continue
        if not entries or not values:
            fail("ListPreference %s is missing entries or entryValues" % key)
            continue
        counts = {}
        arrays_path = os.path.join(RES, "values", "arrays.xml")
        arrays_root = ET.parse(arrays_path).getroot()
        for arr in arrays_root.findall("string-array"):
            counts[arr.get("name")] = len(arr.findall("item"))
        if counts.get(entries) != counts.get(values):
            fail("array parity: %s has %s items but %s has %s"
                 % (entries, counts.get(entries), values, counts.get(values)))


def strip_comments(text, path):
    """
    Blank out comments so the leftover scan reads code, not prose.

    Needed because the commentary in this app deliberately cites the other apps
    in the suite — that is where the lessons came from — and those citations are
    not leftovers. A first version of this check flagged all of them, which is
    the kind of noisy audit people learn to ignore.
    """
    if path.endswith((".xml",)):
        return re.sub(r"<!--.*?-->", lambda m: " " * len(m.group(0)), text, flags=re.S)
    if path.endswith((".css",)):
        return re.sub(r"/\*.*?\*/", lambda m: " " * len(m.group(0)), text, flags=re.S)
    if path.endswith(".kt"):
        text = re.sub(r"/\*.*?\*/", lambda m: " " * len(m.group(0)), text, flags=re.S)
        return re.sub(r"//[^\n]*", lambda m: " " * len(m.group(0)), text)
    return text


def check_start_page_hosts():
    """
    Any Reddit URL baked into a default or a ListPreference must be on the
    canonical host, and every default must be one of its own entryValues.

    Written after 1.1.0 shipped with `start_page` still pointing at old.reddit
    for anyone upgrading: the entryValues had moved to www, the stored value had
    not, and the app opened on a login wall. A stale URL in either place is now
    a build-time failure rather than something the user discovers on launch.
    """
    canonical = "www.reddit.com"
    stale = re.compile(r"https://((?:old|sh|m|np|amp|new|i|en|ssl|pay)\.reddit\.com|reddit\.com)/")

    prefs_kt = os.path.join(JAVA, "com", "threadbare", "client", "util", "Prefs.kt")
    arrays = os.path.join(RES, "values", "arrays.xml")
    prefs_xml = os.path.join(RES, "xml", "preferences.xml")

    for path in (prefs_kt, arrays, prefs_xml):
        if not os.path.exists(path):
            continue
        text = strip_comments(open(path, encoding="utf-8").read(), os.path.basename(path))
        for m in stale.finditer(text):
            line = text[:m.start()].count("\n") + 1
            fail("%s:%d has a non-canonical Reddit URL %r; the canonical host is %s"
                 % (os.path.relpath(path, ROOT), line, m.group(0), canonical))

    # A default that is not among its own entryValues shows as a blank setting.
    root = ET.parse(prefs_xml).getroot()
    arrays_root = ET.parse(arrays).getroot()
    values = {a.get("name"): [i.text for i in a.findall("item")]
              for a in arrays_root.findall("string-array")}
    for el in root.iter():
        if not el.tag.endswith("ListPreference"):
            continue
        if el.get(APP_NS + "key") in RUNTIME_POPULATED:
            continue
        default = el.get(APP_NS + "defaultValue")
        entry_values = (el.get(APP_NS + "entryValues") or "").replace("@array/", "")
        if default is None or entry_values not in values:
            continue
        if default not in values[entry_values]:
            fail("ListPreference %s defaults to %r which is not in @array/%s"
                 % (el.get(APP_NS + "key"), default, entry_values))


def check_leftovers():
    """Nothing from the template should have survived as an identifier."""
    bad = ("tombot", "tastywrap", "supplychain", "spoticap", "timhortons",
           "Tombot", "TastyWrap", "SupplyChain", "Spoticap")
    seen = set()
    for base in (RES, JAVA, os.path.join(MAIN, "assets")):
        if not os.path.isdir(base):
            continue
        for dirpath, _, names in os.walk(base):
            for name in names:
                path = os.path.abspath(os.path.join(dirpath, name))
                if path in seen or not name.endswith((".xml", ".kt", ".css")):
                    continue
                seen.add(path)
                code = strip_comments(open(path, encoding="utf-8").read(), name)
                for token in bad:
                    for m in re.finditer(re.escape(token), code):
                        line = code[:m.start()].count("\n") + 1
                        ctx = code.splitlines()[line - 1].strip()[:70]
                        fail("%s:%d still refers to %s: %s"
                             % (os.path.relpath(path, ROOT), line, token, ctx))


def main():
    defined = collect_defined()
    check_refs(defined)
    check_kotlin(defined)
    check_manifest()
    check_preferences()
    check_start_page_hosts()
    check_leftovers()

    print("defined: %s" % ", ".join(
        "%d %s" % (len(v), k) for k, v in sorted(defined.items()) if v))
    for w in WARNINGS:
        print("warn  %s" % w)
    if FAILURES:
        for f in FAILURES:
            print("FAIL  %s" % f)
        print("\n%d failures" % len(FAILURES))
        return 1
    print("clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
