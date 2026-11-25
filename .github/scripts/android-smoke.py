"""Exercise the F-Droid debug APK on a clean Android emulator, without a VoIP.ms account."""

import re
import subprocess
import sys
import time
from xml.etree import ElementTree

PACKAGE = "net.kourlas.voipms_sms"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=60).strip()


def element(attribute, value, *, tap=False):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/sms-smoke.xml")
            tree = ElementTree.fromstring(adb("exec-out", "cat", "/sdcard/sms-smoke.xml"))
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            time.sleep(1)
            continue
        for node in tree.iter("node"):
            if node.get(attribute) == value:
                if tap:
                    left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
                print(f"{'Tapped' if tap else 'Found'} {value}", flush=True)
                return
        time.sleep(1)
    raise AssertionError(f"Missing UI element {attribute}={value}")


def main():
    adb("install", "-g", sys.argv[1])
    adb("logcat", "-b", "crash", "-c")
    adb("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
        "-c", "android.intent.category.LAUNCHER", "-n", PACKAGE + "/.conversations.ConversationsActivity")
    element("resource-id", PACKAGE + ":id/sign_in_button")
    element("resource-id", PACKAGE + ":id/skip_button", tap=True)
    element("content-desc", "More options", tap=True)
    element("text", "Settings", tap=True)
    element("text", "Appearance", tap=True)
    element("text", "Dark")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    element("text", "Synchronization", tap=True)
    element("text", "Live delivery")
    element("text", "This build receives messages through synchronization. Set the background interval below.")
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
