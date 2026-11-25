"""Exercise the F-Droid debug APK on a clean Android emulator, without a VoIP.ms account."""

import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
import tempfile
import time
from xml.etree import ElementTree

PACKAGE = "net.kourlas.voipms_sms"
SCREENSHOTS = Path(os.environ.get("SMS_SMOKE_SCREENSHOTS") or tempfile.mkdtemp(prefix="voipms-ui-"))


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=60).strip()


def screenshot(name):
    SCREENSHOTS.mkdir(parents=True, exist_ok=True)
    with (SCREENSHOTS / (name + ".png")).open("wb") as image:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=image, timeout=60, check=True)


def element(attribute, value, *, tap=False):
    expected = value if isinstance(value, tuple) else (value,)
    visible = []
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/sms-smoke.xml")
            tree = ElementTree.fromstring(adb("exec-out", "cat", "/sdcard/sms-smoke.xml"))
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            time.sleep(1)
            continue
        nodes = list(tree.iter("node"))
        visible = [node.get("text") or node.get("content-desc") for node in nodes
                   if node.get("text") or node.get("content-desc")]
        permission_text = [node.get("text") for node in nodes if node.get("package") in {
            "com.android.permissioncontroller", "com.google.android.permissioncontroller"
        } and node.get("text")]
        if permission_text:
            raise AssertionError(f"Unexpected permission prompt: {permission_text}")
        for node in nodes:
            if node.get(attribute) in expected:
                if tap:
                    left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                print(f"{'Tapped' if tap else 'Found'} {value}", flush=True)
                return
        time.sleep(1)
    screenshot("unexpected-screen")
    raise AssertionError(f"Missing UI element {attribute}={value}; visible labels: {visible}")


def main():
    print(f"UI screenshots: {SCREENSHOTS}", flush=True)
    adb("install", sys.argv[1])
    # Grant just the two expected startup permissions. Other permission dialogs fail the test.
    for permission in ("READ_CONTACTS", "POST_NOTIFICATIONS"):
        adb("shell", "pm", "grant", PACKAGE, "android.permission." + permission)
    adb("logcat", "-b", "crash", "-c")
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-n", PACKAGE + "/.conversations.ConversationsActivity")
    element("resource-id", PACKAGE + ":id/sign_in_button")
    element("resource-id", PACKAGE + ":id/skip_button", tap=True)
    element("content-desc", "More options")
    screenshot("inbox")
    element("content-desc", "More options", tap=True)
    element("text", "Settings", tap=True)
    element("text", "Appearance", tap=True)
    element("text", "Dark")
    element("text", "Chat font")
    screenshot("appearance")
    element("text", "Chat font", tap=True)
    element("package", ("com.android.documentsui", "com.google.android.documentsui"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    element("text", "Chat font")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    element("text", "Synchronization", tap=True)
    element("text", "Sign in to VoIP.ms to start a listening session.")
    screenshot("direct-listening")
    element("text", "Start live listening", tap=True)
    element("resource-id", PACKAGE + ":id/sign_in_button")
    fixture = runpy.run_path(Path(__file__).with_name("android-smoke-fixture.py"))
    fixture["run"](adb, element, screenshot)
    if not adb("shell", "pidof", PACKAGE):
        raise AssertionError("SMS process exited")
    crashes = adb("logcat", "-b", "crash", "-d")
    if f"Process: {PACKAGE}," in crashes:
        raise AssertionError(crashes)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        print(adb("logcat", "-b", "crash", "-d"), file=sys.stderr)
        raise
