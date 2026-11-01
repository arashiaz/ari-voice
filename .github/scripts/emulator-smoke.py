"""UI smoke test on a clean emulator; no voice models or third-party apps installed.

Tests the real APK, not only its parser: launch, typed command -> Settings, and
an open+search command -> accessibility-permission dialog (not an app picker).
This does NOT verify microphone recognition or search inside Instagram.
"""

import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PACKAGE = "com.arashiaz.arivoice"
ACTIVITY = PACKAGE + "/.MainActivity"


def report_failure(error_type, error, tb):
    # CI log downloads are not always reachable from the sandbox; expose the
    # specific error in the run's annotations as well as the full step log.
    details = str(error).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::error title=Android emulator smoke test::{error_type.__name__}: {details}", flush=True)
    traceback.print_exception(error_type, error, tb)


sys.excepthook = report_failure


def adb(*args):
    result = subprocess.run(["adb", *args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"adb {' '.join(args)} exited {result.returncode}: "
                           f"{result.stdout[-350:]} {result.stderr[-350:]}")
    return result


def dump():
    last_error = None
    for _ in range(5):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/window.xml")
            content = adb("exec-out", "cat", "/sdcard/window.xml").stdout
            return ET.fromstring(content)
        except (subprocess.CalledProcessError, ET.ParseError) as error:
            last_error = error
            time.sleep(2)
    raise RuntimeError("Could not read emulator UI hierarchy") from last_error


def wait_for(predicate, description):
    for _ in range(12):
        root = dump()
        if predicate(root):
            return root
        time.sleep(2)
    raise AssertionError(f"Timed out waiting for {description}")


def node_by_id(root, name):
    return next((node for node in root.iter("node")
                 if node.get("resource-id") == f"{PACKAGE}:id/{name}"), None)


def click(root, name):
    node = node_by_id(root, name)
    if node is None:
        raise AssertionError(f"Visible UI has no {name} control")
    left, top, right, bottom = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def launch():
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)
    time.sleep(2)
    root = dump()
    # Dismiss only the first-run optional model-download offer.
    if any("راه‌اندازی فرمان صوتی آفلاین" in node.get("text", "")
           for node in root.iter("node")):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
    return wait_for(lambda ui: node_by_id(ui, "command") is not None, "assistant home screen")


def type_command(value):
    root = launch()
    click(root, "command")
    # adb's text input is reliable for ASCII; Persian parser phrases are covered by JVM tests.
    adb("shell", "input", "text", value.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "KEYCODE_BACK")  # close keyboard
    root = wait_for(lambda ui: node_by_id(ui, "run") is not None, "run command button")
    click(root, "run")


print(adb("devices", "-l").stdout)
print("Installing test APK (x86_64, not the arm64 phone artifact)")
print(adb("install", "-r", "app/build/outputs/apk/debug/app-debug.apk").stdout)

# This is a real Android activity launch using the installed APK.
type_command("open settings")
focus = adb("shell", "dumpsys", "window").stdout
active = [line.strip() for line in focus.splitlines()
          if "mCurrentFocus" in line or "mFocusedApp" in line]
print("Focused window:", active[:3])
if not any("com.android.settings" in line for line in active):
    raise AssertionError("Open settings command did not switch to Android Settings")

# Regression: a compound command must request accessibility rather than try to
# launch a nonexistent app named 'instagram search ardalan'.
type_command("open instagram search ardalan")
root = wait_for(
    lambda ui: any("مجوز لازم است" in node.get("text", "") for node in ui.iter("node")),
    "accessibility permission dialog for two-step search",
)
if any("انتخاب برنامه برای" in node.get("text", "") for node in root.iter("node")):
    raise AssertionError("Compound search was mistaken for an app name")
print("PASS: APK installs/opens; Settings opens; compound search reaches permission check")
print("NOT TESTED: offline voice recognition or third-party in-app search on a real phone")
