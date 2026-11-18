#!/usr/bin/env python3
"""
Draws the measurement figures of the thesis chapters as vector graphics (SVG and PDF) from the recorded runs.

Usage: python3 performance/thesis_plots.py <output folder>
Needs matplotlib. Fonts: TeX Gyre Pagella, the Palatino clone the thesis is set in.
"""

import json
import os
import statistics
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import graphs  # noqa: E402
import plot  # noqa: E402

DRIVE_RUN = "20261019_151104_drive"
NOISE_RUN = "20261019_164351_excavator"
REQUIRED_INLIERS = 20
CONFIRMING_INLIERS = 12

# Fixed categorical order of the reference palette (same order as plot.py).
BLUE, ORANGE, GREEN, YELLOW, PINK, DARK_GREEN, PURPLE = plot.SERIES
GRAY, LIGHT_GRAY = "#9d9c96", "#d6d4ce"
TEXT, TEXT2, GRID = "#0b0b0b", "#52514e", "#e6e5e1"
DRIVE_SHADE = "#dcebfa"

WIDTH = 5.9  # inches, the text width of the thesis (15 cm)

FONT_DIR = "/usr/share/texmf/fonts/opentype/public/tex-gyre"
for name in ("texgyrepagella-regular.otf", "texgyrepagella-bold.otf", "texgyrepagella-italic.otf"):
    path = os.path.join(FONT_DIR, name)
    if os.path.exists(path):
        font_manager.fontManager.addfont(path)
plt.rcParams.update({
    "font.family": "TeX Gyre Pagella",
    "font.size": 9,
    "axes.titlesize": 9.5,
    "axes.titleweight": "bold",
    "axes.titlelocation": "left",
    "axes.labelsize": 9,
    "axes.labelcolor": TEXT,
    "axes.edgecolor": "#b5b4ad",
    "axes.linewidth": 0.6,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "grid.color": GRID,
    "grid.linewidth": 0.6,
    "xtick.color": TEXT2,
    "ytick.color": TEXT2,
    "xtick.labelsize": 8,
    "ytick.labelsize": 8,
    "legend.fontsize": 8,
    "legend.frameon": False,
    "svg.fonttype": "none",  # text stays text in the SVG
    "pdf.fonttype": 42,
    "figure.dpi": 150,
})


def load(run):
    """A run's data without the log lines from before its first CPU sample, plus t0 and the end of its time axis."""
    with open(os.path.join(HERE, "runs", run, "data.json")) as f:
        data = json.load(f)
    t0 = data["threads"][0]["t"] - 1
    for key in ("monitor", "navigation", "voice"):
        data[key] = [entry for entry in data[key] if entry["t"] >= t0]
    t_end = max([s["t"] for s in data["threads"] + data["processes"]] + [w["t"] for w in plot.parse_monitor(data["monitor"])])
    return data, t0, t_end


def save(fig, out_dir, name):
    for ext in ("svg", "pdf"):
        fig.savefig(os.path.join(out_dir, f"{name}.{ext}"), bbox_inches="tight", pad_inches=0.02)
    plt.close(fig)


def shade_drives(ax, drives, t0, label=False):
    """Light blue background while the robot drove."""
    first = True
    for a, b, avoiding in drives:
        if avoiding:
            continue
        ax.axvspan(a - t0, b - t0, color=DRIVE_SHADE, lw=0, zorder=0,
                   label="robot driving" if (label and first) else None)
        first = False


# ----------------------------------------------------------------------------------------------------------------------

def distance_figure(out_dir):
    """Inliers of every ORB check against the long side of the pink frame (small = far away)."""
    with open(os.path.join(HERE, "runs", DRIVE_RUN, "data.json")) as f:
        checks = plot.parse_checks(json.load(f)["monitor"])
    fig, ax = plt.subplots(figsize=(WIDTH, 2.6))
    ax.scatter([c["side"] for c in checks], [c["inliers"] for c in checks], s=14, facecolors="none",
               edgecolors=BLUE, linewidths=0.9, label=f"single check ({len(checks)} in total)", zorder=3)
    median = plot.binned_median([(c["side"], c["inliers"]) for c in checks])
    ax.plot([x for x, _ in median], [v for _, v in median], color=BLUE, lw=2, zorder=4,
            label="median per 50 px", path_effects=[])
    ax.axhline(REQUIRED_INLIERS, color=ORANGE, lw=1.2, ls=(0, (5, 3)), zorder=2,
               label=f"required for a detection ({REQUIRED_INLIERS})")
    ax.axhline(CONFIRMING_INLIERS, color=GRAY, lw=1.0, ls=(0, (2, 3)), zorder=2,
               label=f"required in the confirming check ({CONFIRMING_INLIERS})")
    ax.set_xlim(0, 450)
    ax.set_ylim(0, max(c["inliers"] for c in checks) + 5)
    ax.set_xlabel("Long side of the pink frame (px); smaller = farther away")
    ax.set_ylabel("Inliers per check")
    # Outside the plot, so it does not cover the points and grid.
    ax.legend(loc="upper left", bbox_to_anchor=(1.02, 1.0))
    fig.subplots_adjust(right=0.66)
    save(fig, out_dir, "orb_inliers_distance")


def noise_figure(out_dir, run=NOISE_RUN, name="silero_construction_noise"):
    """Share of loud frames, Silero probability and share of speech while construction site noise was played."""
    data, t0, t_end = load(run)
    audio = graphs.parse_audio(data["voice"])
    gate = graphs.parse_gate(data["voice"])
    floor = gate.get("floor", -65.0)
    threshold = gate.get("silero", 0.65)
    release = threshold - gate.get("hysteresis", 0.15)
    t = [a["t"] - t0 for a in audio]

    fig, axes = plt.subplots(3, 1, figsize=(WIDTH, 5.4), sharex=True, gridspec_kw={"height_ratios": [1.0, 1.2, 1.0]})
    ax = axes[0]
    # The share of frames above the gate, not the mean level: a mean in dB is pulled far down by a few quiet frames.
    ax.fill_between(t, [a["aboveFloor"] for a in audio], color="#dcebfa")
    ax.plot(t, [a["aboveFloor"] for a in audio], color=BLUE, lw=1.6, marker="o", ms=2.5)
    ax.set_title(f"Share of frames louder than the noise gate ({floor:.0f} dBFS)")
    ax.set_ylabel("Share (%)")
    ax.set_ylim(0, 100)

    ax = axes[1]
    ax.plot(t, [a["sileroMax"] for a in audio], color="#f5c0a6", lw=1.2, label="highest per 2 s window")
    ax.plot(t, [a["sileroMean"] for a in audio], color=ORANGE, lw=1.6, marker="o", ms=2.5, label="mean per 2 s window")
    ax.axhline(threshold, color=PURPLE, lw=1.2, ls=(0, (5, 3)), label=f"speech starts ({threshold:.2f})")
    ax.axhline(release, color=GRAY, lw=1.0, ls=(0, (2, 3)), label=f"speech ends ({release:.2f})")
    ax.set_title("Silero speech probability")
    ax.set_ylabel("Probability")
    ax.set_ylim(0, 1)
    ax.legend(loc="upper left", bbox_to_anchor=(1.02, 1.0))

    ax = axes[2]
    ax.fill_between(t, [a["speech"] for a in audio], color="#e4f0e9", step=None)
    ax.plot(t, [a["speech"] for a in audio], color=DARK_GREEN, lw=1.6)
    ax.axhline(50, color=ORANGE, lw=1.2, ls=(0, (5, 3)), label="share needed over 8 s (50 %)")
    ax.set_title("Share of the time counted as speech")
    ax.set_ylabel("Share (%)")
    ax.set_ylim(0, 100)
    ax.set_xlabel("Time since the start of the recording (s)")
    ax.set_xlim(0, t_end - t0)
    ax.legend(loc="upper left", bbox_to_anchor=(1.02, 1.0))
    fig.align_ylabels(axes)
    fig.subplots_adjust(right=0.68)
    save(fig, out_dir, name)


def thread_and_process_series(data):
    """CPU per RoboGuard thread group and per robot process group (plus kernel time) for every sample."""
    pid = data["pid"]
    threads = []
    for s in data["threads"]:
        sums = [0.0] * len(plot.THREAD_GROUPS)
        for tid, cpu, ni, pcpu, name in s["rows"]:
            name = name.strip()
            g = plot.thread_group(pid, tid, name)
            if g == 3 and name == "ample.roboguard" and tid != pid:
                g = 1
            sums[g] += plot.fnum(pcpu)
        threads.append((s["t"], sums))
    cores = {round(c["t"], 1): sum(v for v in c["cores"].values() if v >= 0) for c in data["cores"]}
    processes = []
    for s in data["processes"]:
        sums = [0.0] * len(plot.PROCESS_GROUPS)
        for p, cpu, ni, pcpu, name in s["rows"]:
            sums[plot.process_group(pid, p, name.strip())] += plot.fnum(pcpu)
        total = cores.get(round(s["t"], 1))
        sums.append(max(0.0, total - sum(sums)) if total is not None else 0.0)
        processes.append((s["t"], sums))
    return threads, processes


def activity_figure(out_dir):
    """What the robot did over the recording, with the CPU load of RoboGuard and of the whole robot underneath."""
    data, t0, t_end = load(DRIVE_RUN)
    drives = plot.drive_intervals(data.get("navigation", []), t_end)
    windows = [w for w in plot.parse_monitor(data["monitor"])]
    audio = graphs.parse_audio(data["voice"])
    seen = graphs.parse_seen(data["monitor"], t_end)
    speaking = graphs.parse_speaking(data["voice"], t_end)
    prompts = graphs.parse_events(data["voice"], "showing prompt")
    resets = graphs.parse_events(data["voice"], "reset after")
    threads, _ = thread_and_process_series(data)

    rows = [("Robot driving", BLUE, "span", [(a, b) for a, b, avoiding in drives if not avoiding])]
    for name in sorted(seen):
        rows.append((f"{name} in view", GREEN, "span", seen[name]))
    rows.append(("Pink frame found", PINK, "level", [(w["t"], 1.0 if w.get("regions", 0) > 0 else 0.0) for w in windows]))
    rows.append(("Speech heard", DARK_GREEN, "level", [(a["t"], 1.0 if a["speech"] > 0 else 0.0) for a in audio]))
    rows.append(("Robot speaking", GRAY, "span", speaking))
    rows.append(("Conversation prompt", ORANGE, "event", prompts))
    rows = [r for r in rows if r[3]]

    fig, axes = plt.subplots(3, 1, figsize=(WIDTH, 6.2), sharex=True,
                             gridspec_kw={"height_ratios": [len(rows) * 0.32, 1.1, 1.1]})
    ax = axes[0]
    shade_drives(ax, drives, t0)
    for i, (label, colour, kind, items) in enumerate(rows):
        y = len(rows) - 1 - i
        if kind == "span":
            for a, b in items:
                ax.broken_barh([(max(a, t0) - t0, min(b, t_end) - max(a, t0))], (y + 0.15, 0.7), color=colour, lw=0)
        elif kind == "event":
            for e in items:
                ax.plot([e - t0, e - t0], [y + 0.1, y + 0.9], color=colour, lw=2.2, solid_capstyle="butt")
        else:
            for tw, v in items:
                if v > 0:
                    ax.broken_barh([(tw - t0 - 2.0, 2.0)], (y + 0.15, 0.7), color=colour, lw=0)
    ax.set_yticks([len(rows) - 1 - i + 0.5 for i in range(len(rows))])
    ax.set_yticklabels([r[0] for r in rows])
    ax.set_ylim(0, len(rows))
    ax.grid(axis="y", visible=False)
    ax.tick_params(axis="y", length=0)
    ax.set_title("Activity timeline")

    ax = axes[1]
    shade_drives(ax, drives, t0, label=True)
    xs, ys = [t - t0 for t, _ in threads], [sum(s) for _, s in threads]
    ax.fill_between(xs, ys, color=BLUE, alpha=0.18, lw=0)
    ax.plot(xs, ys, color=BLUE, lw=1.4, label="RoboGuard")
    ax.set_title("CPU load of RoboGuard")
    ax.set_ylabel("CPU load (%)\n100 % = one core")
    ax.set_ylim(0, 200)
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0))

    ax = axes[2]
    shade_drives(ax, drives, t0)
    busy = [(c["t"] - t0, sum(v for v in c["cores"].values() if v >= 0)) for c in data["cores"]]
    ax.fill_between([t for t, _ in busy], [v for _, v in busy], color=ORANGE, alpha=0.18, lw=0)
    ax.plot([t for t, _ in busy], [v for _, v in busy], color=ORANGE, lw=1.4)
    ax.axhline(800, color=GRAY, lw=1.0, ls=(0, (2, 3)))
    ax.text(1, 815, "all 8 cores fully busy (800 %)", color=TEXT2, fontsize=8, va="bottom")
    ax.set_title("CPU load of the whole robot")
    ax.set_ylabel("CPU load (%)\n100 % = one core")
    ax.set_ylim(0, 900)
    ax.set_yticks(range(0, 801, 200))
    ax.set_xlabel("Time since the start of the recording (s)")
    ax.set_xlim(0, t_end - t0)
    fig.align_ylabels(axes[1:])
    fig.subplots_adjust(right=0.8)
    save(fig, out_dir, "activity_cpu")


def overview_figure(out_dir):
    """CPU per thread group and per process group, the time of one detection check by step, and the check rate."""
    data, t0, t_end = load(DRIVE_RUN)
    drives = plot.drive_intervals(data.get("navigation", []), t_end)
    windows = plot.parse_monitor(data["monitor"])
    threads, processes = thread_and_process_series(data)

    fig, axes = plt.subplots(4, 1, figsize=(WIDTH, 8.4), sharex=True, gridspec_kw={"height_ratios": [1.2, 1.2, 1.1, 0.8]})
    thread_names = ["Object detection (2 workers)", "OpenCV helper threads", "Camera frame copy", "Screen (UI)",
                    "Garbage collection", "Voice detection", "Other threads"]
    thread_colours = [BLUE, ORANGE, GREEN, YELLOW, PINK, DARK_GREEN, GRAY]
    ax = axes[0]
    shade_drives(ax, drives, t0)
    ts = [t - t0 for t, _ in threads]
    stacks = list(zip(*[s for _, s in threads]))
    averages = [statistics.mean(v) for v in stacks]
    ax.stackplot(ts, stacks, colors=thread_colours, edgecolor="white", linewidth=0.4,
                 labels=[f"{n} ({a:.0f} %)" for n, a in zip(thread_names, averages)])
    ax.set_title("CPU load of RoboGuard per thread group")
    ax.set_ylabel("CPU load (%)\n100 % = one core")
    ax.set_ylim(0, 175)
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0), title="Average", title_fontsize=8, alignment="left")

    process_names = ["RoboGuard", "Chassis and motors", "Audio service", "Camera services", "RobotOS vision",
                     "Other processes", "Kernel and interrupts"]
    process_colours = [BLUE, ORANGE, GREEN, YELLOW, PINK, GRAY, LIGHT_GRAY]
    ax = axes[1]
    shade_drives(ax, drives, t0)
    ts = [t - t0 for t, _ in processes]
    stacks = list(zip(*[s for _, s in processes]))
    averages = [statistics.mean(v) for v in stacks]
    ax.stackplot(ts, stacks, colors=process_colours, edgecolor="white", linewidth=0.4,
                 labels=[f"{n} ({a:.0f} %)" for n, a in zip(process_names, averages)])
    ax.set_title("CPU load of the whole robot per process group")
    ax.set_ylabel("CPU load (%)\n800 % = all 8 cores")
    ax.set_ylim(0, 800)
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0), title="Average", title_fontsize=8, alignment="left")

    step_names = ["Pink frame search", "Other work", "Resize", "Keypoints",
                  "Matching", "Homography"]
    step_colours = [PINK, GRAY, YELLOW, BLUE, ORANGE, GREEN]
    ax = axes[2]
    shade_drives(ax, drives, t0)
    for w in windows:
        p = w.get("pink")
        colour = w["colour"]
        if p:
            rest = max(0, p["avg"] - colour - p["resize"] - p["keypoints"] - p["match"] - p["homography"])
            parts = [colour, rest, p["resize"], p["keypoints"], p["match"], p["homography"]]
        else:
            parts = [colour, max(0, w["avg"] - colour), 0, 0, 0, 0]
        base = 0
        for value, c in zip(parts, step_colours):
            if value > 0:
                ax.bar(w["t"] - t0 - 1.0, value, width=1.7, bottom=base, color=c, edgecolor="white", linewidth=0.3)
                base += value
    for name, c in zip(step_names, step_colours):
        ax.bar([0], [0], color=c, label=name)
    ax.set_title("Time of one detection check, by step (average per 2 s window)")
    ax.set_ylabel("Time (ms)")
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0), reverse=True)

    ax = axes[3]
    shade_drives(ax, drives, t0, label=True)
    t = [w["t"] - t0 for w in windows]
    ax.plot(t, [w["fps"] for w in windows], color=ORANGE, lw=1.4, marker="o", ms=2, label="camera frames per second")
    ax.plot(t, [w["pps"] for w in windows], color=BLUE, lw=1.4, marker="o", ms=2, label="detection checks per second")
    ax.set_title("Camera frames and detection checks")
    ax.set_ylabel("Rate (1/s)")
    ax.set_ylim(0, 25)
    ax.set_xlabel("Time since the start of the recording (s)")
    ax.set_xlim(0, t_end - t0)
    ax.legend(loc="upper left", bbox_to_anchor=(1.01, 1.0))
    fig.align_ylabels(axes)
    # The legends sit to the right inside the figure, so the whole figure keeps the text width.
    fig.subplots_adjust(right=0.66)
    save(fig, out_dir, "cpu_overview")


def mel_figure(out_dir):
    """The mel filter bank of Mfcc.kt: 26 triangular filters from 100 to 7600 Hz over the 257 bins of a 512-point FFT."""
    sample_rate, fft_size, bands, low_hz, high_hz = 16_000, 512, 26, 100.0, 7_600.0

    def to_mel(hz):
        return 2595.0 * __import__("math").log10(1 + hz / 700.0)

    def to_hz(mel):
        return 700.0 * (10 ** (mel / 2595.0) - 1)

    low, high = to_mel(low_hz), to_mel(high_hz)
    centres = [int(to_hz(low + (high - low) * i / (bands + 1)) * fft_size / sample_rate) for i in range(bands + 2)]
    bin_hz = sample_rate / fft_size
    fig, ax = plt.subplots(figsize=(WIDTH, 2.4))
    for b in range(bands):
        left, centre, right = centres[b], centres[b + 1], centres[b + 2]
        colour = BLUE if b % 2 == 0 else ORANGE
        ax.plot([left * bin_hz, centre * bin_hz, right * bin_hz], [0, 1, 0], color=colour, lw=1.2)
    ax.set_xlim(0, sample_rate / 2)
    ax.set_ylim(0, 1.05)
    ax.set_xlabel("Frequency (Hz)")
    ax.set_ylabel("Filter weight")
    ax.set_title(f"{bands} mel filters between {low_hz:.0f} and {high_hz:.0f} Hz (FFT of {fft_size} samples at 16 kHz)")
    save(fig, out_dir, "mel_filter_bank")


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    distance_figure(out)
    noise_figure(out)
    noise_figure(out, "20261019_164123_excavator", "silero_construction_noise_run1")
    activity_figure(out)
    overview_figure(out)
    mel_figure(out)
    print("\n".join(sorted(os.listdir(out))))
