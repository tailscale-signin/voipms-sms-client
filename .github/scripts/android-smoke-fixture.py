"""Exercise real Android live-listening controls on an isolated, offline debug emulator."""

import json
import hashlib
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time

PACKAGE = "net.kourlas.voipms_sms"
DID = "4165550100"
CONTACT = "4165550111"
LAST_MESSAGE = "Great, see you at the studio!"


def install_fixture(adb):
    # Only the disposable, debuggable emulator installation is modified.
    if "1" not in (adb("shell", "getprop", "ro.kernel.qemu"), adb("shell", "getprop", "ro.boot.qemu")):
        raise RuntimeError("Synthetic message fixture requires an emulator")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "cmd", "connectivity", "airplane-mode", "enable")
    adb("shell", "svc", "wifi", "disable")
    adb("shell", "svc", "data", "disable")
    for _ in range(20):
        if re.search(r"Active default network:\s*(none|null|-1)", adb("shell", "dumpsys", "connectivity"), re.I):
            break
        time.sleep(1)
    else:
        raise RuntimeError("The emulator still has a default network")

    def put(path, content):
        # A shell-v2 session waits for the app UID to finish the entire write and
        # propagates its exit status. A raw exec-in stream can close at host EOF.
        subprocess.run(["adb", "shell", "-T", f"run-as {PACKAGE} sh -c 'cat > {path}'"],
                       input=content, check=True, timeout=45)
        digest = adb("shell", "run-as", PACKAGE, "sha256sum", path).split()[0]
        if digest != hashlib.sha256(content).hexdigest():
            raise AssertionError(f"Incomplete Android fixture write to {path}")

    prefs = f"""<map>
      <string name="api_email">fixture@example.invalid</string>
      <string name="api_password">offline-fixture</string>
      <set name="dids"><string>{DID}</string></set>
      <string name="active_did">{DID}</string>
      <boolean name="preferences_did_{DID}_show_in_conversations_view" value="true" />
      <boolean name="preferences_did_{DID}_retrieve_messages" value="true" />
      <boolean name="first_run" value="false" />
    </map>"""
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "shared_prefs", "databases")
    adb("shell", "run-as", PACKAGE, "rm", "-f", f"shared_prefs/{PACKAGE}_preferences.xml.bak")
    put(f"shared_prefs/{PACKAGE}_preferences.xml", prefs.encode())

    schema_dir = Path(__file__).resolve().parents[2] / "voipms-sms/schemas/net.kourlas.voipms_sms.database.AbstractDatabase"
    schema_file = max(schema_dir.glob("*.json"), key=lambda p: int(p.stem))
    schema = json.loads(schema_file.read_text())["database"]
    with tempfile.TemporaryDirectory() as directory:
        file = Path(directory) / "sms.db"
        db = sqlite3.connect(file)
        with db:
            for table in schema["entities"]:
                for query in [table["createSql"]] + [index["createSql"] for index in table.get("indices", [])]:
                    db.execute(query.replace('${TABLE_NAME}', table["tableName"]))
            for query in schema["setupQueries"]:
                db.execute(query)
            db.execute(f"PRAGMA user_version={int(schema['version'])}")
            now = int(time.time())
            messages = [
                (101, now - 320, 1, DID, CONTACT, "Are we still meeting at ten tomorrow?"),
                (102, now - 150, 0, DID, CONTACT, "Yes, ten works for me."),
                (103, now - 60, 1, DID, CONTACT, LAST_MESSAGE),
            ]
            db.executemany("INSERT INTO sms (VoipId,Date,Type,Did,Contact,Text,Unread,Delivered,DeliveryInProgress) "
                           "VALUES (?, ?, ?, ?, ?, ?, 0, 1, 0)", messages)
        db.close()
        adb("shell", "run-as", PACKAGE, "rm", "-f", "databases/sms.db-wal", "databases/sms.db-shm",
            "databases/sms.db-journal")
        put("databases/sms.db", file.read_bytes())


def run(adb, element, screenshot):
    install_fixture(adb)
    # Launch a fresh task. Before seeding, the signed-out smoke path ends on
    # SignInActivity, which Android can otherwise restore over the inbox.
    adb("shell", "am", "start", "-W", "--activity-new-task", "--activity-clear-task",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
        "-n", PACKAGE + "/.conversations.ConversationsActivity")
    element("text", LAST_MESSAGE)
    screenshot("sample-inbox")
    element("text", LAST_MESSAGE, tap=True)
    element("text", "Yes, ten works for me.")
    screenshot("sample-conversation")
    element("content-desc", "More options", tap=True)
    element("text", "Look up number online", tap=True)
    element("text", "Search Wikidata", tap=True)
    element("resource-id", "android:id/message")
    screenshot("sample-lookup-consent")
    element("resource-id", "android:id/button2", tap=True)
    element("text", "Yes, ten works for me.")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    element("content-desc", "More options", tap=True)
    element("text", "Settings", tap=True)
    element("text", "Synchronization", tap=True)
    element("text", "Start live listening", tap=True)
    element("text", "Stop listening")
    service = PACKAGE + "/.sms.LiveDeliveryService"
    report = adb("shell", "dumpsys", "activity", "services", service)
    if "isForeground=true" not in report:
        raise AssertionError("Live listening did not enter foreground service state")
    screenshot("live-listening")
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    if "isForeground=true" not in adb("shell", "dumpsys", "activity", "services", service):
        raise AssertionError("Closing the inbox stopped live listening")
    adb("shell", "am", "start", "-W", "--activity-clear-top", "-n", PACKAGE + "/.conversations.ConversationsActivity")
    element("content-desc", "More options", tap=True)
    element("text", "Settings", tap=True)
    element("text", "Synchronization", tap=True)
    element("text", "Stop listening", tap=True)
    element("text", "Start live listening")
    if "isForeground=true" in adb("shell", "dumpsys", "activity", "services", service):
        raise AssertionError("Stopping live listening left the service active")
