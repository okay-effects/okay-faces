#!/usr/bin/env python3
"""THE SHRUNK APP PLAYED FROM OUTSIDE (specs/mobile.md): the release APK — R8 as it is shipped — installed, and the
examples driven by adb as a person would, by what the screen says (uiautomator's dump): an app opened by its name,
a form typed and sent, GitHub's API read, a note kept across a restart, a move played. What the instrumentation
cannot do on a shrunk app, since R8 takes away what only the tests use."""
import re, subprocess, sys, time

ADB = ["adb"]
PKG = "io.github.okay_effects.faces.shell"


def adb(*a):
    return subprocess.run(ADB + list(a), capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t, x0, y0, x1, y1 = m.group(1), *map(int, m.groups()[1:])
        out.append((t, (x0 + x1) // 2, (y0 + y1) // 2))
    return out


def wait(text, seconds=15):
    end = time.time() + seconds
    while time.time() < end:
        for t, x, y in nodes():
            if text in t:
                return x, y
        time.sleep(0.7)
    sys.exit(f"FAIL: no '{text}' on the screen")


def tap(text, seconds=15):
    x, y = wait(text, seconds)
    adb("shell", "input", "tap", str(x), str(y))


def launch(app, reset=False):
    adb("shell", "am", "force-stop", PKG)
    a = ["shell", "am", "start", "-n", f"{PKG}/okay.faces.android.shell.MainActivity", "--es", "app", app]
    adb(*(a + (["--ez", "reset", "true"] if reset else [])))


def check(what, fn):
    fn()
    print(f"ok   {what}")


check("the list opened by its name", lambda: (launch("okay.faces.mobile.example.TodoApp", True), wait("Nothing to do.")))
def notes():
    launch("okay.faces.mobile.example.NotesApp", True)
    wait("Nothing kept yet.")
    x, y = wait("what to remember")
    adb("shell", "input", "tap", str(x), str(y - 90))
    adb("shell", "input", "text", "Buy%smilk")
    tap("Add")
    wait("1 kept on this phone")
check("a note added through the form", notes)
check("GitHub's API read by the shrunk Scala", lambda: (tap("okay-faces on GitHub"), wait("Stars", 30)))
check("the note kept across a restart",
      lambda: (launch("okay.faces.mobile.example.NotesApp"), wait("Buy milk")))
def move():
    launch("okay.faces.mobile.example.NoughtsApp")
    wait("Noughts and crosses")
    tap("·")
    wait("X")
check("a move played", move)
print("all ok")
