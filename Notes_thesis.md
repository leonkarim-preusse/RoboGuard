# Thesis notes: experiments, issues, solutions

Robot: OrionStar GreetBot Mini (ZTT18P1000A0), Android 9, RobotOS SDK `robotservice_12.3.jar`. Dates: 2026-09-14 to 2026-09-17.
Short and factual; details and raw numbers are in `CLAUDE.md` and the probe logs (`files/voiceprobe/` on the robot).

## 1. RobotOS SDK control

- **Issue:** SDK commands (speech, driving, sensors) were silently ignored.
  - Cause: RobotOS only gives control to an app **launched from the home launcher**; adb starts, popups or being on screen are not enough.
  - Attempt: launcher icons for every test screen → works.
- **Issue:** after pressing Home, RoboGuard lost control and the robot stopped talking.
  - Analysis: CoreService `PermissionManager` (decompiled) activates white-listed apps and the configured **default/boot app** whenever they come to the front.
  - Solution: RoboGuard sets itself as default app (`boot_app_package_name`), takes effect after a reboot; control returns via "Recovery pre app".
- **Issue:** speech from the server right after a popup was refused ("skillType is SUSPEND").
  - Cause: `startActivity` is asynchronous; control returns ~0.5 s later, then RobotOS stops TTS once.
  - Solution: wait for `RobotApi.isActive()` + ~1 s before speaking and switching sensors.
- **Issue:** robot drove through private areas after a reboot (no position available).
  - Cause (inferred): two `connectServer` calls in one process broke pose/`isActive` calls; navigation still worked.
  - Solution: one shared RobotApi connection for the whole app + **fail closed** (stop / refuse driving without a fresh position).

## 2. Text-to-speech

- English and German work via `SkillApi.playText` with per-sentence language (`de_DE`); installed-voice query returns null (useless).

## 3. Sensor switching (privacy settings from the phone app)

- Camera off: `stopVision` + Android device policy `setCameraDisabled` → camera stream blocked (SurfaceShare error −15). Works.
- Microphone off: SDK speech recognition off + Android `setMicrophoneMute` → recordings silent, speech service deaf. Works.
- **Issue:** speech recognition cannot be switched back on via the SDK (stays off until reboot) → treat "mic off" as quasi one-way.
- LIDAR on/off via `updateRadarStatus` answered "succeed"; physical effect not tested.

## 4. Navigation and private areas

- Driving by name/coordinates works; speed presets verified in m/s via the `navi_speed` status (Slow ≈ 0.25 m/s).
- **No-go lines written by the app** (map image + SDK flags) were ignored by navigation; only map-tool lines work → privacy areas enforced in the app instead.
- Enforcement: target check before driving + position check every 150 ms while driving (0.5 m margin).
- **Issue:** robot stopped inside the margin and could not leave. Solution: a drive that starts inside may continue if it does not move deeper.
- **Issue:** backing up has no obstacle avoidance. Solution: turn on the spot to face away from the area instead.
- **Issue:** RobotOS's own "stopped" result arrived before the privacy stop → no announcement. Solution: ignore callbacks of an aborted drive.
- Named, encrypted, persistent private areas and locations (AES-GCM, Android Keystore); popup to temporarily allow crossing (5 min default, custom time).

## 5. Conversation detection (one vs. more speakers)

- **Design decision:** speaker **change detection** (MFCC → Gaussian windows → KL2 + ΔBIC, DISTBIC), no voice embeddings/fingerprints (privacy: nothing identifying is kept).
- Audio: Android `AudioRecord` with source **CAMCORDER** (only source with a clear signal); multi-channel useless (no raw mic array).
- **Issue:** a single speaker (video) was detected as two. Settings tried: λ 1.0 → 2.0; window 1.0 s vs 1.5 s; per-candidate log of "λ needed".
- **Issue:** silence and loud noise counted as speech (loudness gate). Attempts: percentile noise floor, 15 dB margin, run filter → still noise-sensitive.
  - Solution: **Silero VAD** (small neural network, on-device via ONNX Runtime). Silence/noise/clicks → p ≈ 0.02–0.06.
  - Privacy comparison discussed: self-built voicing check (explainable, but pitch is a voice trait), WebRTC-VAD, Silero (best, least explainable).
- **Issue:** slow detection with overlapping speech (speaker 1 → both → speaker 2).
  - Attempt: **evidence accumulation** (CUSUM-style leaky integrator over change candidates) instead of counting 2 changes in 20 s.
  - Issue: repeated evaluations of one boundary were counted several times → group candidates within 0.5 s of speech.
  - Issue: total latency ~9–10 s → group span 1.5 s → 0.5 s and hold 2 s → 1 s.
- Working settings (owner test): window 1.5 s, λ 2.0, Silero threshold 0.5, evidence r₀ 1.8, threshold 0.8, half-life 5 s.
- Known limits: one voice reaches r ≈ 1.7–1.98, real changes often only 2.0–2.3 (small margin); the robot's own voice may count as a second speaker (hypothesis).
- Integration: background monitor with RoboGuard; after > 1 s of "more than one speaker" a popup (Leave room / Mute mic / Don't ask again for N min) + spoken apology.

## 6. Object detection with ORB (calendar, for later pixelation)

- Pipeline: ORB keypoints → brute-force Hamming kNN + ratio test (0.75) → RANSAC homography → detected if ≥ 15 good matches and ≥ 12 inliers.
- Camera: SurfaceShare stream (RobotOS keeps the camera). Frames only in memory.
- **Issue:** low preview FPS. Causes: preview tied to detection loop, per-pixel YUV conversion. Solution: separate loops, bulk conversion → ~26 fps preview.
- **Issue:** image looked very bright.
  - Findings: auto-exposure normal (ISO 100, ~6 ms, 2–3 % overexposed); preview used TV-range colour maths on full-range (JFIF) data → fixed.
  - Contrast boost (CLAHE) available as a switch; did not change the matching problem.
- **Issue:** calendar not detected (good matches 1–11, inliers 0).
  - Causes: repetitive structure (spiral binding, identical grid cells) fails the ratio test; background corners use the keypoint budget; plain white page.
  - Explained: ORB already filters like SIFT (FAST threshold, Harris ranking) – filters rank strength, not relevance.
  - Attempts: more features (1000/3000/5000), lower FAST threshold (20/10/5), keypoints spread over an 8×6 grid.
- **Issue:** calendar had to be very close.
  - Cause: 640×480 frames (also squeezed from 16:9); references 640 px; ORB pyramid reaches only ~3.6× scale.
  - Solution: camera at its native **1280×720** (maximum without taking the camera from RobotOS).
- **Issue (user study: calendar on a wall, at a distance):** calendar only ~310×170 px of the frame; busy whiteboard; strong **wide-angle/fisheye distortion** breaks the planar homography.
  - References tried: phone photo (with background), cropped phone photos, screenshot crop of the robot view (only 69 keypoints → useless), region captured from the live frame (saved on the robot).
  - **Enlarged tiles** (2×2 overlapping, 2× zoom, every 1 s): first detections at a distance (good ~90–130, inliers 13–24 vs full frame ~35–60 / 0–14); cost ~1.4–1.8 s per tile pass.
  - Relaxed thresholds (10/8, 8/6) + **2-of-3 consistency** against single-frame false hits.
  - Lens calibration/undistortion considered but **not implemented** (owner decision).
- **Calendar modified by adding more visual features** (coloured markings, then outlines): reference keypoints rose from ~850–1500 to ~2100–2300 (3000-feature setting); good matches clearly higher (up to ~70 full frame, ~130 with tiles).
- **Open issue:** detections with enough inliers are sometimes still rejected (e.g. inliers 11–44 → not detected) → likely the outline plausibility check (convexity/area) under lens distortion.
- Settings used in the last runs: 1280×720, features 3000–5000, FAST 10, grid on, thresholds 10/8 or 8/6, tiles on, contrast boost off. Detection 300–600 ms per full-frame pass.
- **Attempt:** accept a match with enough inliers but a folded outline and draw the inliers' bounding box instead. **Result:** many false detections on the whiteboard without the calendar present → reverted.
- **Second identifier: pink marker line** drawn around the calendar (colour instead of more texture).
  - Pink measured in the robot view at wall distance: hue ~300–320°, low saturation (~60–105/255, thin line mixes with white); the red frame on the whiteboard is 355–20° → separable by hue.
  - Pipeline: HSV colour range → drop specks → grow by a margin (e.g. 40 px) → pink areas → ORB **only inside those areas** (enlarged up to 3×), no full-frame or tile pass.
  - Accept: plausible outline inside the pink area, or enough good matches + inliers inside it (box = pink line bounds, distortion-tolerant).
  - Privacy: colour thresholds only, nothing about people.
  - **Issue:** pink detection weak. Causes (from a robot screenshot): camera renders white bluish → pale pink shifts to purple (hue ~280°); line only ~2 px wide, breaks into pieces; red frame edge and skin edges also pass.
  - Attempt: white balance on bright pixels, wider hue range, closing gaps along the line, and **shape check: pink must run along ≥ 3 sides of its bounds (a frame)**. Rejected pink shown in yellow.
  - Result on the robot: pending.
- Next ideas (not done): alternative algorithms (e.g. SIFT/AKAZE) to be discussed later.

## Open issues / to adjust later

- **Driving stops when the Navigation screen is left** (`MapNavigationActivity.onStop` → stop, on purpose: "a robot must never keep driving while nobody sees the screen that controls it"). Owner (2026-09-17): must be adjusted eventually, e.g. for popups, other RoboGuard screens or background tasks during a drive. Debug camera view was therefore built inside the navigation screen.

