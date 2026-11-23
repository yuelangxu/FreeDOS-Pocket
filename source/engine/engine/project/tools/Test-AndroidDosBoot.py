"""ADB regression: the app still runs after QEMU has read its guest disk."""
import argparse
import json
import socket
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
parser.add_argument("--version", required=True)
args = parser.parse_args()
pkg = "org.freedos.pocket.community"


def adb(*command):
    return subprocess.check_output([args.adb, "-s", args.serial, *command],
                                   text=True, timeout=20).strip()


package = adb("shell", "dumpsys", "package", pkg)
if f"versionName={args.version}\n" not in package.replace("\r", ""):
    raise SystemExit("Installed package does not match the expected version")
adb("shell", "am", "start", "-W", "-n", pkg + "/org.freedos.pocket.MainActivity")
port = adb("forward", "tcp:0", "tcp:5908")
try:
    def query():
        with socket.create_connection(("127.0.0.1", int(port)), timeout=3) as s:
            with s.makefile("rwb") as f:
                if "QMP" not in json.loads(f.readline()):
                    raise ValueError("Not a QMP greeting")
                def call(command):
                    f.write(json.dumps({"execute": command, "id": command}).encode() + b"\r\n")
                    f.flush()
                    for _ in range(100):
                        msg = json.loads(f.readline())
                        if msg.get("id") == command:
                            return msg["return"]
                    raise ValueError("No QMP response")
                call("qmp_capabilities")
                return call("query-status"), call("query-blockstats")

    deadline = time.monotonic() + 45
    while True:
        try:
            state, blocks = query()
            reads = sum(entry["stats"]["rd_operations"] for entry in blocks)
            if state["running"] and reads > 0:
                break
        except (OSError, ValueError, KeyError):
            pass
        if time.monotonic() >= deadline:
            raise SystemExit("QEMU did not stay running and read a guest disk")
        time.sleep(1)
    pid = adb("shell", "pidof", pkg)
    time.sleep(8)
    if pid != adb("shell", "pidof", pkg) or not query()[0]["running"]:
        raise SystemExit("The app did not survive the post-I/O stability check")
    print(json.dumps({"version": args.version, "model": adb("shell", "getprop", "ro.product.model"),
                      "sdk": adb("shell", "getprop", "ro.build.version.sdk"),
                      "pageBytes": adb("shell", "getconf", "PAGE_SIZE"),
                      "guestDiskReads": reads, "status": "running-after-disk-io"}))
finally:
    adb("forward", "--remove", "tcp:" + port)
