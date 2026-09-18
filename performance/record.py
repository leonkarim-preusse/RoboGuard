#!/usr/bin/env python3
"""
Records RoboGuard's per-thread CPU load, the robot's per-process load and the calendar detection's step timings from the robot,
saves the raw data and draws a graphic (performance/plot.py).

Usage (robot connected via adb, RoboGuard running):
    python3 performance/record.py [seconds] [--serial 10.131.33.35:5555] [--label text]

Output: performance/runs/<date>_<time>[_label]/ with data.json and performance.png.
Needs only Python 3 + Pillow (no numpy/matplotlib). Nothing about people is recorded: thread names, CPU numbers and the
CalendarMonitor timing lines only.
"""
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PACKAGE = "com.example.roboguard"


def adb(serial, *args, **kw):
    return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, **kw)


def sample_proc(serial, pid, seconds):
    """
    Reads the kernel's CPU counters once per second on the robot: /proc/<pid>/task/*/stat (every RoboGuard thread) and
    /proc/*/stat (every process). CPU % = difference of utime + stime between samples (100 ticks per second); the last core a
    thread ran on is field 39. More reliable than `top` in batch mode, which repeated its first values on this robot.
    """
    script = (f"i=0; while [ $i -lt {seconds + 1} ]; do echo \"@@ $(date +%s.%N)\"; cat /proc/{pid}/task/*/stat 2>/dev/null; "
              f"echo '## processes'; cat /proc/[0-9]*/stat 2>/dev/null; i=$((i+1)); sleep 1; done")
    out = subprocess.run([ADB, "-s", serial, "shell", script], capture_output=True, text=True).stdout
    samples = []
    for block in out.split("@@ ")[1:]:
        head, _, rest = block.partition("\n")
        threads_part, _, procs_part = rest.partition("## processes")
        samples.append({"t": float(head.strip()), "threads": parse_stats(threads_part), "processes": parse_stats(procs_part)})
    return samples


def parse_stats(text):
    """id -> (name, ticks, nice, last core) from /proc stat lines."""
    result = {}
    for line in text.splitlines():
        if ")" not in line:
            continue
        head, _, tail = line.rpartition(")")
        ident, _, name = head.partition(" (")
        f = tail.split()
        if len(f) < 37 or not ident.strip().isdigit():
            continue
        result[ident.strip()] = (name, int(f[11]) + int(f[12]), f[16], f[36])
    return result


def to_rows(samples, key):
    """Consecutive samples → per-interval rows [id, core, nice, cpu %, name] with the robot time of the interval end."""
    rows = []
    for a, b in zip(samples, samples[1:]):
        dt = b["t"] - a["t"]
        if dt <= 0:
            continue
        r = []
        for ident, (name, ticks, nice, core) in b[key].items():
            if ident in a[key]:
                pct = (ticks - a[key][ident][1]) / 100.0 / dt * 100.0
                if pct > 0:
                    r.append([ident, core, nice, f"{pct:.1f}", name])
        rows.append({"t": b["t"], "rows": r})
    return rows


def main():
    args = sys.argv[1:]
    seconds = 60
    serial = "10.131.33.35:5555"
    label = ""
    i = 0
    while i < len(args):
        if args[i] == "--serial":
            serial = args[i + 1]; i += 2
        elif args[i] == "--label":
            label = args[i + 1]; i += 2
        else:
            seconds = int(args[i]); i += 1

    pid = adb(serial, "shell", "pidof", PACKAGE).stdout.strip().split()
    if not pid:
        sys.exit("RoboGuard is not running on the robot (pidof returned nothing)")
    pid = pid[0]
    # Clock offset robot → host, to put logcat times (robot clock) on the host timeline of the top samples.
    t_host = time.time()
    robot_now = float(adb(serial, "shell", "date", "+%s.%N").stdout.strip())
    offset = t_host - robot_now

    adb(serial, "logcat", "-c")
    # Full command lines of all processes (the stat names are cut to 15 characters, e.g. both HAL services show "android.hardwar").
    cmd = adb(serial, "shell", "for p in /proc/[0-9]*; do echo \"${p#/proc/} $(tr '\\0' ' ' < $p/cmdline 2>/dev/null | cut -c1-80)\"; done").stdout
    cmdlines = {}
    for line in cmd.splitlines():
        ident, _, c = line.partition(" ")
        if c.strip():
            cmdlines[ident] = c.strip()
    print(f"recording {seconds} s from pid {pid} on {serial} …")
    samples = sample_proc(serial, pid, seconds)
    for s_ in samples:  # robot clock → host timeline
        s_["t"] += offset
    threads = to_rows(samples, "threads")
    processes = to_rows(samples, "processes")
    for row_set in processes:
        for row in row_set["rows"]:
            row[4] = cmdlines.get(row[0], row[4])

    log = adb(serial, "logcat", "-d", "-v", "epoch", "-s", "CalendarMonitor:I", "RoboGuardNav:I").stdout.splitlines()
    timing, nav = [], []
    for line in log:
        m = re.match(r"\s*(\d+\.\d+)\s.*?(CalendarMonitor|RoboGuardNav).*?: (.*)$", line)
        if m:
            (timing if m.group(2) == "CalendarMonitor" else nav).append({"t": float(m.group(1)) + offset, "text": m.group(3)})

    name = datetime.now().strftime("%Y%m%d_%H%M%S") + (("_" + re.sub(r"[^A-Za-z0-9_-]+", "-", label)) if label else "")
    run_dir = os.path.join(HERE, "runs", name)
    os.makedirs(run_dir, exist_ok=True)
    data = {"pid": pid, "serial": serial, "seconds": seconds, "label": label, "start": t_host,
            "threads": threads, "processes": processes, "monitor": timing, "navigation": nav}
    with open(os.path.join(run_dir, "data.json"), "w") as f:
        json.dump(data, f)
    print(f"{len(threads)} thread samples, {len(processes)} process samples, {len(timing)} monitor lines → {run_dir}")

    sys.path.insert(0, HERE)
    import plot
    png = plot.render(data, os.path.join(run_dir, "performance.png"))
    print("graphic:", png)


if __name__ == "__main__":
    main()
