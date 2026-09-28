# Performance run range-1000kp

Recorded 2026-09-18 11:28:46 over 90 s with `performance/record.py` while RoboGuard was running on the robot
(10.131.33.35:5555, pid 27881).

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

**01_overview.png** — CPU per thread group and per process, the detection steps and the rates — the graphic that already existed, unchanged.

**02_activity.png** — One row per activity over the whole recording: driving, obstacle avoidance, which object was in view, when the microphone fed the network, when a piece was embedded, when the robot spoke, and every announcement and prompt. Underneath, the CPU of RoboGuard and of the whole robot on the same time axis.

**06_distance.png** — Inliers and keypoints against the size of the pink frame in the picture, i.e. against the distance to the object.


## Settings of this run

(the detection settings line was not in the log window)
