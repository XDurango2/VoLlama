"""Utilidades mínimas para dirigir la UI de un dispositivo Android por adb + uiautomator."""
import os
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

PKG = "com.XDurango.VoLlama"


def adb_path():
    found = shutil.which("adb")
    if found:
        return found
    for base in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                 os.path.expanduser("~/Android/Sdk")):
        if base and os.path.exists(os.path.join(base, "platform-tools", "adb")):
            return os.path.join(base, "platform-tools", "adb")
    raise SystemExit("No se encontró adb (ni en PATH ni en ANDROID_HOME/ANDROID_SDK_ROOT/~/Android/Sdk)")


ADB = adb_path()


def connected_serials():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True, check=True).stdout
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]


class Node:
    def __init__(self, el):
        self.text = el.get("text") or ""
        self.desc = el.get("content-desc") or ""
        self.clickable = el.get("clickable") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds") or "[0,0][0,0]")
        self.l, self.t, self.r, self.b = map(int, m.groups())

    @property
    def center(self):
        return (self.l + self.r) // 2, (self.t + self.b) // 2

    def __repr__(self):
        return f"Node(text={self.text!r}, desc={self.desc!r}, clickable={self.clickable}, center={self.center})"


class Device:
    def __init__(self, serial, label=None):
        self.serial = serial
        self.label = label or serial

    def sh(self, *args, check=True, timeout=30):
        return subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, text=True,
                              check=check, timeout=timeout).stdout

    def model(self):
        return self.sh("shell", "getprop", "ro.product.model").strip()

    def nodes(self):
        for _ in range(3):  # uiautomator dump falla de forma intermitente durante animaciones
            self.sh("shell", "uiautomator", "dump", "/sdcard/_e2e_ui.xml", check=False)
            xml = self.sh("exec-out", "cat", "/sdcard/_e2e_ui.xml", check=False)
            if xml.strip().startswith("<?xml"):
                return [Node(e) for e in ET.fromstring(xml).iter("node")]
            time.sleep(0.5)
        return []

    def find(self, text=None, desc=None, contains=False):
        def ok(have, want):
            return want is not None and (want in have if contains else have == want)
        for n in self.nodes():
            if (text is not None and ok(n.text, text)) or (desc is not None and ok(n.desc, desc)):
                return n
        return None

    def wait_for(self, text=None, desc=None, timeout=15, contains=False):
        end = time.time() + timeout
        while time.time() < end:
            n = self.find(text, desc, contains)
            if n:
                return n
            time.sleep(0.4)
        return None

    def tap(self, node):
        x, y = node.center
        self.sh("shell", "input", "tap", str(x), str(y))

    def tap_text(self, text=None, desc=None, timeout=15, contains=False):
        n = self.wait_for(text, desc, timeout, contains)
        if not n:
            raise TimeoutError(f"[{self.label}] no apareció {text or desc!r} en {timeout}s")
        self.tap(n)
        return n

    def tap_fab(self):
        """El FAB extendido aparece como nodo clickable sin texto en la esquina inferior derecha."""
        cands = [n for n in self.nodes() if n.clickable and not n.text and n.b > 0]
        if not cands:
            raise TimeoutError(f"[{self.label}] no se encontró el FAB")
        self.tap(max(cands, key=lambda n: (n.b, n.r)))

    def launch(self, clear_logcat=True):
        self.sh("shell", "am", "force-stop", PKG)
        if clear_logcat:
            self.sh("shell", "logcat", "-c", check=False)
        self.sh("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", check=False)

    def stop(self):
        self.sh("shell", "am", "force-stop", PKG, check=False)

    def wake(self):
        self.sh("shell", "input", "keyevent", "KEYCODE_WAKEUP", check=False)
        self.sh("shell", "wm", "dismiss-keyguard", check=False)

    def screenshot(self, path):
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"],
                                   capture_output=True, check=True).stdout)

    def dump_texts(self):
        return [(n.text or n.desc, n.clickable) for n in self.nodes() if n.text or n.desc or n.clickable]
