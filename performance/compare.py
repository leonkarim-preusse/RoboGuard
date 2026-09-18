#!/usr/bin/env python3
"""
Compares the calendar detection of several recorded runs by distance: inliers and keypoints found per check against the pink
frame size (small = far away), one colour per run, with median lines.

Usage: python3 performance/compare.py <run dir or data.json> <run dir or data.json> [...] [--name comparison-name]
Output: performance/comparisons/<name>.png
"""
import json
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import plot  # noqa: E402


def main():
    args = sys.argv[1:]
    name = None
    if "--name" in args:
        i = args.index("--name")
        name = args[i + 1]
        del args[i:i + 2]
    if len(args) < 2:
        sys.exit(__doc__)
    runs = []
    for k, a in enumerate(args):
        path = a if a.endswith(".json") else os.path.join(a, "data.json")
        with open(path) as fh:
            d = json.load(fh)
        checks = plot.parse_checks(d["monitor"])
        features = "/".join(str(x) for x in sorted({c["features"] for c in checks})) or "?"
        run = os.path.basename(os.path.dirname(os.path.abspath(path)))
        short = run.split("_", 2)[2] if run.count("_") >= 2 else run  # drop the date/time prefix to keep the legend short
        label = f"{short} · {features} features"
        runs.append((label, checks, plot.SERIES[k % 3]))  # at most 3 runs keep all colour pairs distinguishable
    if len(runs) > 3:
        sys.exit("compare at most 3 runs at once (colour limit)")
    W, H = 1800, 980
    L, R = 110, 520
    img = Image.new("RGB", (W, H), plot.SURFACE)
    g = ImageDraw.Draw(img)
    f_title, f_head, f, fs = plot.font(28, True), plot.font(19, True), plot.font(16), plot.font(14)
    g.text((L, 24), "Calendar detection by distance", fill=plot.TEXT, font=f_title)
    g.text((L, 62), "Pink frame size = long side of the pink line in the camera image (1280×720); smaller = further away",
           fill=plot.TEXT2, font=f)
    plot.distance_panels(g, L, 150, W - L - R, 300, runs, (f_head, f, fs), W - R + 30)
    out_dir = os.path.join(HERE, "comparisons")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, (name or "_vs_".join(os.path.basename(os.path.dirname(os.path.abspath(a if a.endswith('.json') else os.path.join(a, 'x')))) for a in args)) + ".png")
    img.save(out)
    print(out)


if __name__ == "__main__":
    main()
