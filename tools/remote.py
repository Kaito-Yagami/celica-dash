"""Sends one command to the head unit through the running setup helper (RUN ME) and waits for the answer.
The unit must have Settings › Advanced › "Let the PC helper drive the unit" switched on.

    python tools/remote.py diag
    python tools/remote.py logcat
    python tools/remote.py screenshot            -> setup/payload/remote/results/<id>.png
    python tools/remote.py music toggle|play|pause|next|prev
    python tools/remote.py broadcast <action> [key=value ...]     (ints when the value is numeric)
    python tools/remote.py start <package>[/<activity>]
    python tools/remote.py prop <name>
    python tools/remote.py shell <command...>    (runs as the app's user, no root)
    python tools/remote.py ls <path> | read <path>

The command is written to setup/payload/remote/queue; the unit picks it up on its next poll
(every two seconds while it is open) and posts the result, which is printed here.
"""
import os
import sys
import time

REMOTE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "setup", "payload", "remote")
QUEUE = os.path.join(REMOTE, "queue")
RESULTS = os.path.join(REMOTE, "results")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    args = sys.argv[1:]
    queue = QUEUE
    if args and args[0] == "--emu":          # the emulator's own queue
        queue = QUEUE + "-emu"; args = args[1:]
    os.makedirs(queue, exist_ok=True)
    os.makedirs(RESULTS, exist_ok=True)
    cmd_id = f"{int(time.time() * 1000)}"
    with open(os.path.join(queue, cmd_id), "w", encoding="utf-8") as f:
        f.write(" ".join(args))
    txt = os.path.join(RESULTS, cmd_id + ".txt")
    png = os.path.join(RESULTS, cmd_id + ".png")
    deadline = time.time() + float(os.environ.get("REMOTE_TIMEOUT", "40"))
    while time.time() < deadline:
        if os.path.exists(txt):
            time.sleep(0.2)
            data = open(txt, "rb").read()
            if data[:8] == b"\x89PNG\r\n\x1a\n":
                os.replace(txt, png)
                print(png)
            else:
                # the unit's own logs carry Chinese punctuation the console cannot spell
                text = data.decode("utf-8", "replace")
                enc = sys.stdout.encoding or "utf-8"
                print(text.encode(enc, "replace").decode(enc, "replace"))
            return
        time.sleep(0.5)
    print(f"no answer within the timeout (command {cmd_id} still queued: {os.path.exists(os.path.join(queue, cmd_id))})")


if __name__ == "__main__":
    main()
