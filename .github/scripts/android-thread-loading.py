"""Check recipient changes and search spans in a real Android conversation."""


def run(adb, element, fixture):
    package = fixture["PACKAGE"]
    did = fixture["DID"]
    editor = f"{package}:id/message_edit_text"
    draft = "ThreadDraftCanary"
    first, second = "4165550191", "4165550192"

    def open_thread(peer, text=None):
        command = ["shell", "am", "start", "-W", "--activity-single-top", "-n",
                   f"{package}/.conversation.ConversationActivity", "--es", "did", did, "--es", "contact", peer]
        if text is not None:
            command += ["--es", f"{package}.CONVERSATION_EXTRA_MESSAGE_TEXT", text]
        adb(*command)

    open_thread(first, draft)
    element("resource-id", editor, expected_text=draft)
    open_thread(second)
    element("text", "There are no messages in this conversation.")
    if any(node.get("text") == draft for node in fixture["read_tree"](adb).iter("node")):
        raise AssertionError("A draft crossed recipients in the reused conversation")
    open_thread(first)
    element("resource-id", editor, expected_text=draft, tap=True)
    adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
    adb("shell", "input", "keyevent", "KEYCODE_DEL")

    open_thread(fixture["CONTACT"])
    element("text", fixture["LAST_MESSAGE"])
    element("content-desc", "More options", tap=True)
    element("text", "Search", tap=True)
    element("resource-id", f"{package}:id/search_src_text", tap=True)
    adb("shell", "input", "text", "z")
    # U+0130 lowercases to two code units in English. The span for z must still fit the source.
    element("text", "İz")
    fixture["launch_home"](adb)
    element("text", "Call activity")
    element("text", "Messages", tap=True)
    element("text", fixture["LAST_MESSAGE"])
