"""Run the repository's contact smoke assertions against an existing PR APK."""
from pathlib import Path
import runpy
import sys

scripts = Path(__file__).parent
common = runpy.run_path(str(scripts / "android-smoke.py"))
fixture = runpy.run_path(str(scripts / "android-smoke-fixture.py"))
adb, element, screenshot = (common[key] for key in ("adb", "element", "screenshot"))
pkg = fixture["PACKAGE"]

try:
    adb("install", sys.argv[1])
    for permission in ("READ_CONTACTS", "POST_NOTIFICATIONS"):
        adb("shell", "pm", "grant", pkg, "android.permission." + permission)
    adb("logcat", "-b", "crash", "-c")
    fixture["install_fixture"](adb)
    fixture["launch_home"](adb)
    element("text", "Call activity")
    fixture["seed_call_feed"](adb)
    fixture["launch_home"](adb)
    element("content-desc", "Play voicemail")
    element("text", "Messages", tap=True)
    element("text", fixture["LAST_MESSAGE"])
    fixture["verify_contacts"](adb, element)
    screenshot("contacts-confirmed")
    crashes = adb("logcat", "-b", "crash", "-d")
    if not adb("shell", "pidof", pkg) or f"Process: {pkg}," in crashes:
        raise AssertionError(crashes)
finally:
    print(adb("logcat", "-b", "crash", "-d"), flush=True)
