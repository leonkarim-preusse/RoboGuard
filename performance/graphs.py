#!/usr/bin/env python3
"""
Draws the graphics of one performance run: what the robot was doing, and what it cost.

`record.py` collects the numbers, this file turns them into pictures. Each figure answers one question and is meant to be
readable on its own, because they end up in different places (a thesis chapter, a slide, a bug hunt):

    01_overview.png        CPU per thread group and per process, detection steps, rates          (performance/plot.py)
    02_activity.png        one row per activity over the recording: driving, object in view,
                           microphone feeding the network, pieces, announcements, prompts
    03_cores.png           every CPU core separately, plus where RoboGuard's threads ran
    04_object_delay.png    how long an object needs from camera frame to decision, over time
                           and as a distribution, with the pass rate underneath
    05_voice.png           speech share, embedding and gate times, similarity readings
    06_distance.png        inliers and keypoints against the size of the pink frame (= distance)

Drawn with Pillow only (no matplotlib on this machine). `plot.py` holds the palette, the fonts and the two panels that
already existed; everything new lives here so the old graphic keeps rendering unchanged.

Nothing here can describe a person: the inputs are CPU counters, timings, counts and — for the voice panel — the
similarity NUMBERS the detector logs, never audio, never a vector, never a name from the room.
"""
import os
import re
import shutil
from datetime import datetime

from PIL import Image, ImageDraw

import plot
from plot import (AXIS, FAST_CORES, GRID, OTHER, SERIES, SURFACE, TEXT, TEXT2, binned_median,
                  distance_panels, drive_intervals, fnum, font, parse_checks, parse_monitor, settings_line)

HERE = os.path.dirname(os.path.abspath(__file__))

DRIVE = "#dbeafe"
AVOID = "#ffe4c9"


# ----------------------------------------------------------------------------------------------------------------------
# parsing the log lines that record.py collected
# ----------------------------------------------------------------------------------------------------------------------

AUDIO_RE = re.compile(r"audio per ([\d,.]+) s: speech (\d+) % · pieces (\d+) · embedding ([\d,.]+) ms"
                      r" · vad ([\d,.]+) ms per chunk · analysis ([\d,.]+) ms per frame")
# Everything after that was added later, so it is matched separately and may be missing in older runs.
LEVEL_RE = re.compile(r"· level mean (-?\d+|-Infinity) dB, max (-?\d+|-Infinity) dB, above the floor (\d+) %")
SILERO_RE = re.compile(r"· silero mean (\d+[.,]\d+), max (\d+[.,]\d+)")
CLOCK_RE = re.compile(r"· audio/clock (\d+[.,]\d+)")
GATE_LINE_RE = re.compile(r"gate: (\w+), silero (\d+[.,]\d+) \(hysteresis (\d+[.,]\d+)\), level floor (-?\d+) dBFS")
OWNER_CONFIG_RE = re.compile(r"listening for the owner's voice \(threshold (\d+[.,]\d+), margin (\d+[.,]\d+)")
PAIRWISE_CONFIG_RE = re.compile(r"comparing voices with each other \(different below (\d+[.,]\d+)")
# The repeated "settings: …" line carries every threshold, and unlike the start lines it is in every long enough run.
SET_OWNER_RE = re.compile(r"owner comparison, threshold (\d+[.,]\d+), margin (\d+[.,]\d+)")
SET_PAIR_RE = re.compile(r"compare pieces, another voice below (\d+[.,]\d+)")
SET_GATE_RE = re.compile(r"silero (\d+[.,]\d+) \(hysteresis (\d+[.,]\d+)\), floor (-?\d+) dBFS")
SET_ACTIVITY_RE = re.compile(r"speech activity, (\d+) % of the last ([\d,.]+) s, gaps up to (\d+) ms")


def fnum_or_none(text):
    return None if text in ("-Infinity", "Infinity") else fnum(text)


def parse_gate(voice):
    """The thresholds the detector actually ran with, for the lines in the plots."""
    out = {}
    for e in voice:
        m = GATE_LINE_RE.search(e["text"])
        if m:
            out.update(mode=m.group(1), silero=fnum(m.group(2)), hysteresis=fnum(m.group(3)), floor=fnum(m.group(4)))
        m = OWNER_CONFIG_RE.search(e["text"])
        if m:
            out.update(ownerThreshold=fnum(m.group(1)), ownerMargin=fnum(m.group(2)))
        m = PAIRWISE_CONFIG_RE.search(e["text"])
        if m:
            out.update(pairwiseThreshold=fnum(m.group(1)))
        if not e["text"].startswith("settings: "):
            continue
        m = SET_OWNER_RE.search(e["text"])
        if m:
            out.update(ownerThreshold=fnum(m.group(1)), ownerMargin=fnum(m.group(2)))
        m = SET_PAIR_RE.search(e["text"])
        if m:
            out.update(pairwiseThreshold=fnum(m.group(1)))
        m = SET_GATE_RE.search(e["text"])
        if m:
            out.update(silero=fnum(m.group(1)), hysteresis=fnum(m.group(2)), floor=fnum(m.group(3)))
        m = SET_ACTIVITY_RE.search(e["text"])
        if m:
            out.update(activityShare=int(m.group(1)) / 100.0, activityWindow=fnum(m.group(2)),
                       activityGapMs=int(m.group(3)))
    return out
DELAY_RE = re.compile(r"delay frame→decision: avg (\d+) ms, worst (\d+) ms over (\d+) passes")
SEEN_RE = re.compile(r"seen: (.+?) \(inliers (\d+), delay (\d+) ms\)")
GONE_RE = re.compile(r"gone: (.+)$")
ANNOUNCE_RE = re.compile(r"object detected \((.+?)\): (\S+) good (\d+), inliers (\d+), pink regions (\d+)(?:, delay (\d+) ms)?")
SIM_RE = re.compile(r"piece: similarity ([\d,.-]+) -> (\w+)")
PAIR_RE = re.compile(r"piece: lowest similarity to the (\d+) held pieces ([\d,.-]+) -> (.+)$")


def parse_audio(voice):
    """The 2 s load line of whichever voice detector was running (AudioLoad.kt)."""
    out = []
    for e in voice:
        m = AUDIO_RE.search(e["text"])
        if m:
            row = {"t": e["t"], "tag": e.get("tag", ""), "seconds": fnum(m.group(1)), "speech": int(m.group(2)),
                   "pieces": int(m.group(3)), "embed": fnum(m.group(4)), "vad": fnum(m.group(5)),
                   "analysis": fnum(m.group(6))}
            lv = LEVEL_RE.search(e["text"])
            if lv:
                row.update(levelMean=fnum_or_none(lv.group(1)), levelMax=fnum_or_none(lv.group(2)),
                           aboveFloor=int(lv.group(3)))
            si = SILERO_RE.search(e["text"])
            if si:
                row.update(sileroMean=fnum(si.group(1)), sileroMax=fnum(si.group(2)))
            ck = CLOCK_RE.search(e["text"])
            if ck:
                row["clock"] = fnum(ck.group(1))
            out.append(row)
    return out


def parse_similarity(voice):
    """Per-piece readings: the owner comparison, or the lowest pairwise similarity. Numbers only."""
    out = []
    for e in voice:
        m = SIM_RE.search(e["text"])
        if m:
            out.append({"t": e["t"], "value": fnum(m.group(1)), "verdict": m.group(2), "kind": "owner"})
            continue
        m = PAIR_RE.search(e["text"])
        if m:
            out.append({"t": e["t"], "value": fnum(m.group(2)), "verdict": m.group(3), "kind": "pairwise",
                        "held": int(m.group(1))})
    return out


def parse_delays(lines):
    out = []
    for e in lines:
        m = DELAY_RE.search(e["text"])
        if m:
            out.append({"t": e["t"], "avg": int(m.group(1)), "worst": int(m.group(2)), "passes": int(m.group(3))})
    return out


def parse_announcements(lines):
    out = []
    for e in lines:
        m = ANNOUNCE_RE.search(e["text"])
        if m:
            out.append({"t": e["t"], "name": m.group(2), "inliers": int(m.group(4)), "regions": int(m.group(5)),
                        "delay": int(m.group(6)) if m.group(6) else None})
    return out


def parse_seen(lines, t_end):
    """"seen"/"gone" pairs per reference image -> {name: [(start, end)]}."""
    spans, open_at = {}, {}
    for e in lines:
        m = SEEN_RE.search(e["text"])
        if m:
            open_at.setdefault(m.group(1), e["t"])
            continue
        m = GONE_RE.search(e["text"])
        if m and m.group(1) in open_at:
            spans.setdefault(m.group(1), []).append((open_at.pop(m.group(1)), e["t"]))
    for name, start in open_at.items():
        spans.setdefault(name, []).append((start, t_end))
    return spans


def parse_speaking(voice, t_end):
    """Spans in which the robot itself was playing sound, i.e. the microphone was deliberately ignored."""
    spans, start = [], None
    for e in voice:
        if "robot is speaking" in e["text"] and start is None:
            start = e["t"]
        elif "robot stopped speaking" in e["text"] and start is not None:
            spans.append((start, e["t"]))
            start = None
    if start is not None:
        spans.append((start, t_end))
    return spans


def parse_events(entries, needle):
    return [e["t"] for e in entries if needle in e["text"]]


# ----------------------------------------------------------------------------------------------------------------------
# a small drawing toolkit (Pillow only): one figure = title + a stack of panels over a shared time axis
# ----------------------------------------------------------------------------------------------------------------------

def wrap(text, draw, font_, width):
    """Breaks a sentence into lines that fit into `width` pixels."""
    if not text:
        return []
    lines, line = [], ""
    for word in text.split():
        candidate = (line + " " + word).strip()
        if draw.textlength(candidate, font=font_) > width and line:
            lines.append(line)
            line = word
        else:
            line = candidate
    if line:
        lines.append(line)
    return lines


class Figure:
    def __init__(self, title, subtitle, t0, t_end, width=1800, left=150, right=430, note=""):
        self.W, self.L, self.R = width, left, right
        self.t0, self.t_end = t0, max(t_end, t0 + 1)
        self.img = Image.new("RGB", (width, 8000), SURFACE)
        self.g = ImageDraw.Draw(self.img)
        self.f_title, self.f_head, self.f, self.fs = font(28, True), font(19, True), font(16), font(14)
        self.g.text((left, 24), title, fill=TEXT, font=self.f_title)
        self.g.text((left, 62), subtitle, fill=TEXT2, font=self.f)
        y = 88
        # The notes are full sentences; wrap them to the drawing width instead of letting them run off the page.
        for line in wrap(note, self.g, self.fs, width - left - 40):
            self.g.text((left, y), line, fill=TEXT2, font=self.fs)
            y += 20
        self.y = y + (28 if note else 14)
        self.drives = []

    def x_of(self, t):
        # Clamped: a log line can be a fraction of a second older than the first CPU sample, and it must not be drawn
        # into the left margin where the row labels are.
        share = min(max((t - self.t0) / (self.t_end - self.t0), 0.0), 1.0)
        return self.L + share * (self.W - self.L - self.R)

    def shade(self, top, height):
        """Light blue while the robot drove, light orange while it avoided an obstacle."""
        for a, b, avoid in self.drives:
            x0, x1 = self.x_of(max(a, self.t0)), self.x_of(min(b, self.t_end))
            if x1 > x0:
                self.g.rectangle([x0, top, x1, top + height], fill=AVOID if avoid else DRIVE)

    def time_axis(self, top, height, labels=True):
        step = 10 if self.t_end - self.t0 <= 180 else 30
        for s in range(0, int(self.t_end - self.t0) + 1, step):
            x = self.x_of(self.t0 + s)
            self.g.line([x, top, x, top + height], fill=GRID)
            if labels:
                self.g.line([x, top + height, x, top + height + 5], fill=AXIS)
                self.g.text((x - 8, top + height + 8), f"{s}s", fill=TEXT2, font=self.fs)

    def panel(self, title, height, ymax, unit, ticks, subtitle=""):
        """A framed panel over the time axis. Returns (top, y_of) for the caller to draw into."""
        self.g.text((self.L, self.y), title, fill=TEXT, font=self.f_head)
        if subtitle:
            self.g.text((self.L + self.g.textlength(title, font=self.f_head) + 16, self.y + 4), subtitle,
                        fill=TEXT2, font=self.fs)
        top = self.y + 30
        self.shade(top, height)
        self.time_axis(top, height)
        for v in ticks:
            y = top + height - (v / ymax if ymax else 0) * height
            self.g.line([self.L, y, self.W - self.R, y], fill=GRID)
            text = f"{v:g}{unit}"
            self.g.text((self.L - 12 - self.g.textlength(text, font=self.fs), y - 8), text, fill=TEXT2, font=self.fs)
        self.g.line([self.L, top + height, self.W - self.R, top + height], fill=AXIS)
        self.y = top + height + 62

        def y_of(v):
            return top + height - min(max(v, 0), ymax) / (ymax or 1) * height

        return top, y_of

    def legend(self, top, items):
        x, y = self.W - self.R + 30, top
        for label, colour in items:
            self.g.rectangle([x, y + 3, x + 14, y + 15], fill=colour)
            self.g.text((x + 22, y), label, fill=TEXT2, font=self.fs)
            y += 22

    def threshold(self, y, colour, label, x_label_at=None):
        """A dashed horizontal line with a label: the value a curve is being compared against."""
        x = self.L
        while x < self.W - self.R:
            self.g.line([x, y, min(x + 8, self.W - self.R), y], fill=colour, width=2)
            x += 14
        self.g.text((x_label_at or (self.L + 10), y - 18), label, fill=colour, font=self.fs)

    def line(self, y_of, points, colour, width=2, dots=False):
        pts = [(self.x_of(t), y_of(v)) for t, v in points]
        if len(pts) > 1:
            self.g.line([c for p in pts for c in p], fill=colour, width=width, joint="curve")
        if dots:
            for x, y in pts:
                self.g.ellipse([x - 3, y - 3, x + 3, y + 3], fill=colour)

    def area(self, top, height, y_of, points, colour):
        if len(points) < 2:
            return
        pts = [(self.x_of(t), y_of(v)) for t, v in points]
        self.g.polygon([(pts[0][0], top + height)] + pts + [(pts[-1][0], top + height)], fill=colour)

    def save(self, path):
        self.img.crop((0, 0, self.W, int(self.y) + 20)).save(path)
        return path


# ----------------------------------------------------------------------------------------------------------------------
# figure 2: what the robot was doing
# ----------------------------------------------------------------------------------------------------------------------

def activity_figure(data, out_path, t0, t_end, windows):
    monitor, voice = data["monitor"], data.get("voice", [])
    seen = parse_seen(monitor, t_end)
    audio = parse_audio(voice)
    announcements = parse_announcements(monitor)
    similarity = parse_similarity(voice)
    speaking = parse_speaking(voice, t_end)
    prompts = parse_events(voice, "showing prompt")
    resets = parse_events(voice, "reset after")

    # A wide left margin: the row labels are sentences, not numbers.
    fig = Figure("What the robot was doing", subtitle_for(data, t0),
                 t0, t_end, left=360, right=260,
                 note="One row per activity. A filled bar means the activity was running; a tick marks a single event. "
                      "Bars with a height show a share per 2 s window.")
    fig.drives = drive_intervals(data.get("navigation", []), t_end)

    rows = []
    rows.append(("Driving", SERIES[0], "span", [(a, b) for a, b, avoid in fig.drives if not avoid]))
    rows.append(("Avoiding an obstacle", SERIES[3], "span", [(a, b) for a, b, avoid in fig.drives if avoid]))
    for name in sorted(seen):
        rows.append((f"In view: {name}", SERIES[2], "span", seen[name]))
    rows.append(("Object announced (spoken)", SERIES[1], "event", [a["t"] for a in announcements]))
    if windows:
        rows.append(("Camera: pink area found", SERIES[4], "level",
                     [(w["t"], min(w.get("regions", 0), 1.0)) for w in windows]))
        rows.append(("Object seen in the pass (share)", SERIES[2], "level",
                     [(w["t"], w.get("detected", 0) / max(w.get("passes", 1), 1)) for w in windows]))
        rows.append(("Object detection running", SERIES[0], "level",
                     [(w["t"], min(w["pps"] / 12.0, 1.0)) for w in windows]))
    if audio:
        rows.append(("Microphone: speech into the network", SERIES[5], "level",
                     [(a["t"], a["speech"] / 100.0) for a in audio]))
    rows.append(("Voice piece embedded", SERIES[6], "event", [s["t"] for s in similarity]))
    rows.append(("Robot speaking (microphone ignored)", OTHER, "span", speaking))
    rows.append(("Conversation: silence reset", SERIES[3], "event", resets))
    rows.append(("Conversation prompt shown", SERIES[1], "event", prompts))

    row_h, gap = 26, 12
    height = len(rows) * (row_h + gap)
    fig.g.text((fig.L, fig.y), "Activity timeline", fill=TEXT, font=fig.f_head)
    top = fig.y + 30
    fig.shade(top, height)
    fig.time_axis(top, height)
    y = top
    for label, colour, kind, items in rows:
        fig.g.text((fig.L - 12 - fig.g.textlength(label, font=fig.fs), y + 5), label, fill=TEXT2, font=fig.fs)
        fig.g.rectangle([fig.L, y, fig.W - fig.R, y + row_h], outline=GRID)
        if kind == "span":
            for a, b in items:
                x0, x1 = fig.x_of(max(a, t0)), fig.x_of(min(b, t_end))
                fig.g.rectangle([x0, y + 2, max(x1, x0 + 2), y + row_h - 2], fill=colour)
        elif kind == "event":
            for t in items:
                x = fig.x_of(t)
                fig.g.rectangle([x - 2, y + 2, x + 2, y + row_h - 2], fill=colour)
        else:  # level
            width = max((fig.W - fig.R - fig.L) / max(len(items), 1) - 1, 2)
            for t, v in items:
                # A window with nothing in it stays empty: a 1 px bar everywhere would read as a line through the row.
                if v <= 0:
                    continue
                x = fig.x_of(t)
                h = max(v * (row_h - 4), 2)
                fig.g.rectangle([x - width, y + row_h - 2 - h, x, y + row_h - 2], fill=colour)
        y += row_h + gap
    fig.y = top + height + 70

    _cpu_context(fig, data, t0)
    return fig.save(out_path)


def _cpu_context(fig, data, t0):
    """A CPU line under the activity rows, so a busy phase can be read against what was running."""
    threads = data["threads"]
    if not threads:
        return
    total = [(s["t"], sum(fnum(r[3]) for r in s["rows"])) for s in threads]
    ymax = max(200, int(max(v for _, v in total) / 100 + 1) * 100)
    top, y_of = fig.panel("RoboGuard CPU in total", 180, ymax, " %", list(range(0, ymax + 1, 100)),
                          "sum over all its threads; 100 % = one core")
    fig.area(top, 180, y_of, total, "#e8effa")
    fig.line(y_of, total, SERIES[0])
    cores = data.get("cores", [])
    if cores:
        busy = [(c["t"], sum(v for v in c["cores"].values() if v >= 0)) for c in cores]
        top, y_of = fig.panel("Whole robot: all cores together", 180, 800, " %", [0, 200, 400, 600, 800],
                              "800 % = all eight cores fully busy")
        fig.area(top, 180, y_of, busy, "#fdece4")
        fig.line(y_of, busy, SERIES[1])


# ----------------------------------------------------------------------------------------------------------------------
# figure 3: the cores
# ----------------------------------------------------------------------------------------------------------------------

def cores_figure(data, out_path, t0, t_end):
    cores = data.get("cores", [])
    if not cores:
        return None
    names = sorted({n for c in cores for n in c["cores"]}, key=lambda n: int(n[3:]))
    fig = Figure("Every CPU core separately", subtitle_for(data, t0), t0, t_end,
                 note="Cores 0–3 are the small (efficiency) cores, 4–7 the big ones. A core that reports no ticks at all "
                      "was parked by the kernel and is drawn as a grey band, which is not the same as 0 % busy.")
    fig.drives = drive_intervals(data.get("navigation", []), t_end)

    for name in names:
        colour = SERIES[0] if int(name[3:]) in FAST_CORES else SERIES[2]
        kind = "big core" if int(name[3:]) in FAST_CORES else "small core"
        top, y_of = fig.panel(f"{name}", 90, 100, " %", [0, 50, 100], kind)
        points = [(c["t"], c["cores"].get(name, -1)) for c in cores]
        for (ta, va), (tb, _) in zip(points, points[1:]):
            if va < 0:
                fig.g.rectangle([fig.x_of(ta), top, fig.x_of(tb), top + 90], fill="#eeeeec")
        awake = [(t, v) for t, v in points if v >= 0]
        fig.area(top, 90, y_of, awake, "#e8effa" if colour == SERIES[0] else "#e4f5ee")
        fig.line(y_of, awake, colour)

    # average per core as bars, and the share of busy samples RoboGuard's threads spent on the big cores
    averages = []
    for name in names:
        vals = [c["cores"][name] for c in cores if c["cores"].get(name, -1) >= 0]
        averages.append((name, sum(vals) / len(vals) if vals else 0.0, len(vals)))
    fig.g.text((fig.L, fig.y), "Average load per core over the whole run", fill=TEXT, font=fig.f_head)
    top = fig.y + 34
    h, bw = 200, (fig.W - fig.L - fig.R) / (len(names) * 2)
    for v in (0, 25, 50, 75, 100):
        y = top + h - v / 100 * h
        fig.g.line([fig.L, y, fig.W - fig.R, y], fill=GRID)
        fig.g.text((fig.L - 12 - fig.g.textlength(f"{v} %", font=fig.fs), y - 8), f"{v} %", fill=TEXT2, font=fig.fs)
    for i, (name, avg, n) in enumerate(averages):
        x = fig.L + (i * 2 + 0.5) * bw
        colour = SERIES[0] if int(name[3:]) in FAST_CORES else SERIES[2]
        fig.g.rectangle([x, top + h - avg / 100 * h, x + bw, top + h], fill=colour)
        fig.g.text((x, top + h + 8), name, fill=TEXT2, font=fig.fs)
        fig.g.text((x, top + h + 26), f"{avg:.0f} %", fill=TEXT2, font=fig.fs)
        if n < len(cores):
            fig.g.text((x, top + h + 44), f"asleep {len(cores) - n}×", fill=TEXT2, font=fig.fs)
    fig.g.line([fig.L, top + h, fig.W - fig.R, top + h], fill=AXIS)
    fig.y = top + h + 90

    _thread_cores(fig, data)
    return fig.save(out_path)


def _thread_cores(fig, data):
    """Where RoboGuard's own threads ran: share of their busy samples that landed on a big core."""
    pid = data["pid"]
    per = {}
    for s in data["threads"]:
        for tid, core, ni, pcpu, name in s["rows"]:
            v = fnum(pcpu)
            if v <= 0.5:
                continue
            st = per.setdefault(tid, {"name": name.strip(), "busy": 0, "fast": 0, "sum": 0.0, "nice": ni})
            st["busy"] += 1
            st["sum"] += v
            if core.isdigit() and int(core) in FAST_CORES:
                st["fast"] += 1
    rows = sorted(per.items(), key=lambda kv: -kv[1]["sum"])[:16]
    fig.g.text((fig.L, fig.y), "RoboGuard threads: how much CPU, and on which cores", fill=TEXT, font=fig.f_head)
    y = fig.y + 34
    headers = [("thread", 0), ("nice", 430), ("CPU % (avg over busy samples)", 520), ("busy samples", 900),
               ("on big cores", 1060)]
    for label, dx in headers:
        fig.g.text((fig.L + dx, y), label, fill=TEXT2, font=fig.fs)
    y += 24
    for tid, st in rows:
        avg = st["sum"] / max(st["busy"], 1)
        share = st["fast"] / max(st["busy"], 1) * 100
        name = st["name"] + (" (main/UI)" if tid == pid else "")
        fig.g.text((fig.L, y), name[:44], fill=TEXT, font=fig.fs)
        fig.g.text((fig.L + 430, y), st["nice"], fill=TEXT2, font=fig.fs)
        fig.g.rectangle([fig.L + 520, y + 3, fig.L + 520 + min(avg, 100) * 3.4, y + 15], fill=SERIES[0])
        fig.g.text((fig.L + 520 + min(avg, 100) * 3.4 + 8, y), f"{avg:.0f}", fill=TEXT2, font=fig.fs)
        fig.g.text((fig.L + 900, y), str(st["busy"]), fill=TEXT2, font=fig.fs)
        fig.g.text((fig.L + 1060, y), f"{share:.0f} %", fill=TEXT2, font=fig.fs)
        y += 22
    fig.y = y + 30


# ----------------------------------------------------------------------------------------------------------------------
# figure 4: the delay of the object detection
# ----------------------------------------------------------------------------------------------------------------------

def delay_figure(data, out_path, t0, t_end, windows):
    monitor = data["monitor"]
    delays = parse_delays(monitor)
    announcements = parse_announcements(monitor)
    seen = parse_seen(monitor, t_end)
    if not delays and not announcements:
        return None
    fig = Figure("Object detection: how long it takes", subtitle_for(data, t0), t0, t_end,
                 note="Delay = from the moment the camera frame arrived to the moment the pass decided about it. It is "
                      "longer than one pass, because a frame waits while both workers are busy; the announcement adds the "
                      "confirmation rule and the speech on top.")
    fig.drives = drive_intervals(data.get("navigation", []), t_end)

    if delays:
        ymax = max(200, int(max(d["worst"] for d in delays) / 100 + 1) * 100)
        top, y_of = fig.panel("Frame → decision", 260, ymax, " ms", list(range(0, ymax + 1, max(100, ymax // 5 // 100 * 100))),
                              "per 2 s window")
        fig.area(top, 260, y_of, [(d["t"], d["worst"]) for d in delays], "#fdece4")
        fig.line(y_of, [(d["t"], d["worst"]) for d in delays], SERIES[1])
        fig.line(y_of, [(d["t"], d["avg"]) for d in delays], SERIES[0], dots=True)
        for a in announcements:
            if a["delay"] is not None:
                x, y = fig.x_of(a["t"]), y_of(a["delay"])
                fig.g.ellipse([x - 5, y - 5, x + 5, y + 5], outline=SERIES[4], width=3)
        fig.legend(top, [("worst in the window", SERIES[1]), ("average", SERIES[0]),
                         ("the pass that announced", SERIES[4])])

        # distribution: how often which delay
        values = sorted(d["avg"] for d in delays)
        fig.g.text((fig.L, fig.y), "Distribution of the average delay", fill=TEXT, font=fig.f_head)
        top = fig.y + 34
        h = 200
        bins = {}
        step = max(50, ymax // 20 // 50 * 50)
        for v in values:
            bins[int(v // step)] = bins.get(int(v // step), 0) + 1
        top_count = max(bins.values()) if bins else 1
        bw = (fig.W - fig.L - fig.R) / max(len(bins), 1)
        for i, b in enumerate(sorted(bins)):
            x = fig.L + i * bw
            height = bins[b] / top_count * h
            fig.g.rectangle([x + 2, top + h - height, x + bw - 2, top + h], fill=SERIES[0])
            fig.g.text((x + 6, top + h + 8), f"{b * step}–{(b + 1) * step} ms", fill=TEXT2, font=fig.fs)
            fig.g.text((x + 6, top + h + 26), f"{bins[b]}×", fill=TEXT2, font=fig.fs)
        fig.g.line([fig.L, top + h, fig.W - fig.R, top + h], fill=AXIS)
        median = values[len(values) // 2]
        fig.g.text((fig.W - fig.R + 30, top), f"median {median:.0f} ms", fill=TEXT2, font=fig.fs)
        fig.g.text((fig.W - fig.R + 30, top + 22), f"worst {max(d['worst'] for d in delays)} ms", fill=TEXT2, font=fig.fs)
        fig.y = top + h + 90

    if windows:
        pmax = max(4, int(max(w["pps"] for w in windows) + 1))
        top, y_of = fig.panel("Checks per second and camera frames per second", 200, max(pmax, 30), "/s",
                              [0, 10, 20, 30], "how often a frame was looked at at all")
        fig.line(y_of, [(w["t"], w["fps"]) for w in windows], SERIES[2])
        fig.line(y_of, [(w["t"], w["pps"]) for w in windows], SERIES[0], dots=True)
        fig.legend(top, [("camera fps", SERIES[2]), ("detection passes/s", SERIES[0])])

        steps = [(w["t"], w.get("pink", {}).get("avg", 0)) for w in windows if w.get("pink")]
        if steps:
            smax = max(100, int(max(v for _, v in steps) / 100 + 1) * 100)
            top, y_of = fig.panel("Time of one pass that had a pink area to check", 200, smax, " ms",
                                  list(range(0, smax + 1, max(100, smax // 5 // 100 * 100))), "average per 2 s window")
            fig.area(top, 200, y_of, steps, "#e8effa")
            fig.line(y_of, steps, SERIES[0], dots=True)

    # how long each object stayed in view, and the gaps between announcements
    if seen:
        fig.g.text((fig.L, fig.y), "Every time an object was in view", fill=TEXT, font=fig.f_head)
        y = fig.y + 34
        for name in sorted(seen):
            total = sum(b - a for a, b in seen[name])
            longest = max(b - a for a, b in seen[name])
            fig.g.text((fig.L, y), f"{name}: {len(seen[name])} times in view, {total:.1f} s in total, "
                                   f"longest {longest:.1f} s", fill=TEXT, font=fig.f)
            y += 24
        if announcements:
            times = [a["t"] for a in announcements]
            gaps = [b - a for a, b in zip(times, times[1:])]
            text = f"{len(announcements)} announcements"
            if gaps:
                text += f", gap between them {min(gaps):.1f}–{max(gaps):.1f} s"
            fig.g.text((fig.L, y), text, fill=TEXT, font=fig.f)
            y += 24
        fig.y = y + 30
    return fig.save(out_path)


# ----------------------------------------------------------------------------------------------------------------------
# figure 5: the voice path
# ----------------------------------------------------------------------------------------------------------------------

TAG_TO_MODE = {"OwnerVoice": "OWNER", "PairwiseVoice": "PAIRWISE", "SpeechActivity": "ACTIVITY",
               "SpeakerChange": "CHANGE"}


def voice_mode(voice, audio):
    """Which method produced these lines, for the file name and the header."""
    for a in audio:
        if a.get("tag") in TAG_TO_MODE:
            return TAG_TO_MODE[a["tag"]]
    for e in voice:
        if e.get("tag") in TAG_TO_MODE:
            return TAG_TO_MODE[e["tag"]]
    return "VOICE"


def voice_settings(voice):
    """
    The settings the run actually used, taken verbatim from the detector's own line (AudioLoad repeats it every 30 s, so a
    recording that starts later still catches it).
    """
    for e in reversed(voice):
        if e["text"].startswith("settings: "):
            return e["text"][len("settings: "):]
    for e in voice:
        if e["text"].startswith("config: "):
            return e["text"][len("config: "):]
    return ""


def voice_figure(data, out_path, t0, t_end):
    voice = data.get("voice", [])
    audio = parse_audio(voice)
    similarity = parse_similarity(voice)
    if not audio and not similarity:
        return None
    mode = voice_mode(voice, audio)
    settings = voice_settings(voice)
    note = f"Settings: {settings}" if settings else f"Method: {mode}"
    fig = Figure("Voice detection: load and readings", subtitle_for(data, t0), t0, t_end, note=note)
    fig.drives = drive_intervals(data.get("navigation", []), t_end)

    gate = parse_gate(voice)

    levels = [(a["t"], a["levelMean"]) for a in audio if a.get("levelMean") is not None]
    if levels:
        floor = gate.get("floor", -65.0)
        lo = min(min(v for _, v in levels), floor) - 5
        hi = max([v for a_ in audio for v in [a_.get("levelMax")] if v is not None] + [floor]) + 5
        span = hi - lo
        top, y_raw = fig.panel("Microphone level", 240, 1.0, "", [], "mean and loudest frame per 2 s window")

        def y_level(v):
            return y_raw((v - lo) / span)

        for v in [int(hi // 10 * 10) - 10 * k for k in range(6)]:
            if lo <= v <= hi:
                fig.g.line([fig.L, y_level(v), fig.W - fig.R, y_level(v)], fill=GRID)
                fig.g.text((fig.L - 12 - fig.g.textlength(f"{v} dB", font=fig.fs), y_level(v) - 8), f"{v} dB",
                           fill=TEXT2, font=fig.fs)
        maxes = [(a["t"], a["levelMax"]) for a in audio if a.get("levelMax") is not None]
        fig.line(y_level, maxes, "#9dc0ea")
        fig.line(y_level, levels, SERIES[0], dots=True)
        fig.threshold(y_level(floor), SERIES[1], "noise gate %.0f dB" % floor)
        fig.legend(top, [("loudest frame in the window", "#9dc0ea"), ("mean of the window", SERIES[0]),
                         ("the gate's floor", SERIES[1])])

    probs = [(a["t"], a["sileroMean"]) for a in audio if a.get("sileroMean") is not None]
    if probs:
        threshold = gate.get("silero", 0.5)
        hysteresis = gate.get("hysteresis", 0.15)
        top, y_of = fig.panel("Silero speech probability", 240, 1.0, "", [0, 0.25, 0.5, 0.75, 1.0],
                              "mean and highest of the window")
        fig.line(y_of, [(a["t"], a["sileroMax"]) for a in audio if a.get("sileroMax") is not None], "#f5c0a6")
        fig.line(y_of, probs, SERIES[1], dots=True)
        fig.threshold(y_of(threshold), SERIES[4], "speech from %.2f" % threshold)
        if hysteresis > 0:
            fig.threshold(y_of(threshold - hysteresis), "#c9c8c3", "ends below %.2f" % (threshold - hysteresis))
        fig.legend(top, [("highest in the window", "#f5c0a6"), ("mean of the window", SERIES[1]),
                         ("threshold", SERIES[4]), ("with hysteresis", "#c9c8c3")])

    if audio:
        top, y_of = fig.panel("Share of the time that counted as speech", 200, 100, " %", [0, 25, 50, 75, 100],
                              "per 2 s window; the rest is silence, noise or the robot's own voice")
        fig.area(top, 200, y_of, [(a["t"], a["speech"]) for a in audio], "#e4f0e9")
        fig.line(y_of, [(a["t"], a["speech"]) for a in audio], SERIES[5])

        emb = [(a["t"], a["embed"]) for a in audio if a["pieces"] > 0]
        if emb:
            emax = max(100, int(max(v for _, v in emb) / 50 + 1) * 50)
            top, y_of = fig.panel("One embedding (CAM++ on 3 s of speech)", 200, emax, " ms",
                                  list(range(0, emax + 1, max(50, emax // 4 // 50 * 50))), "average per window")
            fig.line(y_of, emb, SERIES[6], dots=True)
            fig.legend(top, [(f"median {sorted(v for _, v in emb)[len(emb) // 2]:.0f} ms", SERIES[6])])

        vmax = max(2.0, max(a["vad"] for a in audio) * 1.2)
        top, y_of = fig.panel("Voice-activity gate per 32 ms chunk", 160, vmax, " ms",
                              [round(vmax * i / 4, 1) for i in range(5)], "Silero, runs on every chunk")
        fig.line(y_of, [(a["t"], a["vad"]) for a in audio], SERIES[3], dots=True)

        top, y_of = fig.panel("Pieces sent into the speaker network", 140, max(3, max(a["pieces"] for a in audio)), "",
                              list(range(0, max(3, max(a["pieces"] for a in audio)) + 1)), "per 2 s window")
        bw = max((fig.W - fig.R - fig.L) / max(len(audio), 1) - 2, 3)
        for a in audio:
            if a["pieces"]:
                x = fig.x_of(a["t"])
                fig.g.rectangle([x - bw, y_of(a["pieces"]), x, top + 140], fill=SERIES[6])

    if similarity:
        # The panel is drawn WITHOUT ticks and gets its own grid below, because the values are not on a 0…1 scale: after
        # cohort centring they run from about −0.4 to +0.6, and drawing a 0…1 axis under points that are mapped to the
        # data range put the dots next to their own grid lines (owner, 2026-09-28).
        owner_threshold = gate.get("ownerThreshold")
        pair_threshold = gate.get("pairwiseThreshold")
        marks = [v for v in (owner_threshold,
                             None if owner_threshold is None else owner_threshold - gate.get("ownerMargin", 0.02),
                             pair_threshold) if v is not None]
        values = [s["value"] for s in similarity] + marks
        lo, hi = min(values) - 0.05, max(values) + 0.05
        span = max(hi - lo, 0.1)
        top, y_raw = fig.panel("Similarity of every piece", 260, 1.0, "", [],
                               "owner comparison, or the lowest pairwise similarity")

        def y_of(v):
            return y_raw((v - lo) / span)

        for i in range(0, 5):
            v = lo + span * i / 4
            y = y_of(v)
            fig.g.line([fig.L, y, fig.W - fig.R, y], fill=GRID)
            fig.g.text((fig.L - 12 - fig.g.textlength("%.2f" % v, font=fig.fs), y - 8), "%.2f" % v,
                       fill=TEXT2, font=fig.fs)
        # The lines a reading is judged against: the owner threshold and, below it by the margin, where a piece starts
        # counting as somebody else; or the single pairwise threshold.
        if owner_threshold is not None:
            fig.threshold(y_of(owner_threshold), SERIES[2], "owner from %.2f" % owner_threshold)
            other = owner_threshold - gate.get("ownerMargin", 0.02)
            fig.threshold(y_of(other), SERIES[1], "somebody else below %.2f" % other,
                          x_label_at=fig.L + 240)
        if pair_threshold is not None:
            fig.threshold(y_of(pair_threshold), SERIES[1], "another voice below %.2f" % pair_threshold)
        colours = {"owner": SERIES[2], "somebody": SERIES[1], "unclear": OTHER}
        for s in similarity:
            x, y = fig.x_of(s["t"]), y_of(s["value"])
            colour = colours.get(s["verdict"].split()[0], SERIES[6])
            fig.g.ellipse([x - 4, y - 4, x + 4, y + 4], fill=colour)
        fig.legend(top, [("owner / same voice", SERIES[2]), ("somebody else", SERIES[1]), ("unclear", OTHER)])

        # Both of those in one picture (owner request): the gate's probability and what the comparison made of it, on the
        # same time axis. Left scale = probability, right scale = similarity; the dashed lines are their thresholds.
        if probs:
            top, y_raw = fig.panel("Speech probability and the readings together", 320, 1.0, "", [],
                                   "left: Silero probability · right: similarity of the piece")

            def y_prob(v):
                return y_raw(v)

            def y_sim(v):
                return y_raw((v - lo) / span)

            for v in (0.0, 0.25, 0.5, 0.75, 1.0):
                y = y_prob(v)
                fig.g.line([fig.L, y, fig.W - fig.R, y], fill=GRID)
                fig.g.text((fig.L - 12 - fig.g.textlength("%.2f" % v, font=fig.fs), y - 8), "%.2f" % v,
                           fill=SERIES[1], font=fig.fs)
            # The similarity scale is written INSIDE the right edge, so it cannot collide with the legend.
            for i in range(0, 5):
                v = lo + span * i / 4
                y = y_sim(v)
                fig.g.text((fig.W - fig.R - 8 - fig.g.textlength("%.2f" % v, font=fig.fs), y - 8), "%.2f" % v,
                           fill=SERIES[2], font=fig.fs)
            fig.line(y_prob, [(a_["t"], a_["sileroMax"]) for a_ in audio if a_.get("sileroMax") is not None], "#f5c0a6")
            fig.line(y_prob, probs, SERIES[1])
            fig.threshold(y_prob(gate.get("silero", 0.5)), SERIES[4],
                          "speech from %.2f" % gate.get("silero", 0.5))
            if owner_threshold is not None:
                fig.threshold(y_sim(owner_threshold), SERIES[2], "owner from %.2f" % owner_threshold,
                              x_label_at=fig.L + 300)
            if pair_threshold is not None:
                fig.threshold(y_sim(pair_threshold), SERIES[2], "another voice below %.2f" % pair_threshold,
                              x_label_at=fig.L + 300)
            for reading in similarity:
                x, y = fig.x_of(reading["t"]), y_sim(reading["value"])
                colour = colours.get(reading["verdict"].split()[0], SERIES[6])
                fig.g.ellipse([x - 5, y - 5, x + 5, y + 5], fill=colour, outline=SURFACE)
            fig.legend(top, [("Silero, mean of the window", SERIES[1]), ("Silero, highest", "#f5c0a6"),
                             ("reading: owner / same", SERIES[2]), ("reading: somebody else", SERIES[1]),
                             ("reading: unclear", OTHER)])
    return fig.save(out_path)


# ----------------------------------------------------------------------------------------------------------------------
# figure 6: inliers and keypoints against distance
# ----------------------------------------------------------------------------------------------------------------------

def distance_figure(data, out_path, t0):
    checks = parse_checks(data["monitor"])
    if not checks:
        return None
    fig = Figure("How far away an object can still be found", subtitle_for(data, t0), t0, t0 + 1,
                 note="Every point is one ORB run on one pink area. The long side of that area in the camera frame stands "
                      "for the distance: small = far away. The line is the median per 50 px.")
    label = data.get("label") or "this run"
    fig.y = distance_panels(fig.g, fig.L, fig.y + 20, fig.W - fig.L - fig.R, 300,
                            [(label, checks, SERIES[0])], (fig.f_head, fig.f, fig.fs), fig.W - fig.R + 30)
    return fig.save(out_path)


# ----------------------------------------------------------------------------------------------------------------------

def subtitle_for(data, t0):
    started = datetime.fromtimestamp(t0).strftime("%Y-%m-%d %H:%M:%S")
    text = f"Recorded {started}, {data['seconds']} s, pid {data['pid']}"
    if data.get("label"):
        text += f", {data['label']}"
    if data.get("navigation"):
        text += "  ·  background: light blue = driving, light orange = avoiding an obstacle"
    return text


NOTE = """# Performance run {label}

Recorded {started} over {seconds} s with `performance/record.py` while RoboGuard was running on the robot
({serial}, pid {pid}).

## What is measured

| Source | What comes from it |
|---|---|
| `/proc/<pid>/task/*/stat` | CPU time of every RoboGuard thread, once per second, plus the core it last ran on |
| `/proc/[0-9]*/stat` | CPU time of every process on the robot (chassis, audio, camera, RobotOS vision, …) |
| `/proc/stat` | busy share of each of the eight cores; 0–3 are the small cores, 4–7 the big ones |
| Logcat `CalendarMonitor` | per 2 s: camera fps, passes/s, step times, detected, pink regions, rejects, **frame → decision delay**; per ORB run: frame size, scale, keypoints, good matches, inliers; **seen / gone** per reference image; every announcement |
| Logcat `OwnerVoice` / `PairwiseVoice` / `SpeakerChange` | per 2 s: share of the window that counted as speech, pieces embedded, **time of one embedding**, time of the voice-activity gate; per piece: the similarity reading; when the robot's own voice muted the microphone |
| Logcat `ConversationMonitor` | when a prompt was shown, when the silence reset fired |
| Logcat `RoboGuardNav` | drives (start, arrived, failed, stopped) and obstacle avoidance, drawn as the background shading |

Nothing about a person is recorded: no audio, no image, no transcript, no embedding — only counts, times and the
similarity numbers the detector itself logs.

## The graphics

{figures}

## Settings of this run

{settings}
"""


def render_all(data, out_dir):
    """Draws every figure of one run into out_dir and writes note.md next to them. Returns the file names."""
    threads = data["threads"]
    t0 = (threads[0]["t"] - 1) if threads else data["start"]
    windows = [w for w in parse_monitor(data["monitor"]) if w["t"] >= t0]
    t_end = max([s["t"] for s in threads + data["processes"]] + [w["t"] for w in windows] + [t0 + 1])

    made = []
    overview = os.path.join(out_dir, "01_overview.png")
    plot.render(data, overview)
    made.append(("01_overview.png", "CPU per thread group and per process, the detection steps and the rates — the "
                                    "graphic that already existed, unchanged."))
    for name, description, fn in [
        ("02_activity.png", "One row per activity over the whole recording: driving, obstacle avoidance, which object "
                            "was in view, when the microphone fed the network, when a piece was embedded, when the "
                            "robot spoke, and every announcement and prompt. Underneath, the CPU of RoboGuard and of "
                            "the whole robot on the same time axis.",
         lambda p: activity_figure(data, p, t0, t_end, windows)),
        ("03_cores.png", "Every CPU core on its own panel, the average load per core, and a table of RoboGuard's "
                         "threads with how much CPU each used and how often it ran on a big core.",
         lambda p: cores_figure(data, p, t0, t_end)),
        ("04_object_delay.png", "How long an object needs from the camera frame to the decision: over time (average and "
                                "worst per window, with the announcing passes marked), as a distribution, plus the pass "
                                "rate, the time of one pass and how long each object stayed in view.",
         lambda p: delay_figure(data, p, t0, t_end, windows)),
        ("05_voice.png", "The voice path: how much of the time counted as speech, how long one embedding and one gate "
                         "chunk took, how many pieces went into the network, and the similarity of every piece.",
         lambda p: voice_figure(data, p, t0, t_end)),
        ("06_distance.png", "Inliers and keypoints against the size of the pink frame in the picture, i.e. against the "
                            "distance to the object.",
         lambda p: distance_figure(data, p, t0)),
    ]:
        path = fn(os.path.join(out_dir, name))
        if path:
            made.append((name, description))

    # A second copy of the voice figure where the owner collects them: performance/voice_fingerprinting/,
    # named <day-month-year>_<time>_<method>.png, with the settings printed at the top of the picture itself.
    voice_png = os.path.join(out_dir, "05_voice.png")
    if os.path.exists(voice_png):
        collected = os.path.join(HERE, "voice_fingerprinting")
        os.makedirs(collected, exist_ok=True)
        mode = voice_mode(data.get("voice", []), parse_audio(data.get("voice", [])))
        stamp = datetime.fromtimestamp(t0).strftime("%d-%m-%Y_%H%M")
        target = os.path.join(collected, f"{stamp}_{mode}.png")
        shutil.copyfile(voice_png, target)
        made.append((os.path.join("..", "voice_fingerprinting", os.path.basename(target)),
                     "The same voice figure, collected with the others under performance/voice_fingerprinting/."))

    with open(os.path.join(out_dir, "note.md"), "w") as f:
        f.write(NOTE.format(
            label=data.get("label") or "",
            started=datetime.fromtimestamp(t0).strftime("%Y-%m-%d %H:%M:%S"),
            seconds=data["seconds"], serial=data["serial"], pid=data["pid"],
            figures="\n".join(f"**{n}** — {d}\n" for n, d in made),
            settings=settings_line(data["monitor"]) or "(the detection settings line was not in the log window)"))
    return [n for n, _ in made] + ["note.md"]


if __name__ == "__main__":
    import json
    import sys
    run = sys.argv[1]
    with open(os.path.join(run, "data.json")) as f:
        print("\n".join(render_all(json.load(f), run)))
