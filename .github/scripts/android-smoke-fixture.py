"""Exercise real Android live-listening controls on an isolated, offline debug emulator."""

from datetime import datetime, timedelta, timezone
import json
import hashlib
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
from xml.etree import ElementTree
from zoneinfo import ZoneInfo

PACKAGE = "net.kourlas.voipms_sms"
DID = "4165550100"
CONTACT = "4165550111"
LAST_MESSAGE = "Great, see you at the studio!"
JUNK = "Unpaid tolls: pay at https://example.invalid"
CODE = "004281"
PRIVATE_PEER = "6475550194"
PRIVATE_TEXT = "Confidential rehearsal fixture"
FIXTURE_EMAIL = "fixture@example.invalid"
VOICEMAIL_ACTIONS = ("Play voicemail", "Pause voicemail", "Stop voicemail")



def put_file(adb, path, content):
    # A shell-v2 session waits for the app UID to finish the entire write and
    # propagates its exit status. A raw exec-in stream can close at host EOF.
    subprocess.run(["adb", "shell", "-T", f"run-as {PACKAGE} sh -c 'cat > {path}'"],
                   input=content, check=True, timeout=45)
    digest = adb("shell", "run-as", PACKAGE, "sha256sum", path).split()[0]
    if digest != hashlib.sha256(content).hexdigest():
        raise AssertionError(f"Incomplete Android fixture write to {path}")



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

    prefs = f"""<map>
      <string name="api_email">{FIXTURE_EMAIL}</string>
      <string name="api_password">offline-fixture</string>
      <set name="dids"><string>{DID}</string></set>
      <string name="active_did">{DID}</string>
      <boolean name="preferences_did_{DID}_show_in_conversations_view" value="true" />
      <boolean name="preferences_did_{DID}_retrieve_messages" value="true" />
      <boolean name="first_run" value="false" />
    </map>"""
    adb("shell", "run-as", PACKAGE, "mkdir", "-p", "shared_prefs", "databases")
    adb("shell", "run-as", PACKAGE, "rm", "-f", f"shared_prefs/{PACKAGE}_preferences.xml.bak")
    put_file(adb, f"shared_prefs/{PACKAGE}_preferences.xml", prefs.encode())
    private = f'<map><boolean name="{DID}_{PRIVATE_PEER}" value="true"/></map>'
    put_file(adb, "shared_prefs/private-threads.xml", private.encode())

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
                (106, now - 90, 1, DID, PRIVATE_PEER, PRIVATE_TEXT),
                (101, now - 320, 1, DID, CONTACT, "Are we still meeting at ten tomorrow?"),
                (104, now - 200, 1, DID, CONTACT, f"Your verification code is {CODE}."),
                (105, now - 180, 1, DID, "6475550123", JUNK),
                (102, now - 150, 0, DID, CONTACT, "Yes, ten works for me."),
                (103, now - 60, 1, DID, CONTACT, LAST_MESSAGE),
            ]
            db.executemany("INSERT INTO sms (VoipId,Date,Type,Did,Contact,Text,Unread,Delivered,DeliveryInProgress) "
                           "VALUES (?, ?, ?, ?, ?, ?, 0, 1, 0)", messages)
        db.close()
        adb("shell", "run-as", PACKAGE, "rm", "-f", "databases/sms.db-wal", "databases/sms.db-shm",
            "databases/sms.db-journal")
        put_file(adb, "databases/sms.db", file.read_bytes())


def launch_home(adb):
    adb("shell", "am", "start", "-W", "-f", "0x10008000",
        "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
        "-n", PACKAGE + "/.conversations.ConversationsActivity")


def read_tree(adb):
    for attempt in range(4):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/sms-smoke.xml")
            return ElementTree.fromstring(adb("exec-out", "cat", "/sdcard/sms-smoke.xml"))
        except (ElementTree.ParseError, subprocess.CalledProcessError):
            if attempt == 3:
                raise
            time.sleep(.4)


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node.attrib["bounds"])))


def seed_call_feed(adb):
    # Home creates the production schema and account-scoped observer before this snapshot.
    def copy_store(directory):
        files = adb("shell", "run-as", PACKAGE, "ls", "databases").split()
        if "call_trail.db" not in files:
            return None
        directory = Path(directory)
        for name in ("call_trail.db", "call_trail.db-wal"):
            if name in files:
                (directory / name).write_bytes(subprocess.check_output(
                    ["adb", "exec-out", "run-as", PACKAGE, "cat", "databases/" + name], timeout=45))
        return directory / "call_trail.db"

    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        # Wait until SQLiteOpenHelper has committed the real schema before stopping its process.
        with tempfile.TemporaryDirectory() as directory:
            try:
                path = copy_store(directory)
                if path is not None:
                    with sqlite3.connect(path) as probe:
                        ready = probe.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='events'").fetchone()
                    probe.close()
                    if ready:
                        break
            except (sqlite3.DatabaseError, subprocess.CalledProcessError):
                pass
        time.sleep(.25)
    else:
        raise AssertionError("Home did not initialize the call history database for the fixture account")
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "settings", "put", "system", "time_12_24", "24")
    zone_name = adb("shell", "getprop", "persist.sys.timezone") or "UTC"
    now = datetime.fromtimestamp(int(adb("shell", "date", "+%s")), timezone.utc).astimezone(ZoneInfo(zone_name))
    voice = now - timedelta(seconds=15)
    missed = now - timedelta(days=1, minutes=8)
    account = hashlib.sha256(FIXTURE_EMAIL.encode()).hexdigest()
    with tempfile.TemporaryDirectory() as directory:
        path = copy_store(directory)
        if path is None:
            raise AssertionError("The initialized call database disappeared after process stop")
        with sqlite3.connect(path) as db:
            # Insert oldest first, and include a newer row from a different account to catch ordering and scoping bugs.
            db.executemany("INSERT INTO events(account,id,did,peer,receivedAt,voicemail,mailbox,folder,slot,caller,providerDate)"
                           " VALUES(?,?,?,?,?,?,?,?,?,?,?)", [
                (account, "test-missed", DID, "6475550145", int(missed.timestamp()*1000), 0, "", "", "", "", ""),
                (account, "test-voice", DID, CONTACT, int(voice.timestamp()*1000), 1, "9999", "INBOX", "0", CONTACT,
                 voice.isoformat()),
                ("different-fixture-account", "not-in-account", DID, "6475550198", int(now.timestamp()*1000),
                 1, "7654", "INBOX", "1", "", ""),
            ])
            db.commit()
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchall()
        db.close()
        adb("shell", "run-as", PACKAGE, "rm", "-f", "databases/call_trail.db-wal", "databases/call_trail.db-shm",
            "databases/call_trail.db-journal")
        put_file(adb, "databases/call_trail.db", path.read_bytes())
    return voice, missed


def check_voicemail_controls(root):
    for label in VOICEMAIL_ACTIONS:
        nodes = [n for n in root.iter("node") if n.get("content-desc") == label]
        if len(nodes) != 1:
            raise AssertionError(f"Expected one accessible {label} control, found {len(nodes)}")
        button = nodes[0]
        if button.get("clickable") != "true" or (button.get("enabled") == "true") != (label == "Play voicemail"):
            raise AssertionError(f"Unexpected idle voicemail control state: {label}")


def verify_call_home(tree, voice, missed):
    nodes = list(tree.iter("node"))
    labels = [[n for n in nodes if n.get("text", "").startswith(kind + " · ")] for kind in ("Voicemail", "Missed call")]
    if any(len(matches) != 1 for matches in labels):
        raise AssertionError("Expected exactly the fixture account’s two call events")
    prev = None
    for matches, when in zip(labels, (voice, missed)):
        day = when.strftime("%A, %B ") + str(when.day) + when.strftime(", %Y")
        headings = [n for n in nodes if n.get("text") == day]
        times = [n for n in nodes if n.get("text") == when.strftime("%H:%M")]
        if len(headings) != 1 or len(times) != 1:
            raise AssertionError(f"Home did not display the local date and time for {day} {when:%H:%M}")
        heading_y = bounds(headings[0])[1]
        name_y = bounds(matches[0])[1]
        time_y = bounds(times[0])[1]
        if not heading_y < name_y or not heading_y < time_y or (prev is not None and heading_y <= prev):
            raise AssertionError("Home call events are not grouped newest first by local received date")
        prev = max(name_y, time_y)
    check_voicemail_controls(tree)


def verify_thread_voicemail(tree, when):
    nodes = list(tree.iter("node"))
    titles = [n.get("text") for n in nodes if n.get("resource-id") == PACKAGE + ":id/call_entry_name"]
    times = [n.get("text", "") for n in nodes if n.get("resource-id") == PACKAGE + ":id/call_entry_time"]
    # Java DateFormat.getDateTimeInstance() uses locale defaults, independent of the Android 24h clock switch.
    clocks = (when.strftime("%H:%M:%S"), when.strftime("%I:%M:%S").lstrip("0"))
    if titles != ["Voicemail"] or len(times) != 1 or not any(clock in times[0] for clock in clocks):
        raise AssertionError(f"The selected SMS thread has the wrong call, date or time: {titles}, {times}")
    date = rf"\b{when:%b}\.?\s+{when.day}\b.*{when.year}"
    if not re.search(date, times[0]):
        raise AssertionError(f"Thread voicemail date does not match Home: {times}")
    check_voicemail_controls(tree)


def reveal_older_thread_item(adb, resource):
    for _ in range(5):
        nodes = list(read_tree(adb).iter("node"))
        if any(n.get("resource-id") == PACKAGE + ":id/" + resource for n in nodes):
            return
        view = next(n for n in nodes if n.get("resource-id") == PACKAGE + ":id/list")
        x1, y1, x2, y2 = bounds(view)
        x = str((x1+x2)//2)
        adb("shell", "input", "swipe", x, str(y1+(y2-y1)//5), x, str(y1+(y2-y1)*3//4), "475")
    raise AssertionError("Conversation never displayed " + resource)


def verify_private_pull(adb, element):
    pin = "528946"
    if adb("shell", "pm", "has-feature", "android.software.secure_lock_screen") != "true":
        print("Private credential gesture unavailable on this emulator image (no secure lock screen feature)", flush=True)
        return
    # This test exclusively owns the disposable emulator verified in install_fixture().
    configured = adb("shell", "locksettings", "set-pin", pin)
    if "set to" not in configured.lower():
        raise AssertionError("Could not configure the emulator credential for the private-list gesture")
    try:
        def pull():
            tree = read_tree(adb)
            view = next(n for n in tree.iter("node") if n.get("resource-id") == PACKAGE + ":id/list")
            x1, y1, x2, y2 = bounds(view)
            x = str((x1+x2)//2)
            adb("shell", "input", "swipe", x, str(y1 + (y2-y1)//9), x, str(y1 + 4*(y2-y1)//5), "650")
        if any(n.get("text") == PRIVATE_TEXT for n in read_tree(adb).iter("node")):
            raise AssertionError("Private fixture was visible before credential confirmation")
        pull()
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            focus = adb("shell", "dumpsys", "window", "windows")
            on_prompt = re.search(r"mCurrentFocus=.*(?:ConfirmLock|ConfirmDeviceCredential|BiometricPrompt|CredentialSelector)", focus, re.I)
            if on_prompt:
                break
            system_credential = any(n.get("package") in {"com.android.settings", "com.android.systemui"}
                                    and "pin" in (n.get("text", "") + n.get("content-desc", "")).lower()
                                    for n in read_tree(adb).iter("node"))
            if system_credential:
                break
            time.sleep(.3)
        else:
            raise AssertionError("Pulling beyond refresh distance did not open Android credential confirmation")
        adb("shell", "input", "keyevent", *["KEYCODE_" + digit for digit in pin], "KEYCODE_ENTER")
        element("text", PRIVATE_TEXT)
        if any(n.get("text") == LAST_MESSAGE for n in read_tree(adb).iter("node")):
            raise AssertionError("Ordinary messages remained visible in the private list")
        pull()
        element("text", LAST_MESSAGE)
        if any(n.get("text") == PRIVATE_TEXT for n in read_tree(adb).iter("node")):
            raise AssertionError("Private message remained visible after returning to Messages")
    finally:
        adb("shell", "locksettings", "clear", "--old", pin)


def run(adb, element, screenshot):
    install_fixture(adb)
    # Launch a fresh task. Before seeding, the signed-out smoke path ends on
    # SignInActivity, which Android can otherwise restore over the inbox.
    launch_home(adb)
    element("text", "Call activity")
    voice, missed = seed_call_feed(adb)
    launch_home(adb)
    element("content-desc", "Play voicemail")
    verify_call_home(read_tree(adb), voice, missed)
    screenshot("sample-home")
    element("text", "Text back", tap=True)  # First visible event is the fixture voicemail.
    element("resource-id", PACKAGE + ":id/call_entry_name", expected_text="Voicemail")
    verify_thread_voicemail(read_tree(adb), voice)
    element("text", LAST_MESSAGE)
    screenshot("sample-thread-voicemail")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    element("text", "Call activity")
    element("text", "Messages", tap=True)
    element("text", LAST_MESSAGE)
    element("resource-id", PACKAGE + ":id/sender_region")
    screenshot("sample-inbox")
    element("text", "Other", tap=True)
    element("text", JUNK, hold=True)
    element("content-desc", "More options", tap=True)
    element("text", "Move to Messages", tap=True)
    element("text", "No suspected spam or junk conversations.")
    element("text", "Messages", tap=True)
    element("text", JUNK, hold=True)
    element("content-desc", "More options", tap=True)
    element("text", "Move to Other", tap=True)
    element("text", LAST_MESSAGE, tap=True)
    element("text", "Yes, ten works for me.")
    reveal_older_thread_item(adb, "copy_sms_code")
    element("resource-id", PACKAGE + ":id/copy_sms_code", tap=True)
    # Android's clipboard confirmation can cover the composer, including the system Share action.
    # Allow its standard six-second timeout before sending a coordinate tap to the app.
    time.sleep(7)
    deadline = time.monotonic() + 12
    while re.search(r"Clipboard\s*Overlay", adb("shell", "dumpsys", "window", "visible"), re.I):
        if time.monotonic() >= deadline:
            raise AssertionError("System clipboard overlay still covers the composer")
        time.sleep(0.5)
    element("resource-id", PACKAGE + ":id/message_edit_text", tap=True)
    adb("shell", "input", "keycombination", "113", "50")  # Ctrl+V in the foreground composer
    element("resource-id", PACKAGE + ":id/message_edit_text", expected_text=CODE)
    print("Verification code pasted unchanged into the SMS composer", flush=True)
    adb("shell", "input", "keyevent", *(["KEYCODE_DEL"] * len(CODE)))
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    screenshot("sample-conversation")
    element("content-desc", "More options", tap=True)
    element("text", "Look up number online", tap=True)
    element("text", "Look up prefix origin", tap=True)
    element("resource-id", "android:id/message")
    element("resource-id", "android:id/button2", tap=True)
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

    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    element("text", "Messages", tap=True)
    element("text", LAST_MESSAGE)
    verify_private_pull(adb, element)
