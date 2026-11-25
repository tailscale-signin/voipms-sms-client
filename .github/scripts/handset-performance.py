"""Measure notification launches and scroll frames on a connected, unlocked handset.

Run "python3 .github/scripts/handset-performance.py" on before/after builds with the
same inbox and refresh rate. On the phone, open fresh conversation notifications
while capturing, then scroll normally in the inbox or thread. No SMS is sent.
The JSON contains timings and build/device information, never message contents.
"""
import argparse
import json
import math
import re
import subprocess
import sys
import time

PACKAGE = "net.kourlas.voipms_sms"


def adb(*parts):
    return subprocess.check_output(["adb", *parts], text=True, timeout=30, errors="replace")


def distribution(values):
    ordered = sorted(values)
    def quantile(percentile):
        return round(ordered[math.ceil(len(ordered) * percentile) - 1], 2)
    return {"samples": len(values), "p50_ms": quantile(.5), "p95_ms": quantile(.95)} if ordered else {"samples": 0}


def frame_times(output):
    # FrameCompleted and IntendedVsync share a nanosecond time base. Ignore flagged startup frames.
    values = []
    for block in output.split("---PROFILEDATA---")[1::2]:
        lines = [line.strip() for line in block.splitlines() if line.strip()]
        if not lines:
            continue
        columns = lines[0].split(",")
        if not {"Flags", "IntendedVsync", "FrameCompleted"}.issubset(columns):
            continue
        for line in lines[1:]:
            row = dict(zip(columns, line.split(",")))
            try:
                start, end = int(row["IntendedVsync"]), int(row["FrameCompleted"])
                if int(row["Flags"]) == 0 and start < end < 2**63 - 1:
                    values.append((end - start) / 1e6)
            except (ValueError, KeyError):
                continue
    return values


def notification_launches(log):
    # Count first-draw timings only after the matching notification PendingIntent target starts.
    # Reuse/already-visible cases often lack Displayed, and are excluded.
    result = []
    awaiting = False
    screen = PACKAGE + "/.conversation.ConversationActivity"
    for line in log.splitlines():
        if "START u" in line:
            awaiting = screen in line and "voipms-sms://notifications/conversation/" in line
        if awaiting and "Displayed " + screen in line:
            match = re.search(r"\+(?:(\d+)s)?(\d+)ms\b", line)
            if match:
                result.append(int(match[1] or 0) * 1000 + int(match[2]))
            awaiting = False
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=45, choices=range(1, 601), metavar="1..600")
    options = parser.parse_args()
    # Device clock, including fractional seconds, avoids assuming the host and handset agree.
    since = adb("shell", "date", "+%s.%3N").strip()
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    print("Open fresh thread notifications and scroll now.", flush=True, file=sys.stderr)
    time.sleep(options.seconds)
    frames = frame_times(adb("shell", "dumpsys", "gfxinfo", PACKAGE, "framestats"))
    log = adb("logcat", "-d", "-T", since, "-v", "epoch", "ActivityTaskManager:I", "*:S")
    version = re.search(r"versionName=(\S+)", adb("shell", "dumpsys", "package", PACKAGE))
    services = adb("shell", "settings", "get", "secure", "enabled_accessibility_services").lower()
    print(json.dumps({
        "device": adb("shell", "getprop", "ro.product.model").strip(),
        "sdk": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "version": version[1] if version else None,
        "talkback_enabled": "talkback" in services,
        "font_scale": adb("shell", "settings", "get", "system", "font_scale").strip(),
        "notification_activity_start_to_first_draw": distribution(notification_launches(log)),
        "rendered_frames": distribution(frames),
    }, indent=2))


if __name__ == "__main__":
    main()
