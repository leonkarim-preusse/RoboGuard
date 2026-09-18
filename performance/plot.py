#!/usr/bin/env python3
"""
Draws performance.png from a run recorded by performance/record.py:
  1. RoboGuard's CPU per thread group (stacked; 100 % = one core fully busy)
  2. The whole robot's CPU per process group (stacked; 800 % = all 8 cores)
  3. Calendar detection time per check, split into its steps (2 s averages from the CalendarMonitor log)
  4. Checks per second and camera frames per second
  plus a table per thread: average/max CPU, share of samples on the fast cores (4–7 on the robot's Snapdragon 845), nice value.

Usage: python3 performance/plot.py performance/runs/<run>/data.json   (writes performance.png next to it)
"""
import json
import os
import re
import sys
from datetime import datetime

from PIL import Image, ImageDraw, ImageFont

# Reference categorical palette (dataviz skill, light mode, fixed order) + neutral gray for "Other".
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7"]
OTHER = "#9d9c96"
SURFACE = "#fcfcfb"
TEXT = "#0b0b0b"
TEXT2 = "#52514e"
GRID = "#e4e3df"
AXIS = "#b5b4ad"
FAST_CORES = {4, 5, 6, 7}

THREAD_GROUPS = [
    ("Calendar detection (2 workers)", SERIES[0]),
    ("OpenCV helper threads", SERIES[1]),
    ("Camera frame copy", SERIES[2]),
    ("Screen (UI + render)", SERIES[3]),
    ("Garbage collection", SERIES[4]),
    ("Conversation audio", SERIES[5]),
    ("Other RoboGuard threads", OTHER),
]
PROCESS_GROUPS = [
    ("RoboGuard", SERIES[0]),
    ("Robot chassis / motors", SERIES[1]),
    ("Audio service", SERIES[2]),
    ("Camera services", SERIES[3]),
    ("RobotOS vision", SERIES[4]),
    ("Other processes", OTHER),
]
STEPS = [
    ("Pink search + rest", SERIES[4]),
    ("Resize", SERIES[3]),
    ("Keypoints", SERIES[0]),
    ("Matching (kNN + ratio)", SERIES[1]),
    ("Homography", SERIES[2]),
]


def thread_group(pid, tid, name):
    if name.startswith("CalendarDetect"):
        return 0
    if tid == pid:
        return 3  # main (UI) thread
    if name == "ample.roboguard":
        return 1  # threads started natively by OpenCV inherit the process name
    if name.startswith("CameraStream"):
        return 2
    if name.startswith("RenderThread"):
        return 3
    if name.startswith("HeapTaskDaemon"):
        return 4
    if name.startswith("SpeakerChange"):
        return 5
    return 6


def process_group(roboguard_pid, pid, name):
    if pid == roboguard_pid:
        return 0
    if "chassis" in name:
        return 1
    if "audio" in name:
        return 2
    if "camera" in name:
        return 3
    if "visionsdk" in name:
        return 4
    return 5


def font(size, bold=False):
    path = "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf" % ("-Bold" if bold else "")
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def fnum(s):
    return float(s.replace(",", "."))


def parse_monitor(lines):
    windows = []
    for entry in lines:
        text, t = entry["text"], entry["t"]
        m = re.search(r"camera ([\d,]+) fps · passes ([\d,]+)/s \(avg (\d+) ms, colour (\d+) ms\)", text)
        if m:
            windows.append({"t": t, "fps": fnum(m.group(1)), "pps": fnum(m.group(2)), "avg": int(m.group(3)), "colour": int(m.group(4))})
            continue
        m = re.search(r"passes with a pink area: (\d+), avg (\d+) ms = resize (\d+) \+ keypoints (\d+) \+ knn match (\d+) \+ ratio test (\d+) \+ homography (\d+) ms", text)
        if m and windows:
            n, avg, rs, kp, knn, rt, hg = (int(x) for x in m.groups())
            windows[-1]["pink"] = {"n": n, "avg": avg, "resize": rs, "keypoints": kp, "match": knn + rt, "homography": hg}
            continue
        m = re.search(r"pink search per pass: colour image ([\d,.]+) \+ colour ranges ([\d,.]+) \+ cleaning/grouping ([\d,.]+) \+ regions/shapes ([\d,.]+) ms", text)
        if m and windows:
            windows[-1]["pinkStages"] = [fnum(x) for x in m.groups()]
    return windows


CHECK_RE = re.compile(r"check: frame (\d+) px, scale ([\d.,]+), keypoints (\d+), good (\d+), inliers (\d+), features (\d+)")


def parse_checks(lines):
    """Per-check lines of the CalendarMonitor: frame size (px), scale, keypoints found, good matches, inliers, features setting."""
    out = []
    for entry in lines:
        m = CHECK_RE.search(entry["text"])
        if m:
            out.append({"t": entry["t"], "side": int(m.group(1)), "scale": fnum(m.group(2)), "keypoints": int(m.group(3)),
                        "good": int(m.group(4)), "inliers": int(m.group(5)), "features": int(m.group(6))})
    return out


def binned_median(points, width=50, min_count=3):
    """Median y per x bin (width px) with at least min_count points: [(bin centre, median)]."""
    bins = {}
    for x, y in points:
        bins.setdefault(int(x // width), []).append(y)
    out = []
    for b in sorted(bins):
        ys = sorted(bins[b])
        if len(ys) >= min_count:
            out.append(((b + 0.5) * width, ys[len(ys) // 2]))
    return out


def distance_panels(g, left, top, width, height, runs, fonts, legend_x):
    """
    Two scatter panels over the pink frame size (long side in frame pixels; small = far away): inliers per check and keypoints
    ORB found per check. runs = [(label, checks, colour)]; each run also gets a line of binned medians. Returns the bottom y.
    """
    f_head, f, fs = fonts
    all_checks = [c for _, cs, _ in runs for c in cs]
    xmax = max([c["side"] for c in all_checks] + [400])
    xmax = (int(xmax / 100) + 1) * 100
    panels = [("Inliers per check vs pink frame size (small = far away)", "inliers",
               max([c["inliers"] for c in all_checks] + [40])),
              ("Keypoints ORB found per check vs pink frame size", "keypoints",
               max([c["keypoints"] for c in all_checks] + [1000]))]
    y = top
    for title, key, ymax in panels:
        ymax = (int(ymax / 10) + 1) * 10 if key == "inliers" else (int(ymax / 500) + 1) * 500
        g.text((left, y - 32), title, fill=TEXT, font=f_head)
        step = 10 if key == "inliers" and ymax <= 80 else (20 if key == "inliers" else 500)
        for v in range(0, ymax + 1, step):
            yy = y + height - v / ymax * height
            g.line([left, yy, left + width, yy], fill=GRID)
            g.text((left - 12 - g.textlength(str(v), font=fs), yy - 8), str(v), fill=TEXT2, font=fs)
        g.line([left, y + height, left + width, y + height], fill=AXIS)
        for v in range(0, xmax + 1, 100):
            xx = left + v / xmax * width
            g.line([xx, y + height, xx, y + height + 5], fill=AXIS)
            g.text((xx - 14, y + height + 8), f"{v} px", fill=TEXT2, font=fs)

        def pt(c):
            return left + c["side"] / xmax * width, y + height - min(c[key], ymax) / ymax * height
        for label, cs, color in runs:
            for c in cs:
                px, py = pt(c)
                g.ellipse([px - 3, py - 3, px + 3, py + 3], outline=color, width=2)
        for label, cs, color in runs:
            med = binned_median([(c["side"], c[key]) for c in cs])
            line = [(left + x / xmax * width, y + height - min(v, ymax) / ymax * height) for x, v in med]
            if len(line) > 1:
                g.line(line, fill=SURFACE, width=6)  # surface halo so the median line reads over the dots
                g.line(line, fill=color, width=3)
        ly = y
        for label, cs, color in runs:
            g.rounded_rectangle([legend_x, ly + 3, legend_x + 16, ly + 17], radius=3, fill=color)
            g.text((legend_x + 24, ly), f"{label} · {len(cs)} checks", fill=TEXT, font=f)
            ly += 26
        g.text((legend_x, ly + 6), "dots = single checks, line = median per 50 px", fill=TEXT2, font=fs)
        y += height + 90
    return y


def drive_intervals(nav, t_end):
    """
    Driving phases from the navigation log (Navigation and Map screen): [(start, end, avoiding)] where avoiding = obstacle avoidance
    (usually turning). A drive starts at "Started" and ends at ARRIVED / FAILED / STOP; still running at the end → until t_end.
    """
    out = []
    start = None
    avoid = None
    for e in nav:
        text, t = e["text"], e["t"]
        if ": Started" in text:
            start = t
        elif start is not None and ("ARRIVED" in text or "FAILED" in text or "STOP" in text):
            if avoid is not None:
                out.append((avoid, t, True)); avoid = None
            out.append((start, t, False)); start = None
        if "AvoidingObstacle" in text:
            avoid = t
        elif "ObstacleCleared" in text and avoid is not None:
            out.append((avoid, t, True)); avoid = None
    if start is not None:
        out.append((start, t_end, False))
    if avoid is not None:
        out.append((avoid, t_end, True))
    return out


def settings_line(lines):
    for entry in lines:
        if entry["text"].startswith("references ["):
            return entry["text"]
    return ""


def render(data, out_path):
    pid = data["pid"]
    threads, processes = data["threads"], data["processes"]
    # The timeline starts one interval before the first CPU sample (setup such as reading process names happens before it).
    t0 = (threads[0]["t"] - 1) if threads else data["start"]
    windows = [w for w in parse_monitor(data["monitor"]) if w["t"] >= t0]
    t_end = max([s["t"] for s in threads + processes] + [w["t"] for w in windows] + [t0 + 1])

    # --- aggregate ----------------------------------------------------------------------------------------------------
    thread_series = []  # per sample: list of group sums
    per_thread = {}  # tid -> stats
    for s in threads:
        sums = [0.0] * len(THREAD_GROUPS)
        for tid, cpu, ni, pcpu, name in s["rows"]:
            name = name.strip()
            g = thread_group(pid, tid, name)
            if g == 3 and name == "ample.roboguard" and tid != pid:
                g = 1
            v = fnum(pcpu)
            sums[g] += v
            st = per_thread.setdefault(tid, {"name": name, "group": g, "sum": 0.0, "max": 0.0, "n": 0, "fast": 0, "busy": 0, "ni": ni})
            st["sum"] += v; st["n"] += 1; st["max"] = max(st["max"], v)
            if v > 0.5:
                st["busy"] += 1
                if cpu.isdigit() and int(cpu) in FAST_CORES:
                    st["fast"] += 1
        thread_series.append((s["t"], sums))
    process_series = []
    for s in processes:
        sums = [0.0] * len(PROCESS_GROUPS)
        for p, cpu, ni, pcpu, name in s["rows"]:
            sums[process_group(pid, p, name.strip())] += fnum(pcpu)
        process_series.append((s["t"], sums))

    # --- canvas -------------------------------------------------------------------------------------------------------
    W = 1800
    L, R = 110, 420  # right margin holds the legends
    panels = [("RoboGuard CPU per thread group", 330), ("Whole robot CPU per process group", 300),
              ("Calendar detection: time per check, by step (2 s averages)", 300), ("Checks per second and camera frames per second", 200)]
    top_y = 120
    gap = 90
    table_rows = sorted(per_thread.items(), key=lambda kv: -kv[1]["sum"] / max(kv[1]["n"], 1))[:14]
    checks = parse_checks(data["monitor"])
    dist_h = 2 * (260 + 90) if checks else 0
    H = top_y + sum(h for _, h in panels) + gap * len(panels) + 60 + 30 * (len(table_rows) + 2) + 40 + dist_h
    img = Image.new("RGB", (W, H), SURFACE)
    g = ImageDraw.Draw(img)
    f_title, f_head, f, fs = font(28, True), font(19, True), font(16), font(14)

    started = datetime.fromtimestamp(t0).strftime("%Y-%m-%d %H:%M:%S")
    g.text((L, 24), "RoboGuard performance on the robot", fill=TEXT, font=f_title)
    sub = f"Recorded {started}, {data['seconds']} s, pid {pid}" + (f", {data['label']}" if data.get("label") else "") + \
        ("  ·  background: light blue = robot driving, light orange = avoiding an obstacle" if data.get("navigation") else "")
    g.text((L, 62), sub, fill=TEXT2, font=f)
    st_line = settings_line(data["monitor"])
    if st_line:
        g.text((L, 86), st_line[:190], fill=TEXT2, font=fs)

    def x_of(t):
        return L + (t - t0) / max(t_end - t0, 1) * (W - L - R)

    def frame(top, h, title, ymax, unit, ticks):
        g.text((L, top - 32), title, fill=TEXT, font=f_head)
        for v in ticks:
            y = top + h - v / ymax * h
            g.line([L, y, W - R, y], fill=GRID, width=1)
            g.text((L - 12 - g.textlength(f"{v:g}{unit}", font=fs), y - 8), f"{v:g}{unit}", fill=TEXT2, font=fs)
        g.line([L, top + h, W - R, top + h], fill=AXIS, width=1)
        for s in range(0, int(t_end - t0) + 1, 10):
            x = x_of(t0 + s)
            g.line([x, top + h, x, top + h + 5], fill=AXIS)
            g.text((x - 10, top + h + 8), f"{s} s", fill=TEXT2, font=fs)

    def legend(top, items, extra=None):
        x, y = W - R + 30, top
        for name, color in items:
            g.rounded_rectangle([x, y + 3, x + 16, y + 17], radius=3, fill=color)
            g.text((x + 24, y), name, fill=TEXT, font=f)
            y += 26
        if extra:
            for line in extra:
                g.text((x, y + 6), line, fill=TEXT2, font=fs)
                y += 20

    drives = drive_intervals(data.get("navigation", []), t_end)

    def shade(top, h):
        """Driving phases as light background bands: blue = driving, orange = avoiding an obstacle (turning)."""
        for a, b, avoiding in sorted(drives, key=lambda d: d[2]):
            x0, x1 = max(x_of(a), L), min(x_of(b), W - R)
            if x1 > x0:
                g.rectangle([x0, top, x1, top + h], fill="#fbe3d6" if avoiding else "#dcebfa")

    def stacked_area(top, h, series, ymax, colors):
        if len(series) < 2:
            return
        base = [0.0] * len(series)
        for k, color in enumerate(colors):
            upper = [base[i] + series[i][1][k] for i in range(len(series))]
            pts = [(x_of(series[i][0]), top + h - min(upper[i], ymax) / ymax * h) for i in range(len(series))]
            pts += [(x_of(series[i][0]), top + h - min(base[i], ymax) / ymax * h) for i in reversed(range(len(series)))]
            g.polygon(pts, fill=color)
            # 1 px surface line between layers so adjacent fills stay distinguishable.
            g.line([(x_of(series[i][0]), top + h - min(upper[i], ymax) / ymax * h) for i in range(len(series))], fill=SURFACE, width=1)
            base = upper

    y = top_y + 30
    # 1. threads
    h = panels[0][1]
    tmax = max([sum(s) for _, s in thread_series] + [100])
    ymax = max(100, (int(tmax / 50) + 1) * 50)
    shade(y, h)
    frame(y, h, panels[0][0], ymax, " %", [v for v in range(0, int(ymax) + 1, 50)])
    stacked_area(y, h, thread_series, ymax, [c for _, c in THREAD_GROUPS])
    avgs = [sum(s[k] for _, s in thread_series) / max(len(thread_series), 1) for k in range(len(THREAD_GROUPS))]
    legend(y, [(f"{n}  ({a:.0f} %)", c) for (n, c), a in zip(THREAD_GROUPS, avgs)], ["100 % = one core fully busy", "(average over the run in brackets)"])
    y += h + gap

    # 2. processes
    h = panels[1][1]
    shade(y, h)
    frame(y, h, panels[1][0], 800, " %", [0, 200, 400, 600, 800])
    stacked_area(y, h, process_series, 800, [c for _, c in PROCESS_GROUPS])
    pav = [sum(s[k] for _, s in process_series) / max(len(process_series), 1) for k in range(len(PROCESS_GROUPS))]
    legend(y, [(f"{n}  ({a:.0f} %)", c) for (n, c), a in zip(PROCESS_GROUPS, pav)], ["800 % = all 8 cores busy", f"robot total ≈ {sum(pav):.0f} %"])
    y += h + gap

    # 3. step times (stacked bars per 2 s window)
    h = panels[2][1]
    smax = max([w.get("pink", {}).get("avg", w["avg"]) for w in windows] + [100])
    smax = (int(smax / 100) + 1) * 100
    shade(y, h)
    frame(y, h, panels[2][0], smax, " ms", [v for v in range(0, smax + 1, max(100, smax // 5 // 100 * 100))])
    bar_w = max(4, (W - L - R) / max((t_end - t0) / 2, 1) - 3)
    for w in windows:
        x = x_of(w["t"]) - bar_w
        p = w.get("pink")
        if p:
            parts = [max(0, p["avg"] - p["resize"] - p["keypoints"] - p["match"] - p["homography"]), p["resize"], p["keypoints"], p["match"], p["homography"]]
        else:
            parts = [w["colour"], 0, 0, 0, 0]
        base = 0
        for (name, color), v in zip(STEPS, parts):
            if v <= 0:
                continue
            y0 = y + h - base / smax * h
            y1 = y + h - min(base + v, smax) / smax * h
            g.rectangle([x, y1, x + bar_w, y0 - 1], fill=color)  # 1 px gap between segments
            base += v
    pink = [w["pink"] for w in windows if "pink" in w]
    extra = ["bars with only the pink colour:", "no pink frame in view (pink search only)"]
    if pink:
        n = len(pink)
        extra += ["", f"with calendar in view ({n} windows), avg ms:",
                  "keypoints %.0f · matching %.0f" % (sum(p["keypoints"] for p in pink) / n, sum(p["match"] for p in pink) / n),
                  "homography %.0f · whole check %.0f" % (sum(p["homography"] for p in pink) / n, sum(p["avg"] for p in pink) / n)]
    stages = [w["pinkStages"] for w in windows if "pinkStages" in w]
    if stages:
        k = len(stages)
        extra += ["", "pink search, avg ms per check (all):",
                  "colour image %.1f · colour ranges %.1f" % (sum(x[0] for x in stages) / k, sum(x[1] for x in stages) / k),
                  "cleaning/grouping %.1f · shapes %.1f" % (sum(x[2] for x in stages) / k, sum(x[3] for x in stages) / k)]
    legend(y, list(reversed(STEPS)), extra)
    y += h + gap

    # 4. rates
    h = panels[3][1]
    rmax = 35
    shade(y, h)
    frame(y, h, panels[3][0], rmax, "/s", [0, 10, 20, 30])
    for key, color in (("pps", SERIES[0]), ("fps", SERIES[1])):
        pts = [(x_of(w["t"]), y + h - min(w[key], rmax) / rmax * h) for w in windows]
        if len(pts) > 1:
            g.line(pts, fill=color, width=2, joint="curve")
        for px, py in pts:
            g.ellipse([px - 3, py - 3, px + 3, py + 3], fill=color)
    legend(y, [("Detection checks per second", SERIES[0]), ("Camera frames per second (accepted)", SERIES[1])])
    y += h + gap - 20

    # 5. inliers / keypoints vs distance
    if checks:
        features = sorted({c["features"] for c in checks})
        label = "this run, " + "/".join(str(x) for x in features) + " features"
        y = distance_panels(g, L, y + 40, W - L - R, 260, [(label, checks, SERIES[0])], (f_head, f, fs), W - R + 30) - 40

    # 6. per-thread table
    g.text((L, y), "Per thread (top 14 by average CPU)", fill=TEXT, font=f_head)
    y += 34
    cols = [(L, "TID"), (L + 90, "thread name"), (L + 290, "group"), (L + 620, "avg CPU"), (L + 740, "max CPU"),
            (L + 860, "on fast cores when busy"), (L + 1110, "nice")]
    for x, head in cols:
        g.text((x, y), head, fill=TEXT2, font=fs)
    y += 24
    for tid, st in table_rows:
        n = max(st["n"], 1)
        fast = f"{100 * st['fast'] / st['busy']:.0f} % of {st['busy']} busy samples" if st["busy"] else "—"
        values = [tid, st["name"], THREAD_GROUPS[st["group"]][0], f"{st['sum'] / n:.1f} %", f"{st['max']:.0f} %", fast, st["ni"]]
        g.rounded_rectangle([L - 18, y + 4, L - 8, y + 14], radius=2, fill=THREAD_GROUPS[st["group"]][1])
        for (x, _), v in zip(cols, values):
            g.text((x, y), str(v), fill=TEXT, font=f)
        y += 28

    img.save(out_path)
    return out_path


if __name__ == "__main__":
    path = sys.argv[1]
    with open(path) as fh:
        d = json.load(fh)
    print(render(d, os.path.join(os.path.dirname(path), "performance.png")))
