#!/usr/bin/env python3
"""
The frame audit, ported from Tombot.

Walks every <activity> in the manifest and asserts all four requirements of
claude/frame-rule.md, plus the layout shape, following postSplashScreenTheme
where there is a splash theme.

This is the check that should have existed before the frame was ever "fixed":
everything else in the suite is structural and cannot see a whole screen being
wrong. Tombot shipped a settings screen on a stock ActionBar theme with no
edge-to-edge and no inset handling while the main screen was carefully correct,
and the review that fixed the frame never opened it.

Usage: python3 tools/verify_frame.py
Exit:  0 clean, 1 with one line per failure naming the exact cause.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(MAIN, "res")
ANDROID = "{http://schemas.android.com/apk/res/android}"

FAILURES = []


def fail(activity, msg):
    FAILURES.append("%s: %s" % (activity, msg))


def load_styles(values_dir):
    """name -> {parent, {item name: value}} for one values directory."""
    styles = {}
    path = os.path.join(RES, values_dir, "themes.xml")
    if not os.path.exists(path):
        return styles
    root = ET.parse(path).getroot()
    for style in root.findall("style"):
        name = style.get("name")
        items = {}
        for item in style.findall("item"):
            items[item.get("name")] = (item.text or "").strip()
        styles[name] = {"parent": style.get("parent", ""), "items": items}
    return styles


def resolve(styles, name, key, seen=None):
    """Look up an item, walking implicit (dotted) and explicit parents."""
    seen = seen or set()
    if not name or name in seen:
        return None
    seen.add(name)
    style = styles.get(name)
    if not style:
        return None
    if key in style["items"]:
        return style["items"][key]
    parent = style["parent"]
    if parent.startswith("@style/"):
        parent = parent[len("@style/"):]
    if parent and parent in styles:
        return resolve(styles, parent, key, seen)
    if "." in name:
        return resolve(styles, name.rsplit(".", 1)[0], key, seen)
    return None


def parent_chain(styles, name, depth=0):
    out = []
    while name and depth < 12:
        out.append(name)
        style = styles.get(name)
        if not style:
            break
        parent = style["parent"]
        if parent.startswith("@style/"):
            parent = parent[len("@style/"):]
        if not parent and "." in name:
            parent = name.rsplit(".", 1)[0]
        name = parent if parent in styles else (parent or None)
        if name and name not in styles:
            out.append(name)
            break
        depth += 1
    return out


def check_theme(activity, theme, day, night):
    """Requirement 1, in both configurations."""
    theme = theme.replace("@style/", "")

    # Follow the splash theme to the theme that is actually worn afterwards.
    post = resolve(day, theme, "postSplashScreenTheme")
    themes_to_check = [theme]
    if post:
        themes_to_check.append(post.replace("@style/", ""))

    for t in themes_to_check:
        chain = " -> ".join(parent_chain(day, t))
        if "NoActionBar" not in chain and "SplashScreen" not in chain:
            fail(activity, "theme %s is not NoActionBar (chain: %s)" % (t, chain))

        for config_name, styles in (("values", day), ("values-night", night)):
            if t not in styles and t not in day:
                continue
            table = styles if t in styles else day
            for key, want in (
                ("android:statusBarColor", "@android:color/transparent"),
                ("android:navigationBarColor", "@android:color/transparent"),
                ("android:enforceStatusBarContrast", "false"),
                ("android:enforceNavigationBarContrast", "false"),
            ):
                got = resolve(table, t, key)
                if got is None:
                    fail(activity, "%s/%s does not set %s "
                                   "(frame-rule requirement 1)" % (config_name, t, key))
                elif got != want:
                    fail(activity, "%s/%s sets %s to %r, expected %r"
                         % (config_name, t, key, got, want))


def check_source(activity, source):
    """Requirements 2 and 4."""
    if not os.path.exists(source):
        fail(activity, "source file not found: %s" % source)
        return None
    text = open(source, encoding="utf-8").read()

    if "enableEdgeToEdge()" not in text:
        fail(activity, "no enableEdgeToEdge() (frame-rule requirement 2)")
    else:
        edge = text.index("enableEdgeToEdge()")
        content = text.find("setContentView(")
        if content != -1 and edge > content:
            fail(activity, "enableEdgeToEdge() is called AFTER setContentView "
                           "(frame-rule requirement 2)")

    if not re.search(r"\bFrame\s*\(", text):
        fail(activity, "does not construct Frame (frame-rule requirement 4)")
    else:
        for arg in ("barWrapper", "barRow", "content"):
            if arg not in text:
                fail(activity, "Frame constructed without a %s argument" % arg)
        if ".install()" not in text:
            fail(activity, "Frame is constructed but install() is never called")

    # The runtime-colour rule: an activity that declares uiMode in configChanges
    # is not recreated on a day/night switch.
    if "onConfigurationChanged" not in text:
        fail(activity, "no onConfigurationChanged; colours resolved at inflate "
                       "would stay the daytime colour until the next cold start")
    if "applyBarColour" not in text:
        fail(activity, "never calls Frame.applyBarColour, so the system icon "
                       "contrast is never set from the bar's luminance")

    m = re.search(r"setContentView\(R\.layout\.(\w+)\)", text)
    return m.group(1) if m else None


def check_layout(activity, layout_name):
    """Requirement 3: the vertical stack."""
    if not layout_name:
        fail(activity, "could not determine the layout from setContentView")
        return
    path = os.path.join(RES, "layout", layout_name + ".xml")
    if not os.path.exists(path):
        fail(activity, "layout %s.xml not found" % layout_name)
        return

    root = ET.parse(path).getroot()
    tag = root.tag.split("}")[-1]
    if tag != "LinearLayout":
        fail(activity, "layout root is <%s>; requirement 3 needs a vertical "
                       "LinearLayout (a FrameLayout does not clip content out "
                       "of the status-bar strip)" % tag)
        return
    if root.get(ANDROID + "orientation") != "vertical":
        fail(activity, "layout root is not orientation=vertical")

    children = list(root)
    if not children:
        fail(activity, "layout root has no children")
        return

    first = children[0]
    if first.get(ANDROID + "id") != "@+id/topBarWrapper":
        fail(activity, "first child is not @+id/topBarWrapper; the bar must be "
                       "the FIRST child so content is a later sibling")
        return

    if first.get(ANDROID + "background") is None:
        fail(activity, "topBarWrapper has no background; an unpainted wrapper "
                       "leaves the status-bar strip transparent")

    ids = [c.get(ANDROID + "id") for c in first.iter()]
    if "@+id/topBarRow" not in ids:
        fail(activity, "topBarWrapper does not contain @+id/topBarRow")

    weighted = [c for c in children[1:]
                if c.get(ANDROID + "layout_weight") not in (None, "0")]
    if not weighted:
        fail(activity, "no later sibling with a layout_weight; content that is "
                       "not a weighted sibling below the bar can draw in the "
                       "status-bar strip")
    else:
        for c in weighted:
            if c.get(ANDROID + "layout_height") != "0dp":
                fail(activity, "weighted content has layout_height=%r, expected 0dp"
                     % c.get(ANDROID + "layout_height"))


def main():
    manifest = os.path.join(MAIN, "AndroidManifest.xml")
    tree = ET.parse(manifest)
    app = tree.getroot().find("application")

    day = load_styles("values")
    night = load_styles("values-night")

    app_theme = app.get(ANDROID + "theme", "")
    activities = app.findall("activity")
    if not activities:
        print("no activities found — nothing checked, which is itself wrong")
        return 1

    print("checking %d activities\n" % len(activities))

    for activity in activities:
        name = activity.get(ANDROID + "name")
        theme = activity.get(ANDROID + "theme") or app_theme
        pretty = name.lstrip(".")
        print("  %s (theme %s)" % (pretty, theme))

        if not theme:
            fail(pretty, "no theme, and the application sets none either")
            continue

        check_theme(pretty, theme, day, night)
        rel = name.lstrip(".").replace(".", os.sep) + ".kt"
        source = os.path.join(MAIN, "java", "com", "threadbare", "client", rel)
        layout = check_source(pretty, source)
        check_layout(pretty, layout)

    print()
    if FAILURES:
        for f in FAILURES:
            print("FAIL  %s" % f)
        print("\n%d failures" % len(FAILURES))
        return 1
    print("clean: every activity satisfies all four frame requirements")
    return 0


if __name__ == "__main__":
    sys.exit(main())
