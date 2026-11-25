"""Check inbox navigation and the document actually saved by Android's system picker."""
from datetime import datetime
from pathlib import Path
import runpy
import json
import re
import shlex
import time
from uuid import UUID


def reveal_setting(adb, fixture, title):
    for _ in range(6):
        tree = fixture["read_tree"](adb)
        if any(node.get("text") == title for node in tree.iter("node")):
            return tree
        scroll = next((n for n in tree.iter("node") if n.get("scrollable") == "true"), None)
        if scroll is not None:
            left, top, right, bottom = fixture["bounds"](scroll)
            x = (left + right) // 2
            height = bottom - top
            adb("shell", "input", "swipe", str(x), str(bottom - height // 6),
                str(x), str(top + height // 6), "275")
    raise AssertionError("Could not find setting " + title)


def open_database_settings(adb, element, fixture):
    element("content-desc", "More options", tap=True)
    element("text", "Settings", tap=True)
    reveal_setting(adb, fixture, "Database")
    element("text", "Database", tap=True)


def debug_toggle_state(tree):
    # A preference's title and platform switch occupy separate children of the same row.
    for row in reversed(list(tree.iter("node"))):
        children = list(row.iter("node"))
        if any(c.get("text") == "Debug mode" for c in children):
            check = next((c for c in children if c.get("checkable") == "true"), None)
            if check is not None:
                return check.get("checked") == "true"
    raise AssertionError("Debug mode has no accessible switch")


def rapid_tab_switch(adb, fixture):
    tree = fixture["read_tree"](adb)
    positions = {}
    for label in ("Home", "Messages", "Other"):
        tab = next(n for n in tree.iter("node") if n.get("text") == label)
        left, top, right, bottom = fixture["bounds"](tab)
        positions[label] = ((left + right) // 2, (top + bottom) // 2)
    # Send consecutive gestures without waiting for accessibility to settle between tabs.
    order = ("Messages", "Other", "Messages", "Home", "Other", "Messages")
    adb("shell", " && ".join("input tap %d %d" % positions[tab] for tab in order))


def assert_messages_settled(adb, fixture):
    deadline = time.monotonic() + 15
    prefix = fixture["PACKAGE"] + ":id/"
    while time.monotonic() < deadline:
        nodes = list(fixture["read_tree"](adb).iter("node"))
        previews = [n.get("text", "") for n in nodes if n.get("resource-id") == prefix + "message"]
        busy = any(n.get("resource-id") in (prefix + "inbox_loading", prefix + "inbox_retry") for n in nodes)
        if (fixture["LAST_MESSAGE"] in previews and not busy and
                all(fixture["JUNK"] not in text and fixture["PRIVATE_TEXT"] not in text for text in previews)):
            return
    raise AssertionError("Messages did not settle with the expected conversation and folder contents")


def exported_files(adb):
    # Files created in the emulator's local DocumentsUI roots are under shared storage.
    listing = adb("shell", "find /storage/emulated/0 -maxdepth 5 -type f -name 'voipms-sms-debug-*' 2>/dev/null; true")
    return set(listing.splitlines())


def check_export(contents, fixture):
    records = [json.loads(line) for line in contents.splitlines()]
    if not records or records[0].get("type") != "header" or records[0].get("schema") != 1:
        raise AssertionError("Missing diagnostic schema header")
    allowed = {"type", "time", "session", "uptimeMs", "action", "stage", "operation", "count", "elapsedMs", "error"}
    stages = set()
    operations = {}
    for event in records[1:]:
        if event.get("type") != "event" or set(event) - allowed:
            raise AssertionError("Unexpected diagnostic record or unreviewed field")
        if "error" in event and set(event["error"]) - {"class", "frames"}:
            raise AssertionError("Unexpected exception details in the exported report")
        for key in ("action", "stage"):
            if not re.fullmatch(r"[A-Z][A-Z0-9_]*", event.get(key, "")):
                raise AssertionError("Invalid diagnostic event " + key)
        timestamp = event.get("time", "")
        if not timestamp.endswith("Z"):
            raise AssertionError("Expected a UTC diagnostic timestamp")
        datetime.fromisoformat(timestamp[:-1])
        if event.get("operation") is not None:
            operation = UUID(event["operation"])
            operations.setdefault((event["action"], operation), []).append(event["stage"])
        stages.add((event["action"], event["stage"]))
    for action in ("INBOX_LOAD", "THREAD_LOAD"):
        phases = ("QUERYING", "RENDERING", "COMPLETED")
        if not any(key[0] == action and tuple(s for s in steps if s in phases) == phases
                   for key, steps in operations.items()):
            raise AssertionError("Missing completed operation for " + action)
    if not {("NAVIGATION", "MESSAGES"), ("DEBUG_EXPORT", "WRITING")} <= stages:
        raise AssertionError("Missing navigation or document-writing diagnostics")
    secrets = [fixture[k] for k in ("FIXTURE_EMAIL", "DID", "CONTACT", "PRIVATE_PEER",
                                     "LAST_MESSAGE", "JUNK", "PRIVATE_TEXT")]
    secrets += ["offline-fixture", "Studio Fixture", "example.invalid", "(416) 555-0111"]
    if any(s in contents for s in secrets) or re.search(r"(?<![0-9a-f])" + fixture["CODE"] + r"(?![0-9a-f])", contents, re.I):
        raise AssertionError("The exported report contains private fixture data")


def assert_journal_cleared(adb, package):
    private_files = adb("shell", "run-as", package, "find", "no_backup", "-type", "f")
    if any(path.startswith("no_backup/debug-journal/") for path in private_files.splitlines()):
        raise AssertionError("The app retained diagnostic records while capture was off")


def run(adb, element, fixture):
    fixture["launch_home"](adb)
    element("text", "Call activity")
    open_database_settings(adb, element, fixture)
    if debug_toggle_state(reveal_setting(adb, fixture, "Debug mode")):
        raise AssertionError("Debug recording started before opt-in")
    assert_journal_cleared(adb, fixture["PACKAGE"])
    element("text", "Debug mode", tap=True)
    if not debug_toggle_state(reveal_setting(adb, fixture, "Debug mode")):
        raise AssertionError("Debug recording could not be enabled")
    fixture["launch_home"](adb)
    element("text", "Call activity")
    rapid_tab_switch(adb, fixture)
    assert_messages_settled(adb, fixture)
    element("text", fixture["LAST_MESSAGE"], tap=True)
    element("text", "Yes, ten works for me.")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    assert_messages_settled(adb, fixture)
    thread = runpy.run_path(Path(__file__).with_name("android-thread-loading.py"))
    thread["run"](adb, element, fixture)

    open_database_settings(adb, element, fixture)
    reveal_setting(adb, fixture, "Export debug log")
    before = exported_files(adb)
    element("text", "Export debug log", tap=True)
    element("package", ("com.android.documentsui", "com.google.android.documentsui"))
    element("text", ("Save", "SAVE"), tap=True)
    deadline = time.monotonic() + 15
    previous = None
    while time.monotonic() < deadline:
        created = exported_files(adb) - before
        if len(created) == 1:
            path = next(iter(created))
            contents = adb("exec-out", "cat", shlex.quote(path))
            if contents and contents == previous:
                check_export(contents, fixture)
                break
            previous = contents
        time.sleep(.3)
    else:
        raise AssertionError("The system document picker did not save the debug report")

    reveal_setting(adb, fixture, "Debug mode")
    element("text", "Debug mode", tap=True)
    if debug_toggle_state(reveal_setting(adb, fixture, "Debug mode")):
        raise AssertionError("Debug recording stayed enabled")
    assert_journal_cleared(adb, fixture["PACKAGE"])
    print("Rapid tab switching, thread return and redacted diagnostics export passed", flush=True)
