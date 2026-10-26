#!/usr/bin/env python3
"""End-to-end Android Emulator smoke verifier for the R8-minified Release APK."""

from __future__ import annotations

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def run_adb(*args: str, check: bool = True) -> str:
    proc = subprocess.run(
        ["adb", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed ({proc.returncode}): {proc.stderr.strip()} {proc.stdout.strip()}")
    return proc.stdout


def assert_no_app_crash(stage: str) -> None:
    crashes = run_adb("logcat", "-d", "AndroidRuntime:E", "*:S", check=False)
    if "FATAL EXCEPTION" in crashes or "Process: app.routine" in crashes:
        snippet = " | ".join(line.strip() for line in crashes.splitlines()[-15:] if line.strip())
        print(f"::error title=emulator-crash ({stage})::{snippet}")
        raise SystemExit(1)


def dump_ui_xml(local_path: Path) -> ET.Element | None:
    run_adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml", check=False)
    proc = subprocess.run(
        ["adb", "pull", "/sdcard/window_dump.xml", str(local_path)],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0 or not local_path.exists():
        return None
    try:
        return ET.parse(local_path).getroot()
    except ET.ParseError:
        return None


def find_bounds(root: ET.Element, needle: str) -> tuple[int, int, int, int] | None:
    needle_low = needle.lower()
    candidates: list[tuple[int, tuple[int, int, int, int]]] = []
    for node in root.iter("node"):
        combined = f"{node.attrib.get('text', '')} {node.attrib.get('content-desc', '')}".lower()
        if needle_low not in combined:
            continue
        match = BOUNDS_RE.fullmatch(node.attrib.get("bounds", ""))
        if not match:
            continue
        x1, y1, x2, y2 = (int(g) for g in match.groups())
        if x2 > x1 and y2 > y1:
            area = (x2 - x1) * (y2 - y1)
            candidates.append((area, (x1, y1, x2, y2)))
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    return candidates[0][1]


def wait_and_tap(needle: str, timeout_s: float = 20.0, tap: bool = True) -> None:
    deadline = time.monotonic() + timeout_s
    xml_path = Path("/tmp/routine_ui_dump.xml")
    last_texts: list[str] = []
    while time.monotonic() < deadline:
        assert_no_app_crash(f"waiting for '{needle}'")
        root = dump_ui_xml(xml_path)
        if root is not None:
            bounds = find_bounds(root, needle)
            if bounds is not None:
                if tap:
                    cx = (bounds[0] + bounds[2]) // 2
                    cy = (bounds[1] + bounds[3]) // 2
                    print(f"[smoke] Tapping '{needle}' at ({cx}, {cy})")
                    run_adb("shell", "input", "tap", str(cx), str(cy))
                else:
                    print(f"[smoke] Verified '{needle}' visible on screen")
                return
            last_texts = [
                n.attrib.get("text", "")
                for n in root.iter("node")
                if n.attrib.get("text", "").strip()
            ][:15]
        time.sleep(1.5)
    print(f"::error title=emulator-ui::Timed out waiting for '{needle}'. Visible texts: {last_texts}")
    raise SystemExit(1)


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: emulator_release_smoke.py <path-to-release.apk>")
        raise SystemExit(2)

    apk_path = Path(sys.argv[1]).resolve()
    print(f"[smoke] Installing release APK: {apk_path}")
    run_adb("wait-for-device")
    # Dismiss any system ANR / lockscreen dialogs before starting
    run_adb("shell", "wm", "dismiss-keyguard", check=False)
    run_adb("shell", "settings", "put", "global", "window_animation_scale", "0", check=False)
    run_adb("shell", "settings", "put", "global", "transition_animation_scale", "0", check=False)
    run_adb("shell", "settings", "put", "global", "animator_duration_scale", "0", check=False)
    run_adb("logcat", "-c", check=False)

    install_out = run_adb("install", "-r", str(apk_path))
    print(f"[smoke] Install output: {install_out.strip()}")

    # Grant POST_NOTIFICATIONS so foreground monitor notification & notification actions work cleanly on API 33+
    run_adb("shell", "pm", "grant", "app.routine", "android.permission.POST_NOTIFICATIONS", check=False)

    start_out = run_adb("shell", "am", "start", "-W", "-n", "app.routine/.MainActivity")
    print(f"[smoke] Launch output: {start_out.strip()}")
    time.sleep(2.0)
    assert_no_app_crash("app launch")

    # 1. Onboarding -> Skip
    wait_and_tap("Skip", timeout_s=25.0, tap=True)
    time.sleep(1.5)

    # 2. Filter to Ready templates and open "Battery saver below 20%"
    wait_and_tap("Ready", timeout_s=20.0, tap=True)
    time.sleep(1.5)
    wait_and_tap("Battery saver below 20%", timeout_s=20.0, tap=True)
    time.sleep(1.5)

    # 3. Tap "Activate Rule" (exercises R8 kotlinx.serialization + SQLCipher Room DB + RoutineTriggerMonitorService)
    wait_and_tap("Activate Rule", timeout_s=20.0, tap=True)
    time.sleep(2.5)
    assert_no_app_crash("template activation")

    # 4. Verify My Rules screen shows "Active Rules (1)" and "Run Now"
    wait_and_tap("Active Rules (1)", timeout_s=20.0, tap=False)
    wait_and_tap("Run Now", timeout_s=15.0, tap=True)
    time.sleep(2.5)
    assert_no_app_crash("Run Now execution")

    # 5. Send a live broadcast while RoutineTriggerMonitorService is active and verify zero crashes
    run_adb("shell", "am", "broadcast", "-a", "com.android.deskclock.ALARM_DISMISS", check=False)
    time.sleep(1.5)
    assert_no_app_crash("live broadcast dispatch")

    services_out = run_adb("shell", "dumpsys", "activity", "services", "app.routine", check=False)
    if "RoutineTriggerMonitorService" not in services_out:
        print("::error title=emulator-service::RoutineTriggerMonitorService was not started for enabled battery rule")
        raise SystemExit(1)

    print("[smoke] SUCCESS: Release APK installed, activated template in SQLCipher, started monitor service, and executed Run Now with zero crashes!")


if __name__ == "__main__":
    main()
