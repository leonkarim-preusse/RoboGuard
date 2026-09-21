# RoboGuard — project context

Bachelor thesis project. Privacy-aware control for an **OrionStar GreetingBot Mini**.
The thesis argument: privacy is continuous and situational, opt-out-by-default, and
the robot must make its restrictions visible rather than enforcing them silently.

Thesis material (not in this repo): `~/Documents/bachelor Arbeit/` —
`Scenarios and implementations initial outline.pdf`, `Outline Scenarios + notes.odt`.

## Repo layout

```
app/src/main/java/com/example/
    roboguard/      existing app — DO NOT change without being asked
    testing/        the on-robot test screens (moved out of robocontrol 2026-09-21 at the owner's
                    request): voiceprobe/, sensorprobe/, movementprobe/, objectprobe/,
                    conversationprobe/. Package com.example.testing.<probe>; nothing in roboguard or
                    robocontrol imports it, the dependency only runs the other way.
    robocontrol/    added later, standalone
        movement/     robot movement + privacy zones (package com.example.robocontrol.movement)
        voiceprobe/   hardware probes for multi-speaker detection (added 2026-09-15)
        audio/        OrionStarTts: English/German TTS via SkillApi (added 2026-09-15)
        vision/       created by the owner; Blurring.kt is empty (2026-09-15). No vision
                      library in Gradle yet. Planned: ORB-based object re-detection (reference
                      image -> find the same object in new frames), for object pixelation, NOT
                      for people. Recommended library: OpenCV Android (org.opencv:opencv, Maven
                      Central). Open hardware questions: robot CPU ABI, and whether the camera is
                      free or held by RobotOS's vision service (jar has client.surfaceshare.SurfaceShareApi).
                      OpenCV dependency ADDED 2026-09-15: `org.opencv:opencv:4.14.0` in app/build.gradle.kts
                      (owner approved). :app:compileDebugKotlin + checkDebugAarMetadata pass with
                      compileSdk 34 / minSdk 26. Maven Central also has 5.0.0; 4.x chosen because
                      docs and tutorials target it. No abiFilters yet (robot ABI unknown:
                      `adb shell getprop ro.product.cpu.abi`). Call `OpenCVLoader.initLocal()` before use.

### robocontrol/vision (implemented 2026-09-15, compiled, NOT run on hardware)

- `ORB.kt`: owner-named `class ORB` (OpenCV's ORB is imported as `OpenCvOrb` to avoid the name clash).
  The reference **dictionary is keyed by class name** (`addReference("calendar", bitmap|GrayImage)`,
  `orb["calendar"]`, `referenceMap`, `removeReference`, `clearReferences`). `detect(frame)` returns only
  detected objects; `evaluate(frame)` returns every reference with goodMatches/inliers, for tuning.
  Pipeline: ORB (1000 features) → BFMatcher Hamming kNN k=2 → Lowe ratio 0.75 → ≥15 good →
  `findHomography` RANSAC 5 px → ≥12 inliers + convex outline ≥400 px² → detected, with
  corners projected into the frame. References are downscaled to a 640 px longer side. Everything is
  synchronized; native Mats are released explicitly; call `release()` when done.
- **Design rule (owner, 2026-09-15): ORB never controls the camera.** It only analyses images it is
  given (`detect/evaluate` accept `GrayImage` or `Bitmap`). Frame acquisition belongs to the caller,
  e.g. a background thread in an activity sampling every 0.5–1 s. `CameraSnapshot` is an optional
  helper for that caller, not a dependency of ORB.
- **Blurring the video stream (design notes 2026-09-15, not implemented):** SurfaceShare gives the
  app a COPY of the frames. Blurring can only protect streams the app itself outputs (robot screen,
  video to the phone, recordings). It cannot change what RobotOS's VisionSDK sees, because that service
  reads the camera first. State this limit in the thesis. Planned pipeline: continuous stream
  (not CameraSnapshot's open/close) → ORB on the Y plane every Nth frame → reuse the last corners in
  between (optionally LK optical flow) → polygon mask (+ margin) → strong pixelation or solid fill (Gaussian
  blur is weaker for text) → output. Privacy-first: output a frame only after its region decision
  exists, and keep blurring the last region for a grace period after detection is lost (fail closed).
  ORB misses (plain objects, blur, angle) leak, so be honest about that in the evaluation.
- `CameraSnapshot.kt`: `suspend capture(): SnapshotResult`. It opens the SurfaceShare stream, skips 2
  frames, copies the Y plane of the next into a `GrayImage`, then abandons the stream and closes the
  reader/thread/surface. Meant for sampling every 0.5–1 s. Needs RobotApi connected first (else
  `NotConnected`, requestImageFrame = -1). Errors: -14 → `CameraBusy`, -16 → `Preempted`, `Timeout` (3 s).
  UNVERIFIED: per-capture start-up time (check `elapsedMs`; if too slow keep the stream open),
  dark first frames, the success return code (assumed ≥ 0), actual frame size, whether the CAMERA
  permission is needed (none added).
- **Reference images from assets (2026-09-15):** the owner created `app/src/main/java/com/example/robocontrol/assets/`.
  That is NOT a default Android assets dir, so `app/build.gradle.kts` maps it with
  `android.sourceSets["main"].assets.srcDir(...)`; its files land at the APK assets root.
  `ORB(context, config)` now loads every png/jpg/jpeg/webp/bmp/gif/heic/heif there on construction:
  key = file name without extension; decoded as ARGB_8888 with power-of-two subsampling for big photos,
  then gray + downscale + ORB features via `addReference`. `orb.assetLoadReport` lists loaded names
  and rejected files with reasons (undecodable / too few features / duplicate name). **Construct
  off the main thread** (decoding + ORB per image). EXIF rotation is ignored (ORB is rotation-invariant).
- **Object test app (owner request 2026-09-17, compiled + installed, not yet run):** owner added `assets/Calendar_prop.jpeg`
  (6.3 MB) → reference "Calendar_prop". New `vision/CameraStream.kt`: continuous SurfaceShare stream (name "RoboGuardObjectTest",
  ImageReader YUV_420_888 640×480, keeps only the latest `CameraFrame` = Y-plane GrayImage + BT.601 ARGB preview bitmap, abandon on
  stop unless −14). `objectprobe/ObjectProbeActivity.kt`, launcher "RG Object Test": builds ORB off the main thread and logs the asset
  report (keypoints per reference, rejected files); refuses if Sensors Camera=false; connects via RobotApiConnection; runs
  `orb.evaluate` on the latest frame every ≥150 ms; preview with rotation 0/90/180/270; detected → green projected outline + yellow
  axis-aligned box + "name (inliers)"; stats line (good/inliers/detection ms); logs DETECTED/lost transitions (numbers only).
  README: "Testing object detection". Unverified: SurfaceShare frame orientation, preview FPS with the per-pixel YUV→RGB loop, ORB time on
  the robot's CPU, whether the huge JPEG decodes with enough keypoints.
  **First run (probe-20260917-134840.log):** ORB ready in 467 ms, Calendar_prop 943 keypoints (reference 640×480), stream code 0,
  ~39 s of camera, NO detection logged; per-frame numbers were on screen only. Owner reported low preview FPS and no box. Causes found:
  preview updated only inside the detection loop (FPS = detection rate) and per-pixel ByteBuffer.get YUV→RGB. Reference photo analysis
  (PC): 4032×3024, EXIF orientation 6 (ORB ignores EXIF, fine due to rotation invariance), mean 135/255, std 33; calendar with spiral
  binding + identical grid cells (repetitive → ratio test rejects), large white areas, table at the edges. Robot camera snapshot earlier:
  mean brightness 48/255 (dark). **Changes (installed, not yet run):** CameraStream bulk plane copies + integer BT.601 + per-frame
  mean brightness/contrast (Y std); separate preview loop (~30 Hz) and detection loop (every new frame); log summary every 2 s (camera
  fps, preview fps, detection rate/avg ms, brightness, contrast, boost, frame keypoints, good/inliers); `OrbConfig.contrastBoost` (CLAHE
  clip 2.0, 8×8, lazy after OpenCV load) + `ORB.config` mutable + `lastFrameKeypoints`; screen toggle "Contrast boost". Boxes held:
  last successful detection per object stays drawn until the next successful detection or 1.5 s without one (owner: box must stay
  until the next detection frame).
  **Run probe-20260917-135314.log (VERIFIED):** camera ~29 fps, preview ~26 fps, detection ~18/s at 50–60 ms (FPS fix works); boost on
  the whole run; brightness 36 at start then 160–214 (partly overexposed), contrast 42–72; frame keypoints 1000 (= maxFeatures) in EVERY
  frame; Calendar_prop good matches 1–11 (< 15), inliers always 0 → never reached the homography. Brightness/contrast not the bottleneck;
  matching is. Explained to the owner: ORB already filters like SIFT (FAST threshold ≈ contrast threshold, Harris ranking of the best
  nfeatures ≈ edge rejection; OpenCV's `edgeThreshold` is only the border size), ratio test at matching comes from SIFT; the filters rank
  strength, not relevance, so strong background corners take all 1000 and repetitive calendar corners die in the ratio test.
  **Owner: no SIFT for now (discuss alternatives later), try the other fixes. Implemented (installed, not yet run):** OrbConfig
  `fastThreshold` (default 20), `gridDistribution` (8×6 cells, over-detect ×4 with a second ORB, keep top response per cell, then
  compute), `ORB.applySettings(config)` recreates detectors and re-extracts every reference from its stored 640 px image; screen controls
  features 1000/3000/5000, FAST 20/10/5, grid off/on; "Use current frame as reference" → reference "Camera_capture" from the live gray
  frame (memory only) + remove; 2 s summary logs the detector settings.
  **Assets moved (owner):** reference images now in `robocontrol/assets/ORB_img/` (`ORB.ASSET_DIR = "ORB_img"`); Silero model stays in
  the assets root (it was never loaded by ORB: only image extensions are). Owner replaced Calendar_prop.jpeg with a cropped version
  (2611×3862) and added IMG_20260917_140748.jpg (3794×2328, upright, best reference). Run probe-20260917-141656.log: references 884 / 850
  keypoints; detections (good 29–47, inliers 12–17) only at 25.7–31 s when frame contrast jumped 42 → 60–64 (calendar close); otherwise
  good 7–21, inliers 0–8. features 5000: detection 60 → 110–200 ms. Diagnosis: 640×480 frames + 640 px references, ORB pyramid 8×1.2
  reaches down to ~180 px → far calendar too small / too little detail.
  **Camera facts (dumpsys media.camera on the robot, VERIFIED):** device 1 is held by com.ainirobot.maptool's process; RobotOS's session
  stream is **1280×720, format 0x23 (YUV_420_888), dataspace 0x8c20000 = JFIF (full-range)**; aeMode ON, compensation 0, CONVERGED,
  aeRegions whole 1920×1080 sensor, exposure ~6.4 ms, ISO 100, AecLux ~122, aeLockAvailable TRUE. SDK has no exposure control
  (`RobotApi.getVisionResolution` / `STATUS_EXPOSURE` exist, unexplored). SurfaceShare scales into the requested surface size, so the
  old 640×480 was downscaled AND squeezed 16:9→4:3. **Changes (installed, not yet run):** CameraStream default 1280×720; preview uses
  full-range BT.601 (TV-range maths had brightened the preview ~15 %; detection always used raw Y); per-frame `clippedShare` (Y ≥ 250)
  shown and logged as "overexposed %".
  **Wall test (owner goal: user study with the calendar hanging on a wall, robot at a distance). probe-20260917-142219.log + one screencap
  of the robot screen (viewed, then deleted, VERIFIED):** 1280×720 works: camera ~13–15 fps, preview ~13–15, detection ~110 ms at 1000
  features; brightness 185, contrast 38, only 3 % overexposed (exposure is fine). Calendar on a whiteboard ≈ 310×172 px of 1280×720.
  Good matches 4–14, inliers 0 throughout. The screencap shows: strong **wide-angle / fisheye barrel distortion**; the whiteboard is full of
  high-contrast drawings, text, magnets, a poster and a photo that take the 1000 keypoints; calendar digits/lines only a few px. Causes
  given to the owner: too few pixels on the object, keypoint budget used by background, lens distortion breaks the planar homography
  (RANSAC 5 px), low-texture white calendar. Options proposed (not built): grid + more features + lower FAST, detection on upscaled
  overlapping tiles at ~1 Hz, reference captured by the robot camera at study distance, lens calibration + undistortion, temporal
  consistency to allow lower inlier thresholds, and study design (larger / more textured calendar or poster).
  **Owner: "lets try those changes" + screenshot reference (installed, not yet run):** new screencap of the robot screen, calendar region
  cropped (screen px 1380,530–1570,646 = the downscaled preview, ~0.58× frame scale) → `assets/ORB_img/Calendar_robotview.png` 190×116
  (only the calendar; full screenshots deleted). Test app start settings now features 3000, FAST 10, grid on (ORB built with them).
  "Select region as reference": forces rotation 0°, drag a box on the preview (mapped to frame pixels), "Use region W×H px" crops the
  full-resolution gray frame → reference "Camera_capture", saved as grayscale PNG to app-private `files/robocontrol/orb_refs/` and
  reloaded on the next start; "Delete camera reference" removes it and the file.
  **B + E implemented (owner: "implement the rest", then "Dont do D" — a started LensCalibration.kt with checkerboard calibration +
  undistortion was deleted again; installed, not yet run):** `ORB.evaluateTiles(frame, cols 2, rows 2, overlap 0.25, upscale 2.0)` —
  overlapping tiles scaled up and evaluated separately, corners mapped back, best result per reference (detected, then inliers).
  Test app: tiles On/Off (every 1 s, merged with the full-frame result per object, logs "tiles found …" when only tiles detect);
  thresholds 15/12, 10/8, 8/6 (good/inliers, set via ORB.config per pass); consistency On/Off = box only when detected in ≥ 2 of the
  last 3 passes (unconfirmed objects lose their box at once); DETECTED/lost log lines follow the confirmed state; 2 s summary logs
  thresholds, consistency and tile passes/avg ms.
  Owner asked whether all references must match: no, `evaluateGray` matches each reference independently and each gets its own
  result, history and box; boxes were just all green/yellow and overlapped. Now one colour per reference (6-colour palette by name hash),
  thin outline in that colour, box + label in that colour, labels offset per box, colour legend in the references list.
  **Later runs (probe-20260917-143321/144309/145024, VERIFIED):** Calendar_robotview (screenshot crop) only 69 keypoints (165×100 after
  cropping), good 0 → useless. Owner added visual features to the physical calendar and replaced the references (assets/ORB_img now
  "cal_prop_outlines 1.jpg" / "cal_prop_outlines 2.jpg"; older ones moved into subfolders "outdated" and "cal _prop_blueoutdated", which
  ORB ignores but which still ship in the APK). Reference keypoints ~2100–2300 at 3000 features (vs ~850–1500 before). Full frame at
  5000 features: good 27–74, inliers 0–15 (~450–600 ms). **Tiles on: DETECTED at the wall distance** (tiles good 85–133, inliers 13–24 vs
  full frame 34–61 / 0–14); tile pass 1.4–1.8 s. Open: results with inliers well above the threshold (11–44) still "not detected" →
  probably `isPlausibleOutline` (convex/area) under fisheye distortion. Box semantics (final, owner: boxes stayed while the lens was covered):
  boxes = the confirmed detections of the LATEST pass, drawn until the next pass (which may remove them); the latest tile result is
  merged into every full-frame pass until the next tile pass (≤ 1 s old), so tile detections do not flicker.
  **Outline rejection confirmed (probe-20260917-145922.log, VERIFIED):** thresholds 10/8, cal_prop_outlines 2 had good 41–83 and
  inliers 10–24 in almost every 2 s summary but was DETECTED only once; "lost … (good 83, inliers 20)" → `isPlausibleOutline` rejected
  (reference corners projected through the homography fold / degenerate under clustered inliers + wide-angle distortion). With tiles on
  only ~0.8 detection passes/s (full ~250 ms + tile pass ~1 s). **Fix (installed, not yet run):** `outlineProblem` returns "non-convex" /
  "area"; if inliers ≥ minInliers but the outline is implausible, the result is detected with corners = axis-aligned bounding box of the
  RANSAC inlier points (`ObjectMatch.outlineSource` = "inliers", else "outline"; `outlineRejected` = reason). Test app stats show
  "(inlier box)" and "[outline …]". `Notes_thesis.md` created at the repo root (owner request): concise bullet summary of all
  experiments (SDK control, TTS, sensors, navigation/private areas, conversation detection, ORB).
- `GrayImage.kt`: `GrayImage(width, height, pixels)` + `ImagePoint`, with no OpenCV/Android types in the API.
- Tuning order on the robot: log `evaluate()` for frames with and without the object, then set
  `OrbConfig.minInliers` between the two.

### Camera: SurfaceShareApi (jar verified 2026-09-15; docs read; NOT run on hardware)

Official doc: https://doc.orionstar.com/en/knowledge-base/camera-data-stream-sharing/ .
Purpose: get camera frames **without** taking the camera from RobotOS's VisionSDK.
- Jar: `SurfaceShareApi.getInstance()`; `requestImageFrame(Surface, SurfaceShareBean, SurfaceShareListener): Int`;
  `abandonImageFrame(SurfaceShareBean): Int`; `isUsed(): Boolean`.
- `SurfaceShareBean`: `setName(String)`, `setPriority(int)`, `setUseToPreview(boolean)` (`packageName` set by SDK).
  The doc sample only calls `setName("VideoCall")`. Priority semantics are undocumented.
- `SurfaceShareListener` (class): `onStatusUpdate(int, String)`, `onError(int, String)`.
  `SurfaceShareStatus.STATUS_SET_STREAM_SUCCESS = 10`.
  `SurfaceShareError`: `-11` connect timeout, `-12` server exit abnormally, `-13` server exit,
  **`-14` SURFACE_SHARE_USED** (someone else holds the share), `-15` set stream surface failed,
  **`-16` PREEMPTED**. -16 plus the priority field suggests a higher-priority consumer can take the stream (unverified).
- Doc sample: `ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 4)` on a HandlerThread;
  pass `imageReader.surface`; `acquireLatestImage()` and **always `image.close()`**.
  Stop: `abandonImageFrame(bean)` (skip if the error was -14), quit threads, close the ImageReader, release the Surface.
- Doc warnings: high memory/CPU cost, turn it off when unused, unclosed Images cause OOM.
- Doc sample methods `startRequest()` / `stopPushStream()` belong to the sample class
  `SurfaceShareDataEngine`, NOT the SDK.
- FAQ (https://doc.orionstar.com/en/faq/camera-orientation-is-wrong-or-cant-work/): the camera
  defaults to 90° portrait while the robot is landscape; rotate frames by -90°/270°. There is a Camera1 demo
  for the GreetBot Mini (zip linked on that page). Whether SurfaceShare frames are also rotated is unverified.
- OrionStar's sample repo (github.com/OrionStarGIT/RobotSample) `VisionFragment` uses only PersonApi,
  with no SurfaceShare example.
- For OpenCV: the Y plane of YUV_420_888 is already the grayscale image ORB needs (mind rowStride).
- FAQ (https://doc.orionstar.com/en/faq/no-sound-during-video-call/): **the mic array only records
  with "specific audio settings"** ("Robot audio collection parameters" in the APK dev docs).
  Relevant to `MicAccessProbe`; the parameters are not yet read (docs host DNS failure).
app/libs/robotservice_12.3.jar   OrionStar SDK (local jar, not on Maven)
```

### testing/voiceprobe (2026-09-15, compiled, NOT yet run on hardware)

Test code, not product code. `VoiceProbeActivity` (Compose) is started with
`adb shell am start -n com.example.roboguard/com.example.testing.voiceprobe.VoiceProbeActivity`.
- `MicAccessProbe`: tries 5 `AudioSource`s mono at 16 kHz, then 2/4/6/8 channels (index masks)
  at 16 and 48 kHz. Logs per-channel RMS and correlation to channel 0 (≈1.0 means duplicated,
  not a separate mic), plus input devices and active recordings. Keeps statistics only, never audio.
- `SpeechServiceProbe`: logs all `SkillCallback`s, toggles `setMultipleModeEnable`, reads `getTtsPlayStatus`.
- `SoundDirectionProbe`: listens on `ModuleCallbackApi.onSendRequest`, the WAKEUP/SLEEP broadcasts
  and `status_speaker` for an angle.
- `ProbeLog`: screen plus `filesDir/voiceprobe/probe-*.log`. Speech payloads go to the file as
  shape only (length, JSON keys), with no transcript content.
- Manifest additions (shared with RoboGuard, made at the owner's request for these tests):
  `RECORD_AUDIO`, `com.ainirobot.coreservice.robotSettingProvider`, the probe activity.
- Step-by-step test procedure for the owner: README.md, "Testing the voice probes" (added
  2026-09-15 at the owner's request, as the only change to README.md).
- **Mic access results (robot ZTT18P1000A0, Android 9, probe-20260916-121719.log, VERIFIED):**
  - 4 input devices (type 15 twice: channels 1/2/3/4/6, index masks 7/15/63; types 16 and 18: 1–2 ch), rates 8–48 kHz.
  - **An ordinary app CAN record, but the source matters:** mono 16 kHz levels with the same room sound:
    **CAMCORDER -37.4 dBFS (clear signal)**, MIC -77.1 (weak), VOICE_RECOGNITION -92.4, VOICE_COMMUNICATION -95.3,
    UNPROCESSED -94.3 (≈ silent). The sensor test later measured MIC at -34.8, -43.8, -66.2, -91.7 and -103 dBFS in
    different runs, so MIC is unreliable. **Use CAMCORDER for app-side audio (conversation detection).**
  - **Multi-channel is useless:** 2/4/6/8 ch at 16 and 48 kHz give channel 0 at ≤ -50 dBFS and all others -Infinity,
    so no raw mic array is exposed and bearing must come from RobotOS, not from the app.
  - **`com.ainirobot.remotecontrolservice` (uid 1000) records from MIC permanently** (`dumpsys audio`
    RecordActivityMonitor: session 57, 2ch→1ch 16 kHz, running since boot). Our recordings run alongside it
    (Android 9 allows concurrent capture, sometimes with silence).
- `MicAccessProbe` is the deciding experiment for the conversation detector below. If no
  `AudioSource` delivers signal while the speech service runs, change detection on raw audio is
  blocked, and only `SkillApi` signals (VAD timing, volume, multiple mode) and bearings remain.

## Conversation detection (design decision 2026-09-15, from the claude.ai session)

Question to answer: **one speaker, or more than one?** Not "who", and not "how many in total".

**Decision: speaker-change detection, NOT speaker embeddings/fingerprints.**
- GDPR Art. 4(14): biometric data = processing that "allow[s] or confirm[s] the unique
  identification of a natural person". Counting distinct voices in a short window is arguably
  differentiation, not identification. Present that as an argument, not settled law.
- Weakness of that argument for embeddings: a vector that separates A from B *can* identify, one
  stored array away. Reviewers judge capability and purpose, not only retention.
- Change detection outputs **a scalar distance between two adjacent moments**, not a vector that
  characterises a person. Nothing identifying exists to store or match. That is a stronger claim
  than "embeddings are deleted", and it fits opt-out-by-default.

**Method.** MFCC frames → two adjacent windows, each modelled as a Gaussian → test whether one
distribution or two fits better:
- BIC (ΔBIC > 0 ⇒ change), or cheaper: symmetric KL (KL2) / Mahalanobis between window covariances.
- DISTBIC pattern: a distance pass proposes candidate boundaries, BIC confirms them.
- Fuse with the **sound-source bearing** (if the probes find one): two independent weak signals
  that agree beat one strong signal.
- Strength: "did the voice change". Weakness: "how many distinct people in total". That is fine
  for one-vs-many.

**Implementation plan (not started):** a `ConversationDetector` interface with a fake audio feed
(testable without a mic, like `FakeRobotBridge`). Change detection is the primary implementation.
Optionally, an embedding implementation behind the same interface for comparison in the
evaluation chapter only.

**IMPLEMENTED 2026-09-17 (owner: change detection first, clustering possibly later; compiled + installed, not yet run on the robot):**
`robocontrol/conversation/`: `Mfcc.kt` (plain Kotlin: pre-emphasis 0.97, 25 ms Hamming, FFT 512, 26 mel bands 100–7600 Hz,
log, DCT → c1…c12, c0 dropped so loudness is not a change), `Gaussian.kt` (full covariance + 1e-3 ridge, Cholesky, KL2,
ΔBIC = ½(N log|Σ| − N₁ log|Σ₁| − N₂ log|Σ₂|) − λ·½(d + ½d(d+1))·log N), `ChangeDetector.kt` (pure; speech gate = level >
max(noise floor + 12 dB, −55 dBFS), floor creeps up 2 dB/s; windows of 150 speech frames = 1.5 s; every 10 frames KL2 of the
two latest adjacent windows; candidate = local KL2 max ≥ 1.3 × running mean and ≥ 100 frames after the last change; accepted if
ΔBIC > 0; ring buffer of 2W+2step frames, zeroed by clear()), `PcmSource.kt` (`AndroidMicSource` AudioRecord CAMCORDER 16 kHz
mono; `SyntheticVoicesSource`: pulse train + 3 formant resonators, 4 syllables/s, voice A 115 Hz/formants ×1.0, voice B 215 Hz/
×1.2, `expectedChangesMs` truth), `ConversationDetector.kt` (interface + `SpeakerChangeDetector` thread: 10 ms hop, publishes
`ConversationSnapshot` every 100 ms; states NO_SPEECH / LISTENING (< 3 s speech) / ONE_SPEAKER / MULTIPLE_SPEAKERS = ≥ 2 accepted
changes in 20 s; buffers zeroed on stop). Config in `ChangeDetectorConfig`.
**Offline JVM check (kotlinc from the Android Studio plugin, synthetic voices, VERIFIED on the PC):** one voice 30 s (A and B):
0 accepted (10–14 candidates all rejected by ΔBIC); A/B every 4 s: 7/7 changes within ±1 s, 0 false, KL2 peaks 190–280 vs one-voice
mean ~3–4, ΔBIC 760–1090; A/B with 0.7 s pauses: 5/7 within ±1 s plus 2 changes found ~1.2–1.4 s early. Synthetic output needed
gain 250 (first version peaked at −54 dBFS and never passed the speech gate). Real voices are NOT tested yet.
**Test app:** `testing/conversationprobe/SpeakerProbe.kt` + `SpeakerProbeActivity.kt`, launcher icon "RG Speaker Test"
(taskAffinity com.example.roboguard.speakerprobe). Mic start refused if Sensors says Microphone=false; asks RECORD_AUDIO;
"Synthetic self-test (fast)" (4 scenes, PASS/FAIL), "Synthetic demo (40 s, live)", ✔/✘ verdicts for 5 scenarios; big state banner,
numbers, KL2 graph with threshold; ProbeLog gets numbers only. onStop stops listening. README: "Testing speaker change detection".

**First robot run (probe-20260917-111600.log, VERIFIED):** one speaker from a video → MULTIPLE_SPEAKERS after 5 s of speech
(accepted "changes" with KL2 25–46, ΔBIC 231–392 at λ=1, penalty ≈257); "silence" test still MULTIPLE and 20 s of "speech"
counted overall: the speech gate let background noise in (floor tracker + 12 dB margin), and MULTIPLE had priority over
NO_SPEECH for the 20 s decision window, so silence showed late. **Changes (installed, not yet re-run):** noise floor = 10th
percentile of the last 5 s of frame levels (updated every 25 frames), margin 15 dB, speech also needs ≥ 6 of the last 20 frames
loud; NO_SPEECH wins over MULTIPLE (hold 1.5 s); λ default 2.0; KL2 running mean = plain average for the first 50 evaluations
(the old EMA started at the first value and blocked all candidates); full reset of buffered speech + counted changes after
`resetAfterSilenceMs` = 30 s of silence (owner asked 30 s). Candidates log BIC gain and penalty ("would need λ < x"). Screen:
settings λ 1/1.5/2/3, window 1.0/1.5/2.5 s, speech margin 10/15/20 dB (restart listening), floor shown.
Offline gate comparison (synthetic): margin 12 no run filter 7/7 + 6/7 (1 false); margin 15 run≥6: one voice 0 candidates,
A/B 6/7, A/B with pauses 6/7, 0 false → chosen. Run≥12 halved speech frames and broke detection.

**Speech gate: Silero VAD (owner decision 2026-09-17, after a privacy comparison of self-built voicing check / WebRTC-VAD /
Silero; compiled + installed, not yet run on the robot).** Problem: the loudness gate counted loud noise (claps, doors, music) as
speech. Library `com.github.gkonovalov.android-vad:silero:2.0.10` (JitPack) pulls kotlin-stdlib 2.2.0 while the app builds with
Kotlin 2.1.0, so NOT used; instead its bundled model `silero_vad.onnx` (Silero v4, MIT; sha256 a35ebf52…5af28) + LICENSE were copied
to `robocontrol/assets/` and run with `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` via own wrapper
`conversation/SileroVad.kt`. Model I/O VERIFIED on the PC with desktop onnxruntime 1.22.0: inputs `input` float[batch, seq],
`sr` int64 (scalar; shape [1] also accepted), `h`/`c` float[2, batch, 64]; outputs `output` [batch, 1], `hn`, `cn`. PC check:
silence p≈0.02, Gaussian noise 0.3 p≈0.02–0.03, loud clicks p≈0.04–0.06 (no real speech sample tested). Wrapper bytecode showed
android-vad's thresholds: 0.5 / 0.8 / 0.95 by mode. Integration: `ChangeDetectorConfig.speechGate` = SILERO (default) | LOUDNESS,
`sileroThreshold` 0.5 with hysteresis 0.15, plus level > speechMinDb − 10; 512-sample chunks (32 ms) filled from the 10 ms hops,
latest probability applies to following frames; state reset on the 30 s conversation reset; tensors/buffers zeroed, session
closed on stop; load failure → error text + loudness fallback. Synthetic self-test and demo force LOUDNESS (the buzz is not speech).
Screen: gate Silero/Loudness, Silero threshold 0.3/0.5/0.7, "Silero p=…" in the numbers line. Privacy notes given to the owner:
all on-device; Silero least explainable, unknown training data; better recall protects against missed conversations.

**Overlap / slow detection (owner, 2026-09-17, probe-20260917-114115.log):** sequence speaker 1 → both at once → speaker 2 took
until 20.3 s for MULTIPLE (window 1.0 s, λ 2.0): accepted changes needed ratios 2.04 and 2.19, several near misses 1.69–1.96; one
voice (video) earlier peaked at 1.42–1.65. Causes explained: overlap = gradual mixture (two smaller KL2 jumps), 1 s min gap
suppresses the second, COUNT rule needs 2 changes in 20 s. Owner asked whether evidence accumulation is standard: answered that
DISTBIC and CUSUM (Page 1954; Basseville & Nikiforov 1993) are standard, applying CUSUM to change candidates for one-vs-many is
our own heuristic; standard speaker counting = clustering or neural (e.g. CountNet, Stöter et al. 2018) (citations from memory,
unverified). **Implemented (compiled + installed, not yet run):** `DecisionRule` COUNT | EVIDENCE (default); both computed and
logged on every flip; evidence += max(0, gain/penalty − r₀) per candidate (accepted or not), halves every `evidenceHalfLifeMs`
(10 s), ≥ `evidenceThreshold` (0.5) → MULTIPLE held for decisionWindowMs (20 s); r₀ default 1.65 (depends on window length:
gain ∝ N, penalty ∝ log N). Screen: rule, r₀ 1.5/1.65/1.8/2.0, threshold 0.3/0.5/0.8, half-life 5/10/20 s, evidence bar.
Worked example on the 114115 log: evidence would cross 0.5 at 11.0 s instead of 20.3 s.

**Conversation monitor in RoboGuard (owner request 2026-09-17: "scheint okay mit den aktuellen Werten"; compiled, install pending):**
`conversation/ConversationMonitor.kt` (object), started in `RobotServerService.onCreate`, stopped in onDestroy. Listens (default
config: Silero gate, evidence rule, window 1.5 s — NOTE the owner's "okay" run used the probe screen's settings, not necessarily
these defaults) only while Sensors says Microphone ≠ false, RECORD_AUDIO granted, not paused, and no speaker test screen records
(`setProbeUsingMicrophone`, called by SpeakerProbe). MULTIPLE_SPEAKERS for ≥ 2 s (owner) → `ConversationPromptActivity` (dialog, German)
+ German TTS (owner's wording "Bitte entschuldigt die Störung, falls ihr möchtet dass ich den Raum verlasse oder mein Mikrofon
stummschalte, lasst es mich bitte wissen.") after `system/SdkControl.awaitControl` (isActive poll + 1 s settle). Buttons: "Raum verlassen
(Navigation und Karte)" → MapNavigationActivity; "Mikrofon ausschalten" → new `Sensors.setSensor("Microphone", false)` (applies via
update + writes privacy_settings.json atomically if it exists; phone's next save overrides; also triggers SensorSwitches' SDK ASR off,
which may be one-way until reboot); "Gesprächserkennung N Min. pausieren" (default 30, "Andere Dauer": 5/15/30/60/120 or 1–1440 typed;
pause in memory only); "Schließen". No re-prompt in the same conversation (until detector's 30 s silence reset, detected via
speechSeconds == 0) and not within 2 min.
First real use: prompt appeared almost immediately with the defaults (r₀ 1.65, threshold 0.5, half-life 10 s — measured with 1.0 s
windows). The owner's successful probe run (probe-20260917-115530.log) used window 1.5 s, λ 2.0, Silero 0.5, EVIDENCE r₀ 1.8,
threshold 0.8, half-life 5 s → these are now the `ChangeDetectorConfig` defaults (used by the monitor and as probe start values).
Prompt redesigned (owner): English, no spoken sentence on screen (title "Conversation detected"), small buttons (44 dp, 15 sp) in two
rows: Leave room | Mute mic, Pause N min | Other time | Close; time page: 5/15/30/60/120 + Minutes field with Set / Back.
Owner follow-up: "Mute mic" also says (German, owner's wording) "Das Mikrofon kann über die App wieder eingeschaltet werden"
(Mute is permanent until the phone app saves Microphone on; Pause does not touch the mic). Pause button label now
"Don't ask again for N min", now in its own full-width row (owner).
Misdetection 18:27:42 on the robot WITH the tested defaults active (process 13352 started 18:26:59 after the install; the monitor
logged nothing but the prompt, so not traceable). Now the monitor logs its config at start and every candidate to Logcat (tag
ConversationMonitor: time, r, evidence added, ΔBIC). Unverified hypothesis told to the owner: the robot's own voice (TTS, e.g.
the /save confirmation) is picked up by the mic and counts as a second speaker; possible fix = ignore audio while
AudioManager.activePlaybackConfigurations is non-empty (plus ~0.5 s).
**Traced misdetection 18:29:53 (monitor candidate log, VERIFIED numbers):** candidates 17–27.8 s r = 1.75–1.95 (each +0.06–0.15
evidence, ~every 2 s, built up to ~0.3), 28.3 s r 2.26 (+0.46 → 0.74), 30.4 s crossed 0.8; 28.3/31.5/33.2/34.2 s r 2.09–2.37 were
even ΔBIC-accepted, so the COUNT rule would also have fired (31.5 s, ~1 s later). Owner asked what was audible from ~28 s (unanswered).
Recalculation: r₀ 2.0 would have stayed at 0.66 (no prompt); λ 2.5 would reject all four accepted ones.
**Candidate grouping (owner: "yes lets do that"):** candidates came every 0.2–0.3 s around one boundary (evaluation every 10 speech
frames, spacing only after ACCEPTED changes), counting overlapping windows several times. First version (delay each candidate until its
group is complete) delayed up to 2 windows of speech and lost end-of-scene changes offline (5/7, 4/7) → replaced by immediate grouping:
candidates whose boundary is within one window (150 speech frames) of the group's first boundary form a group; `ChangeCandidate` now
carries `groupBestRatioBefore` / `groupAlreadyAccepted`, `ratio`, `evidenceAdded(r0)` = only the increase over the group's best so far,
`countsAsChange` = first accepted of its group. Evidence and COUNT rules, monitor log, probe log and self-test use them. Offline synthetic:
one voice 0 changes; A/B 6/7; A/B with pauses 5/7 (was 6/7; pauses give ~1.6 s speech per turn, close to the group span). Installed,
not yet run on the robot. Approximate re-run of the traced case with grouping: crossing at ~34.2 s instead of 30.4 s (still triggers).
**Latency analysis (owner: "delay seems pretty intense", monitor log 18:38 and 18:43, VERIFIED numbers):** first r ≥ 2.0 change to robot
speaking ≈ 9–10 s: ~5.7 s until evidence crossed 0.8 (grouping over a whole window merged REAL consecutive changes 1–2 s apart: +3.6 s at
18:43, +1.5 s at 18:38 vs no grouping), 2 s hold, ~1–1.5 s SDK control + settle. One voice r ≈ 1.7–1.98, real changes mostly 2.0–2.3 (some
2.6–3.4) → small evidence steps. Ideas given: group span 0.5 s, shorter hold, settle 0.8 s, two-stage/shorter windows, better features
(pitch/F0 in memory only, delta MFCC), adaptive r₀; single strong change and half-life 10 s would not have helped in these two logs.
**Owner chose 1 + 2:** `ChangeDetectorConfig.groupFrames` = 50 (0.5 s of speech) instead of windowFrames; `MULTIPLE_FOR_MS` 2 s → 1 s.
Offline synthetic: one voice 0, A/B 6/7, A/B with pauses 6/7 (back from 5/7). Estimated from the logs: crossing as early as without grouping
(422.3 s / 82.1 s). Installed 13:42 (after the install fix below).
**Stuck install (2026-09-17, VERIFIED):** a 270 MB WiFi install hung for 30 min (robot pingable, adb `device`, but the TCP byte counter to
the robot stopped changing); an earlier attempt was killed by my own `pkill -f "adb … install"` because the pattern matched the bash command
running it (never pkill by a pattern contained in your own command line; kill by PID). `dumpsys package` listed 13 install sessions,
but ALL under "Historical install sessions" (interrupted ones: mDestroyed=true, "Session was abandoned"); "Active install sessions" was
empty, so `pm install-abandon` gives "Caller has no access" and nothing needs cleaning. Fix: kill the adb install by PID, adb kill-server /
start-server / connect. **APK size:** `defaultConfig { ndk { abiFilters += "arm64-v8a" } }` in app/build.gradle.kts (owner approved) →
APK 270 MB → 73 MB (OpenCV + ONNX Runtime had 4 ABIs); install now takes ~6 s.

**Audio source:** the jar has no PCM API, so use plain Android `AudioRecord` + `RECORD_AUDIO`.
This is portable across robots, the same argument the outline makes for eSpeak over OrionStar TTS.

**If embeddings are ever built (evaluation only), hygiene rules:**
- `FloatArray` only, zeroed explicitly after use. Never `String`, never Logcat (logging a distance
  is fine, a vector is not). Heap dumps, ANR traces and crash reports can serialize live objects.
- The **raw PCM buffer is the bigger exposure** (1–3 s of actual speech content): use a fixed-size
  ring buffer, overwritten in place, never copied. The same applies to change detection.
- Enforce it in the type: a class that can only be *compared*, never read out or serialized.

**References**
- S. S. Chen, P. S. Gopalakrishnan (1998). *Speaker, Environment and Channel Change Detection and
  Clustering via the Bayesian Information Criterion.* Proc. DARPA Broadcast News Transcription and
  Understanding Workshop. Origin of BIC segmentation; cite for the method.
- P. Delacourt, C. J. Wellekens (2000). *DISTBIC: A speaker-based segmentation for audio data
  indexing.* Speech Communication 32(1–2), 111–126. Distance pass + BIC confirmation; the one to
  implement from.
- M. A. Siegler, U. Jain, B. Raj, R. M. Stern (1997). *Automatic segmentation, classification and
  clustering of broadcast news audio.* Proc. DARPA Speech Recognition Workshop. The symmetric
  KL (KL2) distance for change detection. (Added by Claude Code; citation not checked against
  the PDF.)
- X. Anguera, S. Bozonnet, N. Evans, C. Fredouille, G. Friedland, O. Vinyals (2012). *Speaker
  Diarization: A Review of Recent Research.* IEEE TASLP 20(2), 356–370. Standard survey; cite so
  embeddings read as rejected, not overlooked.

Two packages in one module, on purpose. `robocontrol` has **no** references to
`roboguard` and nothing in `roboguard` imports it yet. That separation is
deliberate — do not wire them together unless asked.

## Working constraints (from the project owner)

- **Do not change RoboGuard's existing source, comments or documentation** unless
  explicitly asked. It is a working, already-submitted-adjacent codebase.
- Do not add README/docs files unprompted. This file was explicitly requested.
- State clearly which files you changed, every time.
- **Vision-based person detection is ruled out for conversation detection** (owner's
  decision, 2026-09-15): identifying people via camera is exactly the thing the thesis
  objects to. So `PersonApi.getMouthMoveScore/getMouthState`, `getAllFaceList` and
  face-based person counting are NOT to be used for detecting whether people are
  talking, even though the jar exposes them. Audio and sound-source direction only.

## RoboGuard (existing app)

Robot-side server the phone app talks to. Phone repo: `leonkarim-preusse/RoboGuardAndroidEnd`.

- `RobotServerService` — foreground Service. Ktor HTTPS on **8443**, mDNS
  (`robot-<12 digits>.local`), self-signed RSA cert with IP+DNS SAN via BouncyCastle,
  PKCS12 keystore in `filesDir`.
- Auth: `POST /otp_auth` pairs (OTP + client name → id/secret), then every request
  carries `X-Client-Id` + `X-Client-Secret` HMAC over the payload.
- Routes: `/ping`, `/otp_auth`, `/capabilities` (auth), `/update_capabilities`
  (**localhost-only**, intended for robot-side logic to publish real capabilities),
  `/save` (auth, writes `privacy_settings.json`).
- DTOs shared with the phone: `AppSettings(sensors, rooms, situationalSettings, sleepTime)`,
  `RoomSettings(name, sensors)`, `RobotCapabilities(sensors, rooms, situational)`.
  Sensors: Camera, LIDAR, Ultrasonic, Collision, Microphone.
  Rooms: Living Room, Kitchen, Bedroom, Bath, Other.
  Situational: Discretion Mode, Pixelate Objects.
- `MainActivity` declares `action.orionstar.default.app` (boot/default app).
- `PopupActivity` — transient dialog Activity, auto-dismisses after 5 s.
- `BootReceiver` — starts the service on BOOT_COMPLETED.
- Build: minSdk 26, targetSdk 28, compileSdk 34, AGP 8.13.2, Kotlin 2.1.0,
  Ktor 2.3.12, Room + SQLCipher, coroutines 1.7.3. No test dependencies at all —
  adding any test requires adding `testImplementation` first.

**Note:** `targetSdk = 28` is load-bearing. The OrionStar SDK needs legacy external
storage; at 29+ scoped storage makes `READ/WRITE_EXTERNAL_STORAGE` inert. The app is
side-loaded onto the robot, never Play Store, so the Play minimum does not apply.

## robocontrol package

Pure logic, no `roboguard` dependency. Uses only coroutines (`StateFlow`) and
`org.json`. Runs and is testable without the robot and without the jar.

| File | Role |
|---|---|
| `MapGeometry.kt` | `Point2D`, `RobotPose`, `PoseStatus`, `Zone` (polygon, ray-cast containment, margin, centroid), `MapZones` |
| `ZoneRegistry.kt` | JSON persistence per map in `filesDir`; `ZoneBuilder` builds a zone from corners the robot was driven to |
| `RobotBridge.kt` | Interface over the SDK; `NavEvent` / `NavFailure` sealed types |
| `FakeRobotBridge.kt` | In-memory robot, advanced by `tick()` — deterministic, no timers |
| `PrivacyGuard.kt` | Zone decisions, time-boxed overrides, audit log |
| `NavigationController.kt` | `goTo(room/point)`, pre-move check **and** in-motion abort |
| `OrionStarBridge.kt` | Real SDK binding. Compile errors fixed; `navigateTo(Point2D)` still a stub |

### Design decisions worth preserving

- **Enforcement runs twice.** Pre-move (refuse a target inside a private zone) and
  in-motion (abort if the pose enters one). The second is not redundant: RobotOS
  plans its own route and there is no API to constrain the planner, so a legal
  target can still be routed through the bedroom.
- **0.5 m margin** on zone tests. The robot is 0.41 m wide and localization drifts;
  a hard boundary means it is already through the doorway when the check trips.
- **Ray casting, not bounding boxes.** L-shaped rooms are common.
- **Overrides are time-boxed** (max 1 h), carry an `OverrideReason` and `grantedBy`,
  and everything lands in `PrivacyGuard.audit()`. An override nobody can inspect
  would reproduce the invisible-data-gathering problem the thesis is about.
- **`PoseStatus.FORBIDDEN` (SDK forbidden area) out-ranks app overrides.**
- Zones are bound to a map name, because OrionStar locations are.

## OrionStar SDK — VERIFIED against robotservice_12.3.jar

Everything in this section was read out of the jar with `javap`, not from the docs.
**The public knowledge base at doc.orionstar.com is incomplete and in places wrong.**

Packages: `com.ainirobot.coreservice.client.{RobotApi, Definition, ApiListener, StatusListener}`,
`...client.listener.{ActionListener, CommandListener}`,
`...client.actionbean.Pose`, `...client.speech.SkillApi`,
`...client.ashmem.ShareMemoryApi`.

- `ApiListener` — **interface** (`handleApiConnected/Disconnected/Disabled`)
- `StatusListener` — **class**, at `client.StatusListener` (NOT under `.listener`)
- `ActionListener` / `CommandListener` — **classes**; `CommandListener extends ActionListener`
- `RobotApi.getInstance()`, `connectServer(Context, ApiListener)`,
  `setCallback(ModuleCallbackApi)`, **`disconnectApi()`** (there is no `disconnectServer`)
- `registerStatusListener(String, StatusListener): String` / `unregisterStatusListener`
- `startNavigation(int, String, double, long, ActionListener)` — plus ~30 overloads,
  including `startNavigation(int, Pose, double, long, ActionListener)` for coordinates
- `stopNavigation(int)` — note `stopMove` does **not** stop navigation
- Async: `setPoseEstimate/switchMap/setLocation/removeLocation/getLocation/getPlaceList/getMapName/isRobotEstimate(int, …, CommandListener)`
- **Synchronous variants exist and are simpler**: `isRobotEstimate(): Boolean`,
  `getMapName(): String`, `getPlaceList(): List<Pose>`
- `Pose` — `Pose(float,float,float)`, `getX/getY/getTheta/getStatus/getName/getDistance`
- Constants: `RESULT_OK = 1`, `ACTION_RESPONSE_ALREADY_RUN = -1`,
  `ERROR_DESTINATION_NOT_EXIST = -108`, **`ERROR_DESTINATION_CAN_NOT_ARRAIVE = -109`**
  (typo is in the SDK), `ERROR_IN_DESTINATION = -113`, `ERROR_NOT_ESTIMATE = -116`,
  `ERROR_MULTI_ROBOT_WAITING_TIMEOUT = -125`;
  `STATUS_POSE = "navi_pose"`, `JSON_NAVI_POSITION_X/Y/THETA/STATUS = "px"/"py"/"theta"/"status"`;
  `STATUS_START_NAVIGATION = 1014`, `STATUS_NAVI_AVOID = 1018`, `_AVOID_END = 1019`,
  `_OUT_MAP = 1020`, `_MULTI_ROBOT_WAITING = 1034`
- Pose status enum: `SAFE=0, NOT_SAFE=1, OBSTACLE=2 (forbidden area), OUTSIDE=3`
- **Listener callbacks (verified 2026-09-14 via bytecode):** `onResult/onError/onStatusUpdate(int, String)`
  are `@Deprecated`; the replacements are the `(int, String, String extraData)` variants.
  `messagedispatcher.ActionMessage.handleMessage` calls **the 3-arg variant, then the 2-arg one**,
  for every event, and all base implementations are empty. So override exactly one variant
  (the 3-arg one), never both, or you get double callbacks. `parseCommand` passes the
  listener through unchanged.
- **`removeLocation` is a NO-OP** (verified): it is `@Deprecated` and its body is `return 0`.
  It never contacts the service and never calls the listener. No verified replacement.
  Candidates: `editPlace(int, String operParam, CommandListener)` (cmd `cmd_navi_edit_place`,
  JSON schema unknown, not found in the jar), or `updatePlaceList(int, List<PlaceBean>, …)`
  (rewrites the whole list; semantics unverified). `OrionStarBridge.removePlace` currently
  returns `false` immediately. Needs a hardware test before using either candidate.
- `PlaceBean` (actionbean) has `getPlaceName/getPointX/Y/Theta/getPlaceId/getMapName`, and
  constructors `PlaceBean()` and `PlaceBean(JSONObject, String lang)`.

### Voice / people APIs in the jar (verified signatures 2026-09-15, behaviour UNVERIFIED)

Collected for the multi-speaker detection work. Only the signatures were read from the jar;
none of this has been run on hardware.
- `SkillApi`: `registerCallBack(SkillCallback)`, `setMultipleModeEnable(boolean)`,
  `setAngleCenterRange(float center, float range)` (param keys `angle_center`/`angle_range`),
  `setRecognizeModeNew(isContinue, isCloseStreamData)`, `setAsrExtendProperty(String)`,
  `setASREnabled`, `getTtsPlayStatus(): Int`.
- `SkillCallback` (abstract): `onSpeechParResult(String)` (partial ASR), `onQueryAsrResult(String)`,
  `onStart/onStop`, `onVolumeChange(int)`, `onVadMuteTime(int)`, `onSpeechStreamData(String)`,
  `onGetMultipleModeInfos(int): String`, `onError(String,int,String)`.
  Whether `onSpeechStreamData` carries audio or text is unknown.
- `RobotApi.wakeUp(int reqId, float angle, …)`: the wake-up carries a sound-source angle.
- `PersonApi.getInstance()`: `getAllPersons()`, `getAllFaceList()`, `getAllBodyList()`,
  `getFocusPerson()`, `registerPersonListener`, `getMultipleModeInfos(int)`.
  `listener.Person` has `getId/getAngle/getDistance/isWithFace`, **`getMouthMoveScore(): Double`,
  `getMouthState(): Int`** (camera-based "is this person talking").
- "Multiple mode" is shared by SkillApi and PersonApi. Its meaning is unknown and needs a hardware test.
- There is no raw-audio or multi-channel mic API in the jar. `RECORD_AUDIO` is not in the manifest.
  Whether `AudioRecord` can open while the OrionStar speech service holds the mic is **unknown**.

### TTS (verified in the jar 2026-09-15; `robocontrol/audio/OrionStarTts.kt` NOT yet run on hardware)

- `SkillApi.playText(TTSEntity, TextListener)`. `TTSEntity(text)` has a per-sentence
  `ttsParams: HashMap<String,String>`, so the language can be set per sentence without the
  global `setTTSParams(String, int)`, which changes robot-wide settings.
- `TTSParams` keys: `SpeechLanguage`, `SpeakerRole`, `SpeechSpeed` (0–9), `SpeechPit` (0–9),
  `SpeechVolume` (0–30), `SpeechRate` (sample-rate enum). `SpeakerRole` has only Chinese and English
  roles (`SPEAKER_NATIVE_ENG=31`, `SPEAKER_MAN_ENG=40`, …), none for German.
- `LangParamsEnum(codeName, codeValue)`: `EN_US("en_US", 2)`, **`DE_DE("de_DE", 7)`**, plus ~50
  others (EN_GB, FR_FR, …). **UNVERIFIED: whether `SpeechLanguage` wants the codeName ("de_DE",
  used now) or the codeValue ("7").** If German comes out in the wrong voice or language, try the
  codeValue first.
- **UNVERIFIED: whether a German voice is installed.** Check with `OrionStarTts.voicesFor(GERMAN)`,
  which wraps `getSpokemanListByLanguage("de_DE")` (return format undocumented).
- `TextListener` is a class: `onStart/onStop/onError/onComplete/onStreamComplete(String,String)`.
  `onError` carries no reason. Callbacks go through `messagedispatcher.TextDispatcher` onto an SDK
  handler thread, not the main thread.
- `playText` rejects text for which `isInvalidTtsText` → `textIsJson` is true, and calls
  `onError` immediately.
- Not verified: whether a second `playText` while speaking queues or interrupts.
- **Hardware test:** `voiceprobe/TtsProbe.kt` + `TtsProbeActivity` (compiled 2026-09-15, not yet
  run). Start with `adb shell am start -n com.example.roboguard/com.example.testing.voiceprobe.TtsProbeActivity`.
  Test 0 "Say sentences" is a plain smoke test (3 English + 3 German sentences via `speakAndWait`).
  Procedure: README.md, "Testing text-to-speech". It settles every UNVERIFIED point above: voices
  installed (test 1), codeName vs codeValue (tests 2 and 3), queue vs interrupt (test 6), play-status
  values while speaking (test 7). The tester's ✔/✘ verdicts land in the log file.
  The owner asked for on-robot tests, not JVM unit tests. There is still no test dependency in Gradle.
- **Results (robot ZTT18P1000A0, Android 9, 2026-09-16):**
  - Start: after adding Gson, TtsProbeActivity launches without crashing. `OrionStarTts connected after 969 ms`,
    `raw SkillApi connected after 970 ms` (SkillApi.connectApi works from our foreground activity).
  - Test 0 "Say sentences": **nothing spoken; no TextListener callback within 30 s** for sentence 1.
    Robot logcat shows the cause: CoreService `ModuleManager: mActiveAppModule : com.ainirobot.maptool` and
    `DaemonService: Check permission, top : com.example.roboguard  current : com.ainirobot.maptool`, every 2 s.
    **Being the top (foreground) activity is NOT enough: CoreService keeps an "active app module", and SDK
    commands from other apps are ignored silently** (no error, no callback). The map tool (still running, pid 14698)
    held it after the owner created the map "RoboGuard Lab-0916110443". Another third-party app,
    `com.example.PRIVATAR`, also runs on this robot. Open question: what makes an app the active module
    (closing the map tool? launching via RobotOS home / `action.orionstar.default.app`? `RobotApi.connectServer` +
    `setCallback`?). OrionStarTts/TtsProbe only use SkillApi and never call RobotApi.connectServer.
  - **ANSWERED (2026-09-16): an app only gets SDK control if it is LAUNCHED FROM THE ROBOTOS HOME LAUNCHER
    ("app center").** Evidence: after the map tool was stopped, `mActiveAppModule` stayed `null` for our TtsProbe
    (started via `adb am start`, even with RobotApi.connectServer + setCallback → immediate `onSuspend`), AND for
    OrionStar's own installed sample `com.ainirobot.robotos` started via `adb monkey`. The sample shows on screen:
    "Your sdk init failed Make sure launch this app from Home Launcher / SDK初始化失败了请确保从应用中心启动此程序".
    Its MainActivity polls `RobotApi.isApiConnectedService() && RobotApi.isActive()`.
    Consequences: **activities started with adb (all probe screens) cannot use the SDK.** SDK-free parts
    (Android AudioRecord, AudioManager, DevicePolicyManager) are unaffected. `RobotApi.isActive()` is the check
    to log/show.
    **How activation works (robot logcat, 2026-09-16 18:17):** tapping a launcher icon logs
    `ActivityManager: START u0 {cmp=com.example.roboguard/...VoiceProbeActivity} from uid 1000` (system/home), then
    `ModuleManager: Set active module` → `mActiveAppModule : com.example.roboguard` and
    `PermissionManager: On app change pre app : [com.example.PRIVATAR, com.ainirobot.maptool, com.example.roboguard] current : com.example.roboguard`.
    adb starts come from uid 2000 (shell) and are not activated. **Activation is per PACKAGE**: after the icon launch,
    other activities of com.example.roboguard (TtsProbeActivity) had `isActive() = true`. So once RoboGuard is started
    by RobotOS (launcher icon or boot/default app via `action.orionstar.default.app`), all robocontrol code in the
    package has SDK control while in the foreground. Production workaround: make RoboGuard the robot's default/boot
    app (settings, three-finger pull-down). Not yet verified that a boot launch activates the same way.
    Sample init order: in `Application.onCreate` do RobotApi.connectServer → setCallback + setResponseThread
    → then SkillApi.connectApi. Also in the jar: `RobotApi.registerModule(String, List<String>, ModuleCallbackApi)`,
    `unregisterModule` (semantics unknown).
  - **TTS results after launching from the home launcher icon (probe-20260916-121758.log):**
    `SDK control active: yes`. **English TTS WORKS**: "say sentences" finished sentences in 2.6–4.1 s; test 2 English
    started after 505 ms, total 4.8 s. **German with SpeechLanguage "de_DE" (codeName) finished** (started after 515 ms,
    total 5.6 s); whether it SOUNDED German is still unknown (no ✔/✘ verdict logged yet). `voicesFor(ENGLISH/GERMAN)`
    → `getSpokemanListByLanguage` returns **null** for both, so it is useless as an installed-voice check.
    `onSuspend`/`onRecovery` fire when another screen or app takes the foreground and when this one returns
    (seen at 42 s / 46 s / 71 s). Tests 3–7 not yet run.

### Sensor on/off switches (jar bytecode verified 2026-09-15; behaviour UNVERIFIED on hardware)

Undocumented publicly (docs search found none of these). The command each one sends was read from the bytecode:
- **LIDAR ("radar"):** `RobotApi.updateRadarStatus(reqId, open: Boolean, CommandListener)` → `cmd_navi_set_radar_status`
  with JSON `{"openRadar": bool}`. `queryRadarStatus(reqId, l)` → `cmd_navi_query_radar_status`.
  Status topic `Definition.STATUS_RADAR = "status_radar"`. Turning LIDAR off will break localization,
  navigation and obstacle avoidance, so the robot must stand still.
- **Vision (camera-based face/person detection in the head service):** `startVision(reqId, l)` / `stopVision(reqId, l)`
  → `cmd_head_start_vision` / `cmd_head_stop_vision`. Also `startBackupVision/stopBackupVision`.
  Unknown whether `stopVision` releases the camera or only stops the algorithms, and whether SurfaceShare still works afterwards.
- **Depth camera for navigation:** `setNavigationDepthImage(reqId, enable[, Definition.DEPTH_DEVICE])` →
  `cmd_navi_enable_depth_image`, `{"json_navi_enalbe_depth_image": bool, "json_navi_depth_device": int}` (the typo is in the SDK).
- **Microphone / speech:** `SkillApi.setASREnabled(bool)`, `setRecognizable(bool)`, `setRecognizeMode(bool)`,
  `cancleAudioOperation()`. These stop speech recognition, not necessarily the mic capture itself.
- **Status only:** `getSensorStatus` (`cmd_navi_get_sensor_status`), `getHeadCameraStatus`, `getDepthCameraStatus`,
  `getFovCameraStatus`, status `status_navi_sensor_exception`.
- `Definition.ROBOT_SETTING_BAR_CAMERA` / `ROBOT_SETTING_BAR_MICRO` exist (settings keys, probably status-bar
  camera/mic toggles), but no class in the jar uses them. They may be read by RobotOS itself via
  `RobotSettingApi.getRobotInt/setRobotInt`. Meaning unverified. `CMD_NAVI_SET_CAMERA_STATE` is defined but unused in the jar.
- Plain-Android fallbacks (unverified on the robot, depend on its Android version):
  mic → `AudioManager.setMicrophoneMute(true)` (MODIFY_AUDIO_SETTINGS); camera →
  `DevicePolicyManager.setCameraDisabled` (needs device admin/owner, e.g. `adb shell dpm set-device-owner`);
  `SensorPrivacyManager` toggles are Android 12+ and system-only. None stop RobotOS if it bypasses the framework.

### Map creation EXISTS in the jar (undocumented publicly)

```
startCreatingMap(int, StartCreateMapBean, CommandListener)
stopCreatingMap(int, StopCreateMapBean, CommandListener)
cancelCreateMap(int, CommandListener)
saveMappingPose(int, Pose, CommandListener)
deleteMappingPose / renameMappingPose(int, String, …, CommandListener)
setMappingPlace(int, String, int, String, String, String, CommandListener)
getMappingInfo(int, String, CommandListener)
getCreateMapType(): String
```
Also `Definition$CreateMapType`, `Definition$MAPFINISHSTATE`.

The public docs claim the built-in "map tool" does all map operations. That is
wrong — mapping is programmatic. **Autonomous exploration still appears absent**;
these beans have not been inspected yet.

### Constraints confirmed from the docs (not contradicted by the jar)

- **SDK authorization follows the foreground app.** *"When the app interface returns
  to the background, the app is immediately suspended."* A headless service cannot
  hold the Robot API. This is why robot control belongs inside RoboGuard rather than
  in a second APK, and why a popup from another app would suspend navigation.
- **No virtual-wall / forbidden-area API.** Forbidden areas are authored in the map
  tool; the SDK only lets the app *observe* `Pose.status == 2`. Privacy zones must be
  enforced in the app. The one lever on map geometry is
  `ShareMemoryApi.getMapPgmPFD` / `setMapPgmPFD` (raw occupancy grid) — risky,
  undocumented, and it can degrade localization, so it is not the primary mechanism.
- **No on-device power API on the Mini.** `robotStandby` is Lucki-only; sleep/wake/
  reboot/power-off exist only as cloud REST commands. "Turn the robot off" should be
  implemented as stop motion + close sensor gates + say so on screen.
- Speech is on `SkillApi`: `playText(String, TextListener)` (max 1000 chars),
  `stopTTS()`, `setRecognizable(boolean)`, `setRecognizeMode(boolean)`.
- **Runtime dependency Gson (VERIFIED on the robot 2026-09-16):** robotservice_12.3.jar uses `com.google.gson`
  (50 classes referenced, not bundled). Without it, `SkillApi()` crashes with `NoClassDefFoundError:
  com/google/gson/GsonBuilder` (first TtsProbeActivity launch). jdeps shows Gson is the only missing external library.
  OrionStar's RobotSample declares `api 'com.google.code.gson:gson:2.7'`; RoboGuard now has
  `implementation("com.google.code.gson:gson:2.11.0")` in app/build.gradle.kts.
- Required manifest permissions: `INTERNET`,
  `com.ainirobot.coreservice.robotSettingProvider` (easy to miss — without it the
  connect callback never fires), `READ/WRITE_EXTERNAL_STORAGE`.
- Init order: `connectServer` → `handleApiConnected` → `setCallback` →
  `registerStatusListener`. Navigation fails `ERROR_NOT_ESTIMATE` unless localized.

## Current state / known issues

1. **`OrionStarBridge.kt` compile errors: FIXED 2026-09-14** (both fixes applied in source;
   IDE diagnostics re-checked, and `:app:compileDebugKotlin` succeeds). The original two fixes were:
   - import `com.ainirobot.coreservice.client.StatusListener` (not `.listener.StatusListener`)
   - `disconnectApi()` instead of `disconnectServer()`
   **Re-verified 2026-09-14** against the IDE diagnostics and `javap`. These two causes
   account for all 4 IDE errors: the wrong import (line 9) cascades into the "Unresolved
   reference" and "Argument type mismatch" errors at line 57. `StatusListener` has a no-arg
   constructor and `onStatusUpdate(String, String)`, so the existing override is correct
   once the import is fixed.
   `javap` is not on PATH; use `/snap/android-studio/current/jbr/bin/javap`.
   Then worth switching to the synchronous getters and using `Pose(float,float,float)`
   to finish `navigateTo(Point2D)`, which currently just returns a failure.
2. **No tests in the repo.** ~35 JVM tests exist for the zone/guard logic but were
   not added, because `app/build.gradle.kts` has no test dependencies and the owner
   asked for no changes. Adding them needs `testImplementation("junit:junit:4.13.2")`.
3. **Nothing wires robocontrol into RoboGuard.** By design, for now.
   **Update 2026-09-15:** the owner started it: `robocontrol/sensorcontrol/sensors.kt` imports
   `com.example.roboguard.*` to read the sensor settings. Owner-added `RobotServerService.getSensors()`
   (returns `getCurrentSettings().sensors`) is an **instance member** of the Service, so it cannot be
   called as a plain function (compile error "Unresolved reference 'getSensors'"). Top-level
   `internal fun getSettingsFile(context)` in RobotServerService.kt IS reachable from robocontrol.
   `sensors.kt` imports fixed (Context, Json, AppSettings, getSettingsFile); it compiles.
   **Defaults are never written to disk (verified in RobotServerService.kt, 2026-09-15):** `onCreate`
   writes no settings or capabilities file. `privacy_settings.json` is written only by the phone's
   `POST /save`, and `capabilities.json` only by the localhost-only `POST /update_capabilities`.
   Defaults exist only in memory: `getCurrentSettings()` returns `getDefaultSettings()` (Camera/LIDAR/Microphone
   all true) when the file is missing, and `GET /capabilities` responds with default `RobotCapabilities`
   without saving them. So on a fresh install, before the phone has saved once, `Sensors.getSensors()`
   returns `emptyMap()`, NOT the defaults.
   Also: `Sensors.sensors` is read once at construction and goes stale after a later `/save`.
   **IMPLEMENTED 2026-09-15 (owner's requirement: a settings update from the server must update Sensors):**
   `Sensors` is a per-process singleton (`Sensors.get(context)`). Its initial state comes from
   privacy_settings.json, or `DEFAULT_SENSORS` (Camera/LIDAR/Microphone = true) if missing or unreadable,
   so the empty map is gone. It offers `states: StateFlow`, `getSensors()` (always current), `isEnabled(name): Boolean?`,
   `update(Map)` / `update(AppSettings)` / `reload()`, and `addListener(SensorChangeListener, notifyCurrent)`.
   Listeners are called once per CHANGED sensor, on the caller's thread, with exceptions caught per listener.
   **RobotServerService /save hook APPLIED by Claude at the owner's request (2026-09-16, compiled + installed, not yet run):**
   after writing privacy_settings.json and before the notification/popup: `applySensorSettings(settings)` →
   `Sensors.get(applicationContext).update(settings)` (GENERAL sensors only; rooms ignored for now; exceptions caught → false),
   then German TTS via a lazily created `OrionStarTts` in the service: success "Einstellungen wurden aktualisiert"; sensor
   switching threw → "Einstellungen wurden gespeichert, aber die Sensoren konnten nicht umgeschaltet werden"; parse/write
   failure (catch block, still HTTP 500) → owner's wording "Einstellungen konnten nicht gespeichert werden, versuchen Sie es
   bitte erneut". `speakGerman` never throws: connects on demand, keeps only the newest pending sentence, logs "ServerTts"
   (not connected after 5 s, onFailed). Speech only works while RoboGuard is the active foreground app. LIDAR=false in a save
   stops any running navigation (SensorSwitches).
   **First real /save from the phone (robot logcat 22:57:10, VERIFIED):** payload sensors {Camera=false, Microphone=true, LIDAR=true};
   ServerTts "Speaking: Einstellungen wurden aktualisiert". Camera OFF: setCameraDisabled CONFIRMED and independently
   `dumpsys device_policy` → RoboGuardDeviceAdmin `disableCamera=true`; stopVision result=1 but message "timeout" (treated as
   CONFIRMED by SensorSwitches, questionable). Mic ON: setMicrophoneMute(false) CONFIRMED, SDK ASR re-enable FAILED
   (isRecognizable=false, the known one-way issue). LIDAR ON: updateRadarStatus CONFIRMED (succeed). Switches ran twice
   (Sensors.init on first get() + update), harmless. Android 9 `dumpsys audio` has no global mic-mute line; stream "Muted"
   entries are not the mic.
   **"Robot stopped talking" (robot logcat 23:00–23:01, VERIFIED cause):** 23:00:16 RG Sensor Test launched from the home icon
   (uid 1000 → active), 23:00:46 Home pressed → RobotOS suspends RoboGuard (`mActiveAppModule : null`). Later saves started
   PopupActivity from the service (uid 10103, FLAG_ACTIVITY_NEW_TASK), which brought RoboGuard's MainActivity back to the front
   WITHOUT re-activation (PermissionManager "current : null"). Result: SkillServer "fail to play text … because of the skillType is
   SUSPEND" (ServerTts still logged "Speaking", since playText returned without error), and RobotApi calls rejected with code -7
   (startVision, updateRadarStatus). **Being on screen is not enough; only a launch from the home launcher re-activates.** The -7
   rejections mean Camera ON (SDK part) and LIDAR ON of those saves did not reach RobotOS.
   **How RobotOS grants control (CoreService.apk from /system/priv-app/CoreService, dexdump, 2026-09-16; code read, NOT tested):**
   `core.permission.PermissionManager` watches foreground changes. On "app came to foreground" (PermissionHandler
   onForegroundActivitiesChanged, and onAppChange): (1) if the package is in `mWhiteList` → log "Start white list app" →
   `SystemServer.startAppControl(pkg)` = SDK control, however the app got to the front (recents, service startActivity, popup);
   (2) else if NOT launched from recents (`SystemUtils.isLaunchRecent`) and the package is in `mPreActiveApp` (apps active before)
   → "Recovery pre app"; (3) else "Top activity no control" / "Stop app control". `mWhiteList` = resource array/whiteList
   [com.ainirobot.videocall, .settings, .moduleapp, .maptool, .inspection] PLUS the RobotSetting **`boot_app_package_name`**
   (`initWhiteList`; `listenRobotSettingDefaultPackage` re-reads it on change, so no reboot needed). SDK:
   `RobotSettingApi.getRobotString/setRobotString(Definition.BOOT_APP_PACKAGE_NAME = "boot_app_package_name", …)`; provider
   `com.ainirobot.coreservice.robotsettingprovider` needs permission robotSettingProvider (RoboGuard has it; adb shell does not).
   **So: making RoboGuard the robot's default/boot app should give it control whenever it is in the foreground.** Not changed yet
   (owner: research only).
   **Option B implemented (owner: "Lets try B", 2026-09-16, compiled + installed, not yet run):** `robocontrol/system/DefaultAppSetting.kt`:
   `ensureRoboGuardIsDefault(context)` connects RobotApi if needed (RobotSettingApi is served through that binder), reads
   `boot_app_package_name` (constant is `Definition.BOOT_APP_PACKAGE_NAME`, NOT on RobotSettingApi), writes the package only if
   different, reads back (setRobotString returns nothing and swallows RemoteException), logs tag `DefaultApp`. The value before the
   first change is stored in SharedPreferences `robocontrol_default_app` → `restorePrevious(context)` undoes it (no UI calls it yet).
   Called from `MainActivity.onCreate`. Unverified: whether CoreService accepts the write from a third-party app, and whether the
   white list then really activates RoboGuard after Home → recents/popup.
   **First run (robot logcat 23:08, VERIFIED):** the WRITE WORKS: `boot_app_package_name` was "com.example.PRIVATAR" →
   `Changed(from=com.example.PRIVATAR, to=com.example.roboguard)`; OrionHome's SettingsObserver logged "switch default app:
   com.example.roboguard". But activation did NOT change yet: after Home + popup from /save, PermissionManager still logged
   "On app change pre app : [] current : null" → "Top activity no control" (isLaunchRecent false), i.e. CoreService's in-memory
   mWhiteList does not contain RoboGuard; TTS still SUSPEND. No "add packageName … into whiteList" line (CoreService logs at that
   second were dropped by chatty, so unknown whether its setting listener fired). Next step: reboot, since initWhiteList runs at
   CoreService start and reads boot_app_package_name.
   **After reboot (23:12–23:13, VERIFIED):** booted in ~45 s; RobotSettingManager "boot_app_package_name: get from database
   com.example.roboguard"; RobotOS auto-started RoboGuard (MainActivity resumed) and `mActiveAppModule : com.example.roboguard`
   (SDK control without tapping the icon). PermissionManager alternated "Recovery pre app : com.example.roboguard" and "Top
   activity no control" during start-up. Still no "into whiteList"/"Start white list app" line; the decisive test (Home, then back
   via recents/popup) is not done yet.
   **Owner theory "TTS runs before RoboGuard is brought to the front" CHECKED (logcat 23:14, VERIFIED):** 23:14:41 save while in
   front → played, action=complete. 23:14:46 Home → RobotOS `stopTTS()`, RoboGuard suspended. 23:14:49.369 save: sensor SDK calls
   (setRecognizeMode/setASREnabled/setRecognizable) and at .373 playText ALL rejected "because of the skillType is SUSPEND" (rejected
   immediately, not cancelled mid-sentence); .382 PopupActivity started (order in /save: sensors → TTS → popup); CoreService then
   re-activated RoboGuard via "Recovery pre app : com.example.roboguard" (not via white list) and ~0.7 s after the switch called
   `stopTTS()` again (RobotOS does this on every app change). 23:14:53 next save while active → played complete. So: theory right
   in effect (order), mechanism = suspend rejection; SDK sensor switches of the first save after Home fail the same way.
   Proposed fix (not applied, owner only asked to check): in /save bring RoboGuard to the front FIRST, wait until
   `RobotApi.isActive()` is true plus ~1 s (past RobotOS's stopTTS on app change), then apply sensors and speak.
   **Owner then moved showPopup before speakGerman (sensors still first) — still no TTS on the first save from Home
   (logcat 23:17:33, VERIFIED):** .521 sensor SDK calls rejected SUSPEND; .535 PopupActivity START; .637 playText rejected SUSPEND
   (only ~100 ms after the start request); 23:17:34.071 RobotOS `stopTTS()` after re-activating RoboGuard. Second save 23:17:40
   (already active) → played complete. Cause: `startActivity` only queues the start; re-activation ("Recovery pre app") arrives
   ~0.3–0.5 s later, and RobotOS then calls stopTTS on the app change. Reordering calls cannot fix it; /save must wait for
   `RobotApi.isActive()` (poll) + a short settle delay (~1 s) before sensors and speech.
   **Implemented (owner: "yes", compiled + installed, not yet run):** /save = decode → write file → notification + showPopup →
   `applySettingsWhenInControl(settings)` → respond "OK" immediately. The job (service `CoroutineScope(SupervisorJob()+Default)`,
   cancelled in onDestroy; a newer save cancels the pending one) runs `waitForSdkControl()`: `Sensors.get()` (starts RobotApi
   connection), poll `RobotApi.isActive()` (live binder call to IModuleRegistry.isActive) every 100 ms up to 3 s; if control had to
   be regained, wait 1 s more; then `applySensorSettings` + speakGerman. Timeout → logged "No SDK control after 3000 ms", still
   applies (Android parts work). Error path: popup "Privacy Settings could not be saved" + `speakWhenInControl(error sentence)`.
   Also seen in the jar, unexplored: `RobotApi.delegateControl(String): Boolean`.
   **BUG after reboot: private areas ignored while driving (owner report 2026-09-17; logcat + screenshot VERIFIED, cause partly inferred):**
   RoboGuard auto-started (default app), CoreService `mActiveAppModule : com.example.roboguard`, Navigation screen loaded map +
   2 areas ("Privat: Bereich 1", "Schreibtisch"), drives Home/Empfangsstelle/Home all Started → ARRIVED, but every drive logged
   "warning: no SDK control" and the map showed NO robot marker (screencap). So in MapNavigation.pollLoop `getCurrentPose()` /
   `isRobotEstimate()` / `isActive()` never succeeded (no "localized:" line, 0 `ModuleServer[com.example.roboguard] getRobotInfo
   reqType:112` lines) → `controller.onPose` never ran → no in-motion check; the pre-move check only tests targets (outside the
   areas), so the drives went through. `startNavigation` still worked (different binder path). These three calls all go through
   `RobotApi.mModuleRegistry` (set in the ServiceConnection's onServiceConnected, set to NULL in onServiceDisconnected).
   Inferred cause: TWO `connectServer` calls in the process (DefaultAppSetting from MainActivity.onCreate 16:43:20, then
   OrionStarBridge.connect 16:43:37; bridge "connected" only at ~16:43:51), so an old connection's disconnect likely nulled
   mModuleRegistry. Also a design flaw: no pose = silently no enforcement (fail OPEN). The robot itself WAS localized (RobotOS
   refuses navigation otherwise with ERROR_NOT_ESTIMATE); only the app's queries failed.
   **Fix (owner: "Sounds good", compiled + installed, not yet run):** (1) `robocontrol/system/RobotApiConnection.kt`: the ONE
   `connectServer` per process (IDLE/CONNECTING/CONNECTED, listener fan-out, adopts an existing connection via
   isApiConnectedService, never disconnects). OrionStarBridge (listener + tracked pose StatusListener, disconnect() no longer calls
   `RobotApi.disconnectApi()`), SensorSwitches, DefaultAppSetting, SoundDirectionProbe and TtsProbe now go through it (probes no longer
   disconnectApi RobotApi). (2) MapNavigation fail closed: `lastPoseAt` set on every successful getCurrentPose; while driving, no
   position for > `POSE_TIMEOUT_MS` = 1000 → `stop("robot position unknown")` + German "Ich kann meine Position gerade nicht
   bestimmen und halte deshalb an." (Claude's wording, not the owner's); driveTo refuses to start without a fresh position (same
   sentence). MovementProbe has no such check. Previous note, kept for history: In `/save`,
   store the parsed settings (`val settings = jsonConfig.decodeFromString<AppSettings>(payload)`) and after
   `writeText(payload)` call `Sensors.get(applicationContext).update(settings)`, plus
   `import com.example.robocontrol.sensorcontrol.Sensors`. Until then nothing calls `update()` automatically.
   **Hardware switching IMPLEMENTED 2026-09-15 (owner's spec; compiled, NOT run on the robot):**
   `sensorcontrol/SensorSwitches.kt` (internal), called from `Sensors.update()` for EVERY sensor on each update
   (idempotent) and once in `Sensors.init`. false = off, true = back on. Names are matched case-insensitively:
   - LIDAR → SDK only: `stopNavigation` first, then `updateRadarStatus(reqId, enabled, CommandListener)`.
   - Microphone → SDK `SkillApi.setASREnabled` + `setRecognizable` (read-back: `isRecognizable`) AND Android
     `AudioManager.setMicrophoneMute` (read-back: `isMicrophoneMute`; permission MODIFY_AUDIO_SETTINGS added).
   - Camera → SDK `stopVision` / `startVision` AND Android `DevicePolicyManager.setCameraDisabled` (read-back:
     `getCameraDisabled(null)`). Needs the device admin `RoboGuardDeviceAdmin` (receiver in the manifest +
     `res/xml/robocontrol_device_admin.xml`, policy disable-camera) activated once:
     `adb shell dpm set-active-admin com.example.roboguard/com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin`.
     Plain device admins may use this policy only because targetSdk = 28.
   - If the SDK is not connected, `SensorSwitches` connects (RobotApi only if `isApiConnectedService()` is false)
     and re-applies the desired state on connect. Reports: `Sensors.switchReports` (CONFIRMED/SENT/FAILED/PENDING/UNSUPPORTED)
     plus Logcat tag `SensorSwitches`.
   - Open questions for the robot: does radar off really stop the LIDAR; does stopVision release the camera;
     does mic mute affect RobotOS's speech service; does setCameraDisabled affect RobotOS vision; what happens
     with a second connectServer when another component (OrionStarBridge, probes) connects too.
   `DEFAULT_SENSORS` duplicates `getDefaultSettings()`; keep them in sync.
   **Hardware test (compiled 2026-09-15, not yet run):** `testing/sensorprobe/SensorProbe.kt` + `SensorProbeActivity`
   (`adb shell am start -n com.example.roboguard/com.example.testing.sensorprobe.SensorProbeActivity`).
   Procedure: README.md, "Testing sensor switching". Each test goes through `Sensors.update` (in memory only, the
   file is untouched), waits 3 s, logs `switchReports`, then read-backs (queryRadarStatus / getSensorStatus /
   getHeadCameraStatus / isMicrophoneMute / getCameraDisabled / admin active) and effect checks (1 s mic RMS <
   -80 dBFS = silent; CameraSnapshot fails or mean Y < 8 = blocked; NotConnected = inconclusive; LIDAR by eye).
   PASS/FAIL plus ✔/✘ verdicts go to the ProbeLog file (files/voiceprobe/). onDestroy restores the saved settings.
   **Results (robot ZTT18P1000A0, launched from the home icon, probe-20260916-121844.log):**
   - UI bug: the row layout overflowed on the robot's display (buttons render very large; "5 Camera OFF" squashed,
     LIDAR row, verdict buttons and log off-screen). Fixed with a scrollable button column on the left and the log on the right.
   - **Microphone ON (read from the full log): run 1 PASS, test recording -43.8 dBFS = real signal**, so an ordinary app
     CAN record while RobotOS's speech service runs (an earlier note claiming otherwise was wrong). Run 2 was -91.7 dBFS
     (silent), most likely nobody talked, so that is inconclusive rather than a failure.
   - **Re-enabling speech recognition is NOT confirmed:** after Microphone ON, `setASREnabled(true)` + `setRecognizable(true)`
     left `isRecognizable=false` (report SENT). Either the read-back is too early or re-enabling is ignored, which would
     leave RobotOS speech recognition off until a reboot. SensorSwitches now re-reads it after 2 s and reports FAILED
     if it is still false.
   - The owner's point: a silent Android recording while OFF only proves `setMicrophoneMute` blocks ordinary apps, NOT
     that RobotOS's speech service is deaf. SensorProbe now checks both layers: Android (2 s recording; silent while ON =
     INCONCLUSIVE) and RobotOS speech (6 s counting SkillCallback events: onStart/partial/final results and volume>0,
     counts only, no text).
   - **Owner request (2026-09-16):** the sensor test PLAYS BACK each 2 s test recording through the speaker right after
     measuring (AudioTrack, 16 kHz, in memory only, zeroed after playback, never written), and SHOWS each camera
     snapshot on screen (`SensorProbe.lastImage`, not saved), so results can be judged by ear and eye. This is test
     tooling only; production code keeps the no-audio/no-image-retention rules.
   - **Microphone OFF: both switches CONFIRMED** (`setASREnabled/setRecognizable` → isRecognizable=false;
     `setMicrophoneMute` → isMicrophoneMute=true), recording -Infinity dBFS, PASS. Wake-word verdict not yet logged.
   - **Camera ON: `startVision` CONFIRMED** (result=1, message `{"status":0}`), `setCameraDisabled(false)` CONFIRMED,
     **device admin already active: true** (not set up by Claude). **CameraSnapshot / SurfaceShare WORKS:**
     snapshot after 1076 ms, mean brightness 48/255, PASS.
   - **Camera OFF (probe-20260916-122928.log): both switches CONFIRMED** (`stopVision` result=1 `{"status":0}`;
     `setCameraDisabled(true)` → getCameraDisabled=true). **CameraSnapshot then fails immediately with SurfaceShare error
     -15** (ERROR_SET_STREAM_SURFACE_FAILED) after 40 ms. PASS, tester verdict ✔. Note: the device-policy camera disable
     PERSISTS after our app closes, until the app runs again (Sensors.init applies the saved settings) or is changed.
   - **Speech recognition cannot be re-enabled through the SDK:** since the first Microphone OFF (~12:18), every
     Microphone ON reports `isRecognizable=false after 2000 ms` → FAILED, and the speech-layer check counted 0 speech-service
     callbacks while the tester talked (runs at 12:27–12:30). Wake-word behaviour to be confirmed by the tester; a robot
     reboot is expected to restore it. **Consequence for the product: switching RobotOS speech off via
     setASREnabled/setRecognizable(false) may be one-way until reboot; do not use it casually.**
   - **Wake word (robot logcat, SkillManager in com.ainirobot.speechasrservice, 2026-09-16):** no user-set word
     (`initCustomizedWakeupWord userSetWord:null`); the oversea preset is `mPresetDefaultWakeUpWords :oo:ou k:ei l:a k:ei`
     (phonetic: O-KAY LA-KAY). Most likely "OK Lucki" / "Okay Lucky"; not confirmed by ear. SDK: `SkillApi.queryUserSetWakeUpWord()`,
     `setCustomizeWakeUpWord(...)`, `closeCustomizeWakeUpWord()`.
   - Microphone ON test recording was -103 dBFS in 122928 (silent playback): the reason the tester heard nothing.
     The sensor test now records with CAMCORDER (see Mic access results).
   - **With CAMCORDER recording (probe-20260916-123336.log):** Microphone ON tester verdict ✔ (heard own voice in playback).
     **Microphone OFF: both layers PASS**: Android recording -Infinity dBFS, speech service 0 callbacks in 6 s while the
     tester talked, verdict ✔. **Restore saved settings:** Camera ON (startVision + setCameraDisabled(false)) CONFIRMED,
     LIDAR ON CONFIRMED, mic unmute CONFIRMED, but SDK speech re-enable still FAILED (isRecognizable=false).
   - LIDAR OFF/ON: skipped by the owner for now ("seems to be working").
   **Ways to reach RobotServerService members from robocontrol:** (a) bind: `LocalBinder.getService()`
   already exists, and MainActivity.kt:52–69 does `startForegroundService` + `bindService(BIND_AUTO_CREATE)` and
   gets the instance in `onServiceConnected` (no RoboGuard change needed); (b) a companion-object instance
   reference set in onCreate and cleared in onDestroy (RoboGuard change); (c) move settings logic to
   top-level `fun …(context)` functions (RoboGuard change, cleanest).
   Casing mismatch: defaults use situational key `"pixelate objects"`, capabilities use `"Pixelate Objects"`.
   The actual default sensors are only Camera/LIDAR/Microphone (the DTO note above also lists Ultrasonic/Collision).
4. The map must still be built and zones defined before anything is enforced.
   **Map app (from the jar, 2026-09-16):** RobotOS's "map tool" is `Definition.MAPTOOL_PACKAGE_NAME = "com.ainirobot.maptool"`,
   launcher `MAPTOOL_PACKAGE_NAME_LAUNCHER_CLASS = "com.ainirobot.maptool.activity.GuideInitActivity"`, so
   `adb shell am start -n com.ainirobot.maptool/.activity.GuideInitActivity` (not tried; no robot connected).
   The first-setup app `com.ainirobot.firstconfig` also has `first_config_action_start_create_map`.
   The docs (https://doc.orionstar.com/en/knowledge-base/map-and-position/) only say the map tool does
   "all map and point operations"; there are no UI steps. Places belong to a map, and switching maps requires relocalization.
   **No probe needs a map:** voice, TTS and sensor probes run without one. A map matters for movement/zones, and for
   relocalizing after the sensor probe's LIDAR ON test.
   **Map files on the robot (VERIFIED 2026-09-16, robot ZTT18P1000A0):** `/sdcard/robot/map/<mapName>/` (e.g.
   "RoboGuard Lab-0916110443"; other maps: ForumWissen, office, BBS1_Arnoldi, CSP_RoboLab) contains `mapinfo.json`
   (mapName, mapId, createTime, `forbidLine:1` = has no-go lines, mapLanguage de_DE, …), `mapConfig.json`,
   `mapping_track.json`, `place.json` (saved places with x/y/theta and multilingual names, e.g. Charging Point (0.44, -0.15)),
   `place.properties`, and `navi_data/` with `map.pgm`, `probabilitymap.data`, `probpyramids.data`, `vision_map*.data`,
   `config.json` (device params, no georeference). No yaml; resolution/origin are not in any JSON.
   - **`navi_data/probabilitymap.data` format (decoded):** int32 LE length, then protobuf: f1 double resolution
     (0.05), f2 varint width (660), f3 varint height (600), f4 double origin x (-16.0), f5 double origin y (-15.0),
     f6 bytes = width×height uint16 LE cells, **row 0 = lowest world y**. Values: 0 unknown, 1 free, up to 32767 occupied.
     Parsed by `movement/RobotMapFile.kt`. Rendered correctly by eye (same shape as map.pgm, flipped vertically).
   - **`navi_data/map.pgm`** (P5, 320×260 for RoboGuard Lab) is the map tool's display map: 150 unknown, 255 free,
     **5 = walls/obstacles, 0 = NO-GO LINES drawn in the map tool** (straight blue lines when colour-coded), 68/153 rare.
     Its resolution/origin are NOT known: bbox fitting against the probability map gave inconsistent x/y scales
     (0.064 vs 0.079 m/px) and only 35 % wall overlap, so the PGM may be cropped/rotated differently. Unresolved.
   - **map.pgm georeference SOLVED (2026-09-16, from the map tool's own code):** the file is `P5\n<w> <h>\n255\n` + w×h
     pixels + **16 trailing bytes: double LE resolution (0.05), float LE origin x (-8.0), float LE origin y (-6.0)**
     (MapUtils.loadMap reads `bytes2Double(extra,0)`, `byte2float(extra,8)`, `byte2float(extra,12)`). Conversion
     (MapUtils.pose2PixelByRoverMap): `px = (x - originX)/res`, `py = height - (y - originY)/res`. VERIFIED by overlay:
     probability-map walls land exactly on PGM walls, and saved places land on free pixels (Empfangsstelle → px 214/157,
     Ladestapel 156/139, Aufladepunkt 168/142).
   - **How no-go lines are created (map tool com.ainirobot.maptool, /system/priv-app/MapTool/MapTool.apk, disassembled):**
     `MapReqProcessor.handleSaveMap(mapName)` →
     (1) `SettingUtils.setForbidLineFlag` → **`RobotApi.setMapForbidLineFlag(reqId, mapName, 1|0, listener)`**;
     (2) `MapView.getEditedBitmap()` → **`MapUtils.saveRoverMapToPgm(map, mapName)` writes `<map root>/navi_data/map.pgm`
     directly** (P5 header via "P5\n%d %d\n255\n", pixels, extra bytes); no-go lines are pixel value 0;
     (3) `SettingUtils.setTypeLocalMapVersion` → **`RobotApi.setMapUpdateTime(reqId, mapName, now, listener)`**.
     `ShareMemoryApi.set/getMapPgmPFD` is only used by the map tool's TestActivity. So RoboGuard CAN create no-go lines
     with the same public SDK calls + a file write (the app has WRITE_EXTERNAL_STORAGE, targetSdk 28). NOT YET TRIED; unknown
     whether navigation reloads immediately or needs switchMap/relocalization. Back up the map folder (adb pull) first.
   - **PC backup of "RoboGuard Lab-0916110443" (2026-09-16 13:49, md5-verified for map.pgm/probabilitymap.data/mapinfo.json):**
     `~/RoboGuard_map_backups/RoboGuard Lab-0916110443_20260916-134911/` (all 15 files). Original map.pgm md5
     `56a2986a38166c7997444bf48bed053f`.
   - **Implemented (owner approved 2026-09-16, compiled, not yet run):** `movement/PgmMap.kt` (parse/serialise map.pgm exactly,
     world↔pixel, `lineAcross(a, b)` = perpendicular through the midpoint, extended until the first non-free pixel + 0.10 m on
     both sides, `withLine` Bresenham with a square brush, value 0) and `movement/NoGoLineWriter.kt` (backs up the ORIGINAL
     map.pgm + original forbidLine flag once into `files/robocontrol/map_backups/<map>/`, writes via temp file + rename, then
     `setMapForbidLineFlag(…,1)` + `setMapUpdateTime(…, now s)`, logs the SDK answers; `restore()` puts the original back and
     re-sends the original flag). Movement test: section "No-go line test": A/B = selected, preview (red), write/restore with
     confirmation dialogs; existing no-go pixels drawn blue; requests READ+WRITE storage. Default line 2 px = 10 cm.
     README: "No-go line test" incl. PC restore via adb push.
   - **RESULT (owner + probe-20260916-135310.log): programmatic no-go lines DO NOT WORK.** Writing map.pgm (135 new
     value-0 pixels) + `setMapForbidLineFlag(1)` + `setMapUpdateTime(now)` all answered `succeed`, the new line appeared in
     map.pgm, but the robot drove straight through to Empfangsstelle (ARRIVED). Lines drawn **by hand in the map tool** DO
     work: with one in place, `startNavigation` reported status 1025 "The global is path search failed" and then
     `Sdk(code=3)`. So the map tool must also update other navigation data (e.g. probabilitymap/probpyramids or a
     navigation-service reload) that our file write does not. Not pursued further (owner: "which is fine"). The robot's
     map.pgm is back to the original md5 `56a2986a…`; the app's backup copy of the original remains in
     `files/robocontrol/map_backups/RoboGuard_Lab-0916110443/`.
   - **Privacy areas enforced by RoboGuard (owner request 2026-09-16, compiled, not yet run):** the movement test drives
     ONLY through `NavigationController(bridge, PrivacyGuard)` (saved places now by coordinates too). "Private area around
     selected" creates `Zone("Privat: <point>", 24-gon radius 1.0 m, PRIVATE)`; `PrivacyGuard.margin` 0.5 m on top.
     Pre-move: target in zone → `BlockedByPrivacy`. In-motion: the 0.5 s pose poll calls `controller.onPose(pose)` →
     stop + `AbortedOnPrivateZoneEntry`. Both → `OrionStarTts.speakGerman("Weg führt durch privaten Bereich. Ich halte an
     und fahre nicht weiter.")`, first outcome wins (SDK's later Sdk(3) ignored), no automatic resume. Zones are in memory,
     reset on map change; drawn red with the margin ring. Controller calls are synchronized (poll thread vs UI).
     **Stop at the zone edge WORKS on the robot (owner, 2026-09-16), but the robot was then TRAPPED:** after stopping it stands
     inside the margin, and the strict in-motion check aborted every following drive at once, even turning away. Fixes
     (compiled, not yet run): (1) `NavigationController` exit rule: a drive that starts inside a zone (incl. margin) is only
     aborted if the clearance to that zone falls below the best clearance reached during the drive minus
     `EXIT_TOLERANCE_M` = 0.05 m; zones left drop out, re-entry is a normal violation; FORBIDDEN (SDK) stays a hard stop.
     (2) After an in-motion privacy abort the robot TURNS ON THE SPOT (owner's decision, replaced a first 0.3 m goBackward
     back-off because backward motion has no obstacle avoidance: `goBackward` has no `avoid` flag, unlike `goForward`).
     `EscapeHeading.choose` (movement/, pure): candidates every 5°; line of sight marched in 5 cm steps against the zone
     polygons WITHOUT margin (zones the robot is inside don't block); directions clear for ≥ 1 m → pick the one whose
     point 1 m ahead is furthest from all private zones; none clear → longest free distance. `RobotBridge.turnInPlace(rad)`
     → `RobotApi.turnLeft/turnRight(reqId, speed, angle, CommandListener)`: **both args in DEGREES** (bytecode:
     `motionAngle` applies `Math.toRadians` to both, then sends "turn_left"/"turn_right" via motionLine with the angle in the
     "distance" field); `turnBack` = 180°. 30°/s, 0.8 s after the stop, skipped below 10°. UNVERIFIED: turnLeft = +theta
     (counter-clockwise) and pose theta in radians; the log prints heading before/after to check.
     Other obstacle APIs in the jar (not used yet): `checkIfHasObstacle(reqId, d, d, d, l)`, `hasObstacleInArea(reqId, d, d, d, d, l)`,
     `motionArcWithObstacles`, `setObstaclesSafeDistance`, status `status_obstacle_info`.
     `OrionStarBridge.stopNavigation` now also calls `stopMove` so STOP ends the turn. (3) Pose poll 150 ms while navigating.
     **Run probe-20260916-143236.log (VERIFIED):** drawn 4-corner area, drive to Empfangsstelle stopped in front of it, and the
     following drive to Home from INSIDE the margin ARRIVED, so **the exit rule works**. But no German sentence and no turn:
     the log shows `FAILED to reach Empfangsstelle: Sdk(code=3)`. Cause: after `controller.onPose` called `stopNavigation`,
     RobotOS's own result (status 3 = stopped) came in on the SDK callback thread BEFORE the controller delivered
     `AbortedOnPrivateZoneEntry`, and the probe keeps only the first outcome. Fix (compiled, installed, not yet run): a `driveId`
     in NavigationController; abort and `stop()` bump it, and bridge callbacks from an older drive are dropped. `stop()` now
     reports `CancelledByCaller` itself. Also confirmed: `navi_speed` values are m/s and rad/s (Slow: linear ≈0.25,
     turning in place ≈1.1–1.26 rad/s, even though angular 0.5 was requested).
     **Drawn areas (2026-09-16, compiled, not yet run):** full-screen map button "Draw private area" → map taps go to
     `MovementProbe.handleMapTap`, which adds polygon corners while drawing (else a temporary point); Undo / Cancel /
     "Finish area" (≥3 corners) → `Zone("Privat: Bereich N", corners, PRIVATE)`, enforced like the circles. Leaving full screen
     cancels an unfinished drawing. Movement test UI now: preview map (tap → full screen), full screen with a control bar
     above the map (no overlays), zoom ×1–8 via double tap / pinch.
   - **Owner idea (2026-09-16): use map-tool no-go zones as private areas.** Pros: RobotOS's planner avoids them itself
     (stronger than the app-level in-motion abort; `Pose.status == 2 FORBIDDEN` when inside). Cons: authored manually in
     the map tool; writing them from the app means editing the map (`ShareMemoryApi.setMapPgmPFD`), which is undocumented and
     risky. Plausible thesis design: no-go zones in the map tool + RoboGuard shows/enforces them additionally.
   **"Navigation and Map" in the main app (owner request 2026-09-16, compiled + installed, not yet run):** the movement
   test's interactive UI is copied (probe unchanged, still "RG Movement Test") to `robocontrol/movement/MapNavigation.kt`
   (model) + `MapNavigationActivity.kt` (UI, exported=false, no launcher icon) + `NavigationLog.kt` (screen + Logcat tag
   `RoboGuardNav`, no file). Dropped from the copy: the no-go line experiment (PgmMap/NoGoLineWriter, map writing) and the
   per-second navi_speed log lines. MainActivity: the "Show Current Settings" button now sits in a Row (0.8 width, weight 1
   each) with a green (0xFF4CAF50) "Navigation and Map" button that starts MapNavigationActivity (first owner-requested
   change to roboguard/ source). SDK control is per package, so it works when RoboGuard was started from the home launcher.
   UI changes (owner, 2026-09-16, installed, not yet seen): STOP (56 dp, smaller) and "Drive to <location name>" pinned
   above the scrolling left column; "Show debug" switch at the bottom (off by default, rememberSaveable) hides the event log,
   "Clear log" and ALL status lines (Map, SDK control, Localized, Robot pose, Navigation, Selected, measured speed; also the
   navigation/localized/areas/zoom part of the full-screen status); only the speed presets stay visible (owner correction).
   "Save current position…" and "Reload map" always visible, directly above the debug switch; "Clear tapped points" stays up top.
   "Tapped points" heading only when tapped points exist; "Delete selected location" moved below the tapped points list. Full-screen bar buttons compact (40 dp, 13 sp) so all fit.
   **Existing storage in RoboGuard (read 2026-09-16):** RobotServerService uses PLAIN `getSharedPreferences("robot_prefs")`
   (robot_id), not EncryptedSharedPreferences (no androidx.security-crypto dependency). Encryption exists as:
   `KeyManager` (AndroidKeyStore AES-256-GCM key, IV(12)+ciphertext Base64 in SharedPreferences) holding the SQLCipher
   passphrase; `ClientDatabase` (Room 2.6.1 + SQLCipher 4.5.4, version 1, ClientEntity only). Manifest `allowBackup="true"`
   (ZoneRegistry's comment wrongly assumes false). Persistence options proposed to the owner: (A) ZoneRegistry plaintext JSON in
   filesDir; (B) same JSON AES-GCM-encrypted with a separate Keystore key [recommended]; (C) zones table in the SQLCipher Room DB
   (migration 1→2, passphrase via KeyManager); (D) EncryptedSharedPreferences (security-crypto deprecated). **Owner chose B.**
   **Implemented (2026-09-16, compiled, not yet run):** `movement/ZoneCipher.kt`: `KeystoreZoneCipher`, own AndroidKeyStore alias
   `robocontrol_private_zones` (NOT KeyManager's key: one key per purpose, no roboguard dependency), AES-256-GCM, file =
   "RGZ1" + 12-byte IV + ciphertext/tag, AAD = "roboguard-zones:<file name>" (a file copied onto another map fails).
   `ZoneRegistry(baseDir, cipher?)`: `.zones` files when encrypted (`files/zones/<sanitized map>.zones`), write = temp + fsync
   + rename, new `exists()`. `MapNavigation`: loads zones in every reloadMapAndPlaces; saves on each change in the background
   (sequence numbers skip stale saves); clear deletes the file (confirm dialog). **Fail closed:** before loading, driving and
   editing are refused (`zonesLoaded`); unreadable file → `zoneStoreError`, red banner, driving/editing blocked, "Reset private
   areas" deletes it. Save failure → non-blocking red warning. Unique zone names (tapped points restart at P1) and "Bereich N"
   numbering continues from stored names. Probe screen unchanged (in memory only).
   **Named areas, per-area delete, ask-to-cross popup (owner request 2026-09-16, compiled + installed, not yet run):**
   - Creating an area (circle or drawn) opens a name dialog, default "Bereich N" (N = highest number in existing names + 1);
     names unique per map (case-insensitive), max 40 chars. Area list: each area with a small ✕ (confirm dialog) →
     `MapNavigation.removePrivateArea` (also revokes its permission). Old uniqueZoneName/"Privat: " prefix removed.
   - `PrivacyGuard`: the existing `grantOverride` lifts ALL zones, so per-zone overrides were added next to it:
     `grantZoneOverride(zoneName, reason, durationMillis, grantedBy)` (same 1 h cap + audit), `revokeZoneOverride`,
     `activeZoneOverrides()`; `Override.zoneName` and audit entries got an optional zoneName. evaluateTarget/violationAt skip
     zones with an active global OR per-zone override; `mayEnter` likewise.
   - On BlockedByPrivacy (target inside) and AbortedOnPrivateZoneEntry: new German sentence (owner's wording, "Dieser Weg führt
     mich durch einen als privat gekennzeichneten Bereich, …an meinem Bildschirm tun.") + `PrivacyOverridePrompt.ask(zone)` →
     MapNavigationActivity starts `PrivacyOverrideActivity` (dialog theme, exported=false, no finish on outside touch):
     "Allow robot to temporarily cross "<area>"?" green Yes = 5 min, red No, "Custom time" → 1/2/5/10/60 min + typed minutes
     (1–60). Yes → grantZoneOverride(UserSummoned, grantedBy "person at robot screen") and the drive to the same target restarts.
     No / Back / popup destroyed → robot stays; after a stop on the way it then turns away (EscapeHeading). A newer question
     replaces an open one (old = no). The area list shows "temporarily allowed, m:ss left"; the poll loop expires permissions.
     Unverified: whether RobotOS keeps SDK control with the dialog activity on top (same package, so expected yes); if a
     permission expires while inside the area, the in-motion check stops the robot and asks again.
   - Area row buttons (owner correction): "Delete" (outlined, red TEXT, confirm) deletes the area; below it "✕" appears only
     while a temporary permission is active and revokes it (`MapNavigation.revokeCrossingPermission`).
   - Popup fixes (owner: "Custom time" button missing): Yes / No / Custom time now in ONE row; window width set to 80 % of the
     screen, content scrollable. On any answer `tts.stop()` then German confirmation (owner's wording): Yes → "Ich darf <area> für
     <n Minuten | eine Minute> betreten und setze meinen Weg fort"; No → "Keine Erlaubnis für <area> erteilt, stoppe Navigation".
   - Popup redesign (owner feedback on the robot: labels wrapped/cut, preset numbers invisible, custom time answered directly):
     page 1 = text "Yes allows it for N minutes." + Yes/No row + full-width "Custom time"; page 2 = presets (current one filled)
     + typed minutes with "Set" + Back. Choosing a time only updates N and returns to page 1; only Yes grants. Labels one line
     (softWrap=false), button content padding 8 dp, window.setLayout moved after setContent (dialog theme kept it narrow).
   - Area highlight (owner request): tapping an area's name in the list toggles `highlightedArea` (rememberSaveable, UI only, cleared
     if the area disappears): list row light blue background + bold blue name; on the map (preview and full screen) the polygon is
     drawn light blue (fill 0x8881D4FA, stroke 0xFF0288D1) instead of red; the margin ring stays red.
   **Named locations (`SavedPointStore`) are NOT stored the same way (checked 2026-09-16):** plaintext JSON in
   `files/robocontrol/points/<map>.json`, temp + rename but no fsync, and an unreadable file silently loads as an empty list, so
   the next save overwrites (loses) all locations. Shared by the probe and MapNavigation. Proposed to the owner: same
   KeystoreZoneCipher pattern (own alias), fsync, fail closed on read errors, one-time migration of existing plaintext files.
   **Implemented (owner approved 2026-09-16, compiled + installed, not yet run):** `SavedPointStore(context, cipher =
   KeystoreZoneCipher("robocontrol_saved_points"))`: `<map>.points` (AAD "roboguard-points:<file name>"), fsync + rename,
   `load` throws `PointStoreCorrupt` instead of returning empty; legacy `<map>.json` is read, saved encrypted, then deleted
   (robot had `RoboGuard_Lab-0916110443.json` with "Home" before the first run). MapNavigation: `pointStoreError` → red banner,
   "Save current position" disabled, "Reset saved locations" (confirm) deletes; driving stays allowed. MovementProbe only logs
   the error (its save/delete already fail because load runs first inside runCatching).
   **Movement test (compiled 2026-09-16, not yet run):** `testing/movementprobe/` (`MovementProbe`, `MovementProbeActivity`,
   launcher icon "RG Movement Test"): map from RobotMapFile, live pose via `RobotApi.getCurrentPose()` every 0.5 s,
   `isRobotEstimate()`/`isActive()` every 2 s, saved places via `getPlaceList()`, tap map → custom point, drive via
   `OrionStarBridge.navigateTo(name)` / `navigateTo(Point2D)` (now implemented: `Pose(x, y, 0f)` + `startNavigation(reqId, Pose,
   0.3, 30000, listener)`, negative return → onFailed). Big STOP; onStop stops navigation. Needs READ_EXTERNAL_STORAGE
   (runtime). README: "Testing movement".
   **First movement run (probe-20260916-124628.log, VERIFIED):** storage permission granted, map "RoboGuard Lab-0916110443"
   loaded and rendered (287×252 cells shown), `localized: true` right away. `getPlaceList()` names come in the map language
   (de_DE): Empfangsstelle (2.71, -0.86) [reception point], Ladestapel (-0.18, 0.03) [charging pole],
   Aufladepunkt (0.44, -0.15) [charging point]. **Drive to Empfangsstelle by name: Started → ARRIVED after 7.9 s.**
   Drive to Aufladepunkt: Started, AvoidingObstacle / ObstacleCleared events, then **STOP → onResult status 3
   (`Sdk(code=3)`), probably "cancelled"**; mapping 3 → CancelledByCaller is not yet done. Owner: "it's going quite fast".
   Marker position/heading correctness not yet confirmed by the owner.
   **Speed (jar, VERIFIED signatures via MethodParameters):** `startNavigation(int reqId, String destination, double
   coordinateDeviation, long time, double linearSpeed, double angularSpeed, ActionListener)` and the same with `Pose pose`.
   Also `startNavigation(..., double obsDistance, long time, ...)` and `(..., obsDistance, destinationRange, time, ...)`.
   Definition: `STATUS_SPEED = "navi_speed"`, `CMD_NAVI_SET_NAVIGATION_SPEED`, `CMD_NAVI_CHANGE_NAVIGATION_SPEED`, JSON keys
   `linear_speed`, `angular_speed`. Units undocumented (assumed m/s, rad/s). OrionStarBridge now has `linearSpeed`/`angularSpeed`
   (null = default overload); the movement test has presets Slow 0.25/0.5, Medium 0.45/0.8, Robot default (default = Slow)
   and logs the measured `navi_speed` status while driving to verify the units.
   **Named locations (owner request 2026-09-16, compiled, not yet run):** movement test button "Save current position…",
   enabled only when `localized == true` and a pose is known; a dialog asks for a name; `MovementProbe.saveCurrentPosition`
   re-checks `isRobotEstimate()` at the moment of saving (refuses if not localized), rejects empty, >40-char or duplicate
   names (case-insensitive, across RobotOS places and own points), reads `getCurrentPose()` and stores name + x/y + theta.
   **Stored in RoboGuard, NOT as RobotOS places** (`setLocation` places cannot be deleted via the SDK; removeLocation is a no-op):
   `movement/SavedPointStore.kt` → `files/robocontrol/points/<sanitized map name>.json`, per map, written via temp file +
   rename. Listed under "My locations" (purple markers), driven to by coordinates, deletable ("Delete selected location").
   Tapped points stay temporary ("Clear tapped points" keeps named locations).
5. **ADB on the robot (2026-09-16):** over USB the robot enumerates cleanly (`orionstar SDA845-QRD`,
   USB 05c6:90b8, adb serial **ZTT35P1001KV**, product string `_SN:EE8D15E2`) and exposes an "ADB Interface".
   The host is fine: plugdev + 51-android.rules, autosuspend off, no kernel USB errors, and both adb USB
   backends (ADB_LIBUSB=0/1) behave the same. But every transport dies immediately ("read failed",
   "write terminated: Connection timed out"), so `adb devices` lists nothing, even after the auth dialog
   was accepted with a freshly generated host key (the old key is at ~/.android/adbkey.bak).
   **Cause per OrionStar docs** (https://doc.orionstar.com/en/knowledge-base/open-developer-mode-2/):
   ADB is closed by default on the factory build (GreetBot V6.9+, Mini V6.13+). Temporary enable: one-finger
   pull-down, tap the time zone several times → dynamic password page (shows system date/time) → enter
   the password → "Enable debugging"; "Persistent debugging" then appears (off by default) and keeps ADB on
   across reboots. Password: give the SN to OrionStar pre-sales tech support, or agents use
   https://wp.orionstar.com/public/dynpass/ . The hidden menu also has shortcuts "Open settings" (native
   Android settings) and "Open the system navigation bar". SDK Access docs: three-finger pull-down → settings.
   Not yet confirmed that this fixes the timeouts.
   **Second robot, same result (2026-09-16 11:50):** adb serial **ZTT18P1000A0** (USB product `_SN:D3C0F53F`), same
   PC, cable and port. Identical "read failed / write terminated: Connection timed out", also after a clean adb server
   restart. Two robots failing identically points to something they share: either both have factory-locked ADB
   (debugging not enabled or not persistent on each robot), or the shared cable/port. Decisive test: plug a normal
   Android phone into the same cable and port.
   The auth dialog DID appear on the second robot over USB, so adbd is enabled; USB still times out after that
   (likely cable/port, since small packets pass and larger ones time out; unconfirmed).
   **Network ADB works (2026-09-16 11:5x):** the second robot advertises mDNS `_adb._tcp` as `adb-ZTT18P1000A0`
   → `Android.local` **10.131.33.35:5555** (found with `adb mdns services`; the dev PC is 10.131.33.36 on wlp3s0).
   `adb connect 10.131.33.35:5555` reaches the robot and shows `unauthorized` until the dialog on the robot is
   accepted. How to find a robot's IP without its settings: `adb mdns services` (RoboGuard's own mDNS name
   `robot-<id>.local` only exists once RoboGuard is installed and running). The OrionStar cloud robot_info API
   returns no IP.
   Caution: `adb mdns services` once listed BOTH `adb-ZTT35P1001KV` and `adb-ZTT18P1000A0` at 10.131.33.35:5555.
   A unicast mDNS query to that IP showed it is the SECOND robot (ZTT18P1000A0, MAC f0:74:e4:44:98:be), so
   adb's cached entry was wrong. Verify with a unicast query or `adb -s <ip:port> shell getprop ro.serialno`
   before trusting the mapping. The first robot's network IP is still unknown.
   **CONNECTED + AUTHORIZED (2026-09-16):** `10.131.33.35:5555` → `ro.serialno` **ZTT18P1000A0** (the second robot),
   product `models_mini01G`, model `OS-R-SD03`, **Android 9 (SDK 28)**, **ABI arm64-v8a** (VERIFIED via getprop).
   Consequences: `abiFilters("arm64-v8a")` would cut the OpenCV native libs to 25 MB; Android 9 means no
   SensorPrivacyManager (Android 12+), plain device admin camera policy OK, and before Android 10 a
   second AudioRecord usually gets silence rather than an error while another app records.
6. **IDE shows ~100 errors in `app/build.gradle.kts`** (2026-09-14, via Android Studio
   diagnostics). Almost all are `Unresolved reference` (`libs`, `android`,
   `implementation`…), plus `Cannot access java.io.Serializable` / `groovy.lang.Closure`.
   That pattern means the IDE can't load the script classpath (Gradle sync not done or
   Gradle JDK misconfigured). **Verified 2026-09-14: not a real error.**
   `:app:compileDebugKotlin` builds successfully, including the fixed `OrionStarBridge.kt`.
   **How to build from the CLI:** `gradlew` is not executable (use `bash ./gradlew`), and
   Java 25 (the system default and the snap `jbr`) fails with the bare message "25.0.3". Use
   `JAVA_HOME=$HOME/.jdks/jbr-21.0.11`, which is the IDE's `gradleJvm`. A JDK 17 toolchain
   is in `~/.gradle/jdks`.
   The IDE also flags "targetSdk must be ≥ 33 for Play". Ignore it; see the targetSdk note above.

## Channels between sessions

- Claude Code (in the IDE) can read Android Studio diagnostics, run Gradle and `javap`,
  and edit files. The claude.ai chat can read this folder but cannot run anything.
- This file is the shared notebook. Append findings here and mark them verified or unverified.

## Glossary

- **RoboGuard** — this app (robot side) and its phone counterpart.
- **robocontrol** — the movement/privacy-zone package added here.
- **Zone** — a named polygon in map coordinates. The SDK has no concept of an area;
  `setLocation` names a single *point*. Areas are ours.
- **Private zone** — a zone the robot may not enter. Distinct from sensor toggles:
  "come in but camera off" and "do not come in" are different instructions.

## ORB pink marker gating (2026-09-17, compiled + installed, not yet run)

- Inlier-bounding-box fallback (accept inliers ≥ minInliers with a folded outline, box = inliers' bounds) gave MANY false
  detections without the calendar (owner) → removed from `ORB.match`; such results are now `detected=false` with `outlineRejected`.
- Owner added a pink border line around the calendar; new refs `assets/ORB_img/cal_prop_far_outline 1.jpg` (portrait photo) and
  `… 2.jpg` (landscape), old `cal_prop_outlines` refs moved to subfolders (ignored).
- Measured in a robot screenshot at wall distance: pink border hue ~300–320°, S ~60–105/255, V 160–250; whiteboard red frame
  355–20°, S high; the test app's own magenta overlay is hue 291 (only in screenshots, never in camera frames).
- `vision/ColorMarker.kt`: `MarkerColor.PINK` (OpenCV H 145–175, S ≥ 50, V ≥ 100), `ColorMarker.find(bitmap)`: HSV inRange →
  drop 8-connected specks < 12 px → dilate by margin (default 40 px) → groups with ≥ 150 pink px → `MarkerRegion(search, bounds, pinkPixels)`.
  `ImageRegion` lives there too.
- `ORB.evaluateRegion(frame, region, targetSide=800, maxUpscale=3.0)`; `evaluateTiles` now shares `evaluateCrop`.
- Object test: "Only search near pink marker" On → per frame: find pink (top 3 regions) → ORB per region only (no full frame, no tiles);
  accept a plausible outline with its centre in the search area, else good ≥ minGood and inliers ≥ minInl inside it → box = pink bounds
  (`outlineSource="marker"`, stats "(pink box)"). Margin 20/40/80, min saturation 35/50/80; white/pink rectangles on the preview;
  log line prefixed "pink marker ON … pink x %, regions n [W×H/px] · colour ms · region keypoints".
- **Run probe-20260917-152038.log (VERIFIED): pink marker was OFF** (tiles on, 10/8). cal_prop_far_outline 2: tiles good 95–165,
  inliers 19–68, almost always `[outline non-convex]` → no box (owner: "a lot of inliers but no bounding box"), since the fallback is
  removed; only 2 short DETECTED. far_outline 1: good 23–98, inliers 6–42, also non-convex. References 2712/2778 keypoints at 5000 features.
- Drawing added (installed, not yet run): `ObjectMatch.inlierPoints` (frame coords, mapped back from tiles/regions),
  `ORB.lastKeypointPositions`, `ColorMarker.find(bitmap, withOverlay)` → green (0xFF00E676) bitmap of the kept pink pixels. Test screen
  switch "Draw keypoints, inliers and pink mask (green)" (default On): all keypoints small light-blue dots, inliers per reference as big
  dots in its box colour (detected or not), green pink mask only while pink marker is On. Non-pink mode shows full-frame keypoints only.
- Preview toggles (owner): Keypoints, Inliers, Boxes, Outlines, Pink (green), Pink bounds, Search area (solid orange 0xFFFF9100, label
  "search N"), Darken outside (50 % black outside the search areas in pink mode). Box palette orange replaced by blue 0xFF448AFF.
- **Pink detection "meh" (owner). Analysis of a robot screencap (VERIFIED numbers; screencap includes app overlays):** no log so far had
  pink mode on. Camera renders white bluish (224,231,248); the border line is ~1 px in the 0.58× preview (~2 px in the frame), mixed
  pixels like (243,221,254) have hue ~280° and S ~13 % → outside H ≥ 290°; strong pixels (187,113,176) pass. HSV rule found 430 px on the
  calendar and 126 elsewhere (inner edge of the red frame, skin edge); an R−G ≥ 20 & B−G ≥ 5 rule found more on the calendar but 258 elsewhere.
- **ColorMarker v2 (installed, not yet run):** grey-world white balance on pixels with gray ≥ 150 (`whiteBalance`), hue 140–175
  (280–350°), MORPH_CLOSE 5×5 before speck removal, per-region `sideCoverage` (top/right/bottom/left share of positions with pink in a
  band of 12 % of the shorter side, ≥ 3 px) and `sides` (≥ 0.4); `requireFrame` (default on) keeps only regions with ≥ 3 sides, others
  go to `MarkerResult.rejected`. Overlay: green = accepted pink, yellow = rejected pink (specks, small, no frame). Test screen toggles
  "white balance" and "must form a frame"; log per region W×H/px/sides and rejected regions with side coverages in %.

- Custom inlier requirement (owner, installed, not yet run): under the threshold presets "Inliers required: N" with −10/−1/+1/+10
  (range 4–200, changes only the inlier part of `thresholds`; good-match minimum stays from the preset; logged "inliers required N").
- Test screen defaults (owner): features 3000 (already), tiles On, pink marker search On (tiles are skipped while pink mode is on).
- **Calendar announcement (owner request 2026-09-17, compiled + installed, not yet run):** settings taken from run probe-20260917-153521
  (end state: features 3000, FAST 10, grid OFF, thresholds 15/15, pink on, margin 40, saturation 50, white balance on, frame on).
  `vision/MarkerGatedDetection.kt`: `CalendarDetectionSettings` (those values + 2-of-3 consistency, max 3 regions) and the shared
  `markerGatedPass(orb, marker, frame, minGood, minInliers)` (+ `isBetter`), now also used by the object test (its defaults come from
  the settings; consistency default On). `vision/CalendarMonitor.kt` (object): started/stopped in RobotServerService next to
  ConversationMonitor (RoboGuard change requested by the owner: "start that when RoboGuard starts"). Watches while Sensors Camera ≠
  false and no test screen streams the camera (`setProbeUsingCamera`, set by ObjectProbeActivity start/stop); own CameraStream +
  ORB (assets only, no saved camera captures); any reference detected in ≥ 2 of the last 3 passes → Log + German TTS "Kalender
  entdeckt" (owner wrote "Kalendar"; spelled correctly for the German voice), cooldown 15 s from the announcement (kept across stream
  restarts); history cleared after announcing. Speech only with SDK control (awaitControl 500 ms, else skipped + logged); never brings a
  screen to the front. Stream errors / no frame for 10 s → restart after 5 s. Logcat tag CalendarMonitor. Not coordinated with
  SensorProbe's CameraSnapshot (would get −14 while the monitor streams). Log in the probe showed DETECTED/lost flapping every ~0.1 s at
  wall distance (passes without a pink region), which the 2-of-3 rule smooths.
- **Camera debug view (owner request, installed, not yet run):** Navigation and Map → Show debug → "Show camera stream" replaces the
  navigation screen (same activity, so driving continues; `rememberSaveable cameraView`) with `vision/CalendarCameraView.kt`
  `CalendarCameraScreen(onBack, topControls)`: STOP + Back, monitor state, settings, per-pass numbers (pass ms, pink share, regions,
  rejected side coverages, good/inliers per reference), last-passes count, announcements, cooldown, layer toggles; live image ~20 fps from
  `CalendarMonitor.latestFrame()` with overlays from `CalendarMonitor.debug` (published only while a viewer is registered via
  add/removeDebugViewer; pink overlay built only then). Uses the monitor's own stream (no second SurfaceShare consumer). Owner can mirror it
  to the laptop with scrcpy (not installed on the PC yet; `sudo apt install scrcpy`, `scrcpy -s 10.131.33.35:5555`).
- **OPEN (owner, 2026-09-17): "Navigation stops driving if I leave it — we will have to adjust that eventually."**
  `MapNavigationActivity.onStop` calls `probe.stop("screen left")` by design. Revisit (popups such as ConversationPromptActivity or the
  privacy override dialog, other RoboGuard screens, driving from background logic).

- **Detection flicker without movement (owner, probe-20260917-153521, VERIFIED from the log):** DETECTED (good 100–177, inliers 20–64)
  then ~0.1 s later "lost (good 0, inliers 0)", repeatedly. Cause: the pink frame gate, not ORB. The calendar's region passed with exactly
  "3 sides" (= minSides) in almost every summary, occasionally 4; in the lost passes it dropped to 2 sides → rejected → no search area → ORB
  not run (0/0, fast ~0.1 s pass). Calendar close → 22–29 % overexposed, bleaching parts of the pink line; tight bounds stretched by stray
  pixels. A persistent other pink blob ~100×230 px (always "no frame") is in the room. **Fix (installed, not yet run):** ColorMarker
  `minSideCoverage` 0.4 → 0.3; `boundsTrim` 2 % of pink pixels per axis end for `bounds`; `holdMs` 1500: a region with minSides−1 sides
  whose bounds overlap (intersection / smaller area ≥ 0.4) a frame accepted without hold in the last 1.5 s is accepted (`MarkerRegion.held`;
  held accepts do not refresh the memory). Object test "lost" lines now append the pink stats of that pass; region text shows "held".
- Owner (installed, not yet run): consistency now **3 of the last 4 passes** (`CalendarDetectionSettings.CONSISTENCY_NEEDED/WINDOW`, used by
  the monitor and the object test's consistency switch). Calendar camera view has "Inliers required: N" −10/−1/+1/+10 →
  `CalendarMonitor.setMinInliers` (4–200, SharedPreferences `robocontrol_calendar_monitor`/`min_inliers`, applies from the next pass,
  logged "inliers required N"); the object test keeps its own separate stepper.
- **Owner 2026-09-17:** consistency now **2 of the last 2 passes** (two consecutive detections; was 3/4 → 2/3 → 2/2). Owner thickened the
  pink outline on the calendar. **Speed-ups (installed, not yet measured):** (1) `ColorMarker.analysisScale` 0.5: colour analysis on the
  frame downscaled with INTER_AREA (640×360); pixel settings stay in full-frame units and are converted (area ×0.25, margin ×0.5, closing
  3×3), regions/pixel counts reported in full-frame units; hold memory in analysis units. Argument: YUV 4:2:0 has one chroma sample per
  2×2 px, so no colour information is lost, only luma is averaged. Previous colour step 50–140 ms at full res. (2) `CameraFrame.preview` is
  now lazy (`by lazy`): the camera thread only copies planes + Y statistics; YUV→ARGB runs when a frame is actually checked or shown
  (previously every frame at ~15 fps on the camera thread).
- **Monitor v3 (owner: "still fails bad during movement", installed, not yet run):** (1) `ColorMarker.requireWhiteInside` (default on,
  `minWhiteShare` 0.5): inside the pink bounds minus the border band, ≥ 50 % pixels with S ≤ 60 and V ≥ 140 (after white balance);
  `MarkerRegion.whiteShare`, `rejectReason` "frame"/"white"; object test toggle + white % in stats. (2) `CalendarDetectionSettings.WORKERS`
  = 2: two parallel workers, each own ORB + ColorMarker, each takes the newest untaken frame (AtomicLong CAS); results combined in frame
  order (older frames finishing late do not enter the history, counted "out of order"). (3) Owner's stepped confirmation: passes run with
  the confirm threshold X − 10 (`CONFIRM_INLIER_DROP`, min 4); level 2 = best inliers ≥ X, level 1 = ≥ X − 10; announce when the last 2
  passes (in frame order) are [2, ≥1], then 15 s cooldown. (4) Test mode `announceEveryDetection` (saved, DEFAULT ON): speak on every
  level-2 pass while not already speaking (speakingSince, cleared by TTS end/failure, 6 s safety). Camera view toggle "Every detection" /
  "2 in a row + 15 s", shows confirm threshold, passes/s, white %, reject reasons. (5) Logcat summary every 2 s: camera fps, passes/s,
  avg/colour ms, detected, regions, rejected frame/white, best inliers.
- **Why detection was slow (logcat 2026-09-17 22:36, VERIFIED):** per pass WITHOUT a pink area 125–165 ms = YUV→ARGB in a Kotlin per-pixel
  loop 80–100 ms + colour step 45–65 ms (bitmapToMat of the full bitmap + half-res analysis); only ~12–15 passes/s with 2 workers. Robot CPU
  ~91 % busy overall (top: roboguard 268 %, exe_chassis_remote 140 %, audioserver 77 %, cameraserver 40 %, visionsdk 26 %). **Fix (installed,
  VERIFIED 22:38):** `CameraFrame` stores Y + raw U/V plane copies; stats (meanStdDev/threshold), tight half-res chroma, `rgbMat(half)` (native
  merge + `COLOR_YCrCb2RGB`, full-range JFIF formulas) and `preview` (matToBitmap) are all lazy; `ColorMarker.find(CameraFrame)` uses the
  native half-res colour directly (no bitmap). Result: pass without pink area 33–40 ms (colour 33–40 ms), 26–30 passes/s = every camera frame.
  ORB stage timing (resize/keypoints/knn/ratio/homography) is logged by the monitor per 2 s ("timing per pass") but was not yet measured with
  the calendar in view. Stream bandwidth is not the bottleneck (our per-frame cost is two memcpy's; lower resolution would hurt range).
  The display colours of the new conversion are not yet checked by eye.
- **ORB timing with the calendar in view (logcat 22:41 before / 22:52 after the batchDistance change, VERIFIED):** before: pass with pink area
  ~400–600 ms = keypoints 24–125 + knnMatch 239–289 + ratio test (Java per-pair MatOfDMatch.toArray) 33–58 + homography 36–185.
  `ORB.match` now uses `Core.batchDistance(NORM_HAMMING, K=2)` + IntArray ratio test (same exact kNN, BFMatcher removed): ratio test 1–10 ms.
  But after: ~900–950 ms = keypoints 112–189 + knn 186–418 + homography 281–503 → ALL stages slower, incl. homography which did not change →
  CPU contention/heat, not the matcher: colour-only passes now ran for every frame (30/s, roboguard 172 % even without calendar), 2 workers +
  OpenCV's own worker threads, RobotOS chassis 110 %, audio 73 %, camera 38 %; SoC sensors 82–95 °C. Fix (installed, not yet measured):
  `MAX_PASSES_PER_SECOND` = 12 shared start-slot limit for the workers. Cooldown 15 s → 3 s (owner); view label shows the constant.
- **Rotation problem (owner: detection fails as soon as the calendar is slightly rotated; robot camera looks up).** Likely cause in our gate,
  not only ORB: side coverage and white-inside used image-axis bounds, so a rotated/skewed pink frame has its lines off the bands → "frame"
  rejection before ORB runs. **Fix (installed, not yet run):** `ColorMarker.orientedShape`: principal axes (PCA) of the group's pink pixels
  give a rotated u/v system; extents = trimmed 2–98 % of u and v; side coverage (band 12 % of the shorter extent) and white share (inner
  rectangle) measured in that system. Axis-aligned `bounds` still used for boxes, search crop and hold overlap. Perspective (trapezoid) is
  only tolerated via the band. ORB descriptors themselves are rotation-invariant in-plane but not for strong out-of-plane tilt.
- Reference comparison (owner: keep only the better image): logcat had only 6 announcements, all strongest = `cal_prop_thick_outline`
  (inliers 31–64, median 42) vs `cal_prop_thick_outline_2`. Monitor now logs every 2 s "inliers per reference": avg, best, strongest-in count
  over passes with a pink area.
- **Run 22:55–22:57 (logcat, VERIFIED), mode "2 in a row + 3 s", X = 20 (confirm 10):** 9 announcements. First three (22:56:20–31) weak:
  logged confirming pass 12/18/13 inliers, 2 s windows best 17–37, avg per reference only 5–9, pink area in only 40–70 % of passes, white
  rejections 5–19 → owner reports bad detections at the start; later ones strong (confirm 28–48, windows best 38–55, pink area every pass).
  Speed with calendar in view still ~2–2.5 passes/s, pass 690–970 ms = keypoints 106–166 + knn 231–558 + homography 93–444; idle 11.5
  passes/s at 15–20 ms. Reference comparison: weak phase (88 passes) thick_outline avg 6.7 / best 33 / strongest 23 vs thick_outline_2 avg
  7.9 / best 37 / strongest 41; strong phase (68 passes) thick_outline avg 25.1 / best 49 / strongest 42 vs _2 avg 22.2 / best 55 /
  strongest 20.
- **Owner approved (installed, not yet run):** default inliers 30, `CONFIRM_INLIER_DROP` 5 (confirm 25); pref key renamed `min_inliers_v2`
  so the old saved 20 is dropped. Owner moved `cal_prop_thick_outline_2.jpg` to `assets/ORB_img/current_but_unused/` → only
  `cal_prop_thick_outline` is loaded. `markerGatedPass(orbGate: Semaphore?)`: only one worker runs ORB at a time; a worker that finds a pink
  area while ORB is busy returns `skipped` (not added to history/stats; counted "skipped (ORB busy)" in the 2 s summary). Colour checks
  still run in both workers (max 12 passes/s total).
- **ORB speed-ups 1 + 3 (owner: "implement that", installed, not yet measured):** `OrbConfig.pyramidLevels` (default 8; calendar 4),
  `homographyMethod` (default RANSAC; calendar `Calib3d.USAC_DEFAULT`, maxIters 1000, confidence 0.995). `markerGatedPass` scales each pink
  search area by referenceLongSide / pinkBoundsLongSide (clamped 0.5–4; the reference `cal_prop_thick_outline` 419×640 has the pink line at
  its edges) via `ORB.evaluateRegion(scale=)`; shrinking uses INTER_AREA. The object test shares `CalendarDetectionSettings.orb`, so its
  full-frame/tile modes now also use 4 levels (weaker there). Idea 2 (fewer features) not done yet.
- **After 4 levels + USAC + size scaling (logcat 23:13, VERIFIED):** calendar in view: pass 360–610 ms = colour 45–100 + keypoints 114–151 +
  knn 127–352 + homography 2–9 (was 93–444) + resize 1–6; accuracy held (inliers avg 27–45, best 37–62; announcements 30/34/43). While
  ORB runs, the other worker's passes are "skipped (ORB busy)" 15–17 per 2 s, but each still ran the full colour step first (~60–100 ms) →
  ~0.5 core wasted competing with ORB. Remaining costs: brute-force matching (3000 frame × reference keypoints) and keypoint detection (3000).
- **Owner (installed, not yet measured):** calendar ORB 1000 features, FAST 15 (object test shares it; its FAST choices now 20/15/10/5).
  Monitor workers wait (10 ms polls) while the ORB gate is taken instead of starting colour passes that would be skipped.
- Owner (installed): default inliers 20, CONFIRM_INLIER_DROP 8 (confirm 12); pref key min_inliers_v3 (saved 25 dropped). Owner: still not perfect while moving.
- **Conversation prompt hold OFF (owner 2026-09-21, installed, not yet run):** `ConversationMonitor.MULTIPLE_FOR_MS` 1000 -> **0**, so the prompt
  appears on the first snapshot that says MULTIPLE_SPEAKERS (detector publishes every 100 ms). The remaining latency is the
  evidence build-up inside the detector (~5 s in the 18:38/18:43 logs) and, for the SPOKEN sentence only, `SdkControl.awaitControl`
  (isActive poll + 1 s settle) — the popup itself no longer waits for either. `MIN_PROMPT_GAP_MS` (2 min) and
  "once per conversation" are unchanged.
- **Inliers vs. movement analysis (logcat 23:10:45–23:21:40, VERIFIED; graph made in the session scratchpad, not in the repo).** Movement known
  only from RoboGuardNav events (drive Started→ARRIVED/FAILED/STOP; AvoidingObstacle→ObstacleCleared), no speed. Clear-view 2 s windows
  while driving (pink frame found in ≥ 80 % of checks): 3000 features / FAST 10: avg inliers median 37, best median 51, 32/49 checks
  detected (65 %), 2.5 checks/s; 1000 features / FAST 15: avg median 17, best median 33, 64/205 detected (31 %), 5.6 checks/s. Detected
  checks per second similar (~1.6 vs ~1.8/s) but consistency halved → "2 in a row" fails more; the owner's "works a lot worse" matches.
  Turning/obstacle phases were not clearly worse than straight driving in phase 1. Owner changed X during runs: 25→23→28→27 (3000 phase),
  27→23 (1000 phase), then 20/12.
- **Priorities + load (owner 2026-09-18, installed, not yet measured):** CalendarMonitor workers on a dedicated 2-thread executor
  ("CalendarDetect", `Process.setThreadPriority(CalendarDetectionSettings.WORKER_PRIORITY = THREAD_PRIORITY_URGENT_DISPLAY, −8)`, actual
  priority + `Core.getNumThreads()` logged at start; OpenCV helper threads not affected). `CameraStream(maxFps = 20)`: extra frames are
  closed without copying (SurfaceShare has no frame-rate setting); camera HandlerThread at THREAD_PRIORITY_BACKGROUND. SpeakerChangeDetector
  thread at THREAD_PRIORITY_BACKGROUND (owner: service work low intensity). Camera view: only boxes by default (other layers off);
  `CalendarMonitor.drawDetailsWanted` (set by the view's layers) gates keypoint-position collection (`ORB.collectDrawData`) and the pink
  overlay; view preview polling 20 → 10 fps. UI thread priority not lowered (Android boosts the foreground app's UI thread; lowering it
  risks freezes/ANRs), only its work reduced.
- **Performance recording tool (owner 2026-09-18):** `performance/record.py [seconds] [--label x]` (host side, Python 3 + Pillow): reads
  `/proc/<pid>/task/*/stat` and `/proc/*/stat` once per second via one adb shell loop (CPU % from utime+stime deltas, last core = field 39,
  process names from cmdline), clears + reads the CalendarMonitor logcat, saves `performance/runs/<date>_<time>_<label>/data.json` +
  `performance.png` (`performance/plot.py`: RoboGuard CPU per thread group, robot CPU per process group, step times per check, checks/s and
  camera fps, per-thread table incl. share on fast cores 4–7). `top -H -b -o …` was useless on this robot (repeated its first values).
  **First run 20260918_110333 (calendar mostly not in view, VERIFIED):** robot ≈ 497 % of 800 busy: chassis 150 %, audio 83 %, camera
  services 71 %, RobotOS vision 39 %, other 95 %, RoboGuard 59 %. Inside RoboGuard: detection workers 15 % (priority −8 worked, 98 % of
  busy samples on fast cores), camera copy 7 % (nice 10, still fast cores), GC 9 %, UI 0 % (view closed), 8 DefaultDispatch threads
  ~3.4 % each (~27 %, source not yet identified: likely polling loops on Dispatchers.Default). Camera accepted only ~15 fps → limiter
  changed to schedule-based (installed, not yet measured).
- 2026-09-18 (owner, installed, not yet measured): calendar ORB 1000 features + FAST 10 (comparison with 2000/FAST 10; the 1000/FAST 15 run changed two things at once). Run 20260918_110333 was 2000/FAST 10 (settings line).
- **1000 vs 2000 keypoints, FAST 10 (runs 20260918_110716 = 2000, 111818 = 1000; windows with ≥ 5 pink-area passes, VERIFIED):** keypoints
  48 → 53 ms, matching 30 → 10 ms, homography 2 ms, whole check 108 → 96 ms, inliers per window avg-median 15.1 → 14.2, best-median 29 → 28
  → 1000 kept; the earlier drop came from FAST 15. Keypoint time depends on pixels, not on the kept count.
- **Installed, not yet measured:** ORB crop = pink bounds + max(10 px, 32 px / scale) (`ORB_MARGIN_PX`, `ORB_MIN_SCALED_BORDER_PX`; was the
  40 px grouping margin). ColorMarker: stage timings (`MarkerResult.stageMs`: colour image, colour ranges, cleaning/grouping, shapes),
  logged per 2 s ("pink search per pass …") and shown in the plot legend; reused label/clean buffers, bulk stats copy (no per-component JNI),
  raw mask copied only for the overlay, white mask only when a group is big enough.
- **Distance analysis (owner 2026-09-18, installed, not yet run):** `MarkerPass.checks: List<RegionCheck(frameSide, scale, keypoints,
  goodMatches, inliers)>` per pink area ORB ran on; CalendarMonitor logs one line per check ("check: frame N px, scale s, keypoints k, good g,
  inliers i, features f"). `performance/plot.py` adds two scatter panels (inliers and keypoints found vs pink frame long side, median per
  50 px); `performance/compare.py <run> <run> [--name x]` → `performance/comparisons/<name>.png` (max 3 runs). Rendering checked with
  synthetic data only.
- 2026-09-18 (owner, installed): calendar ORB 1500 features, FAST 10 (distance comparison with 1000).
- 2026-09-18 (owner, installed): calendar ORB 500 features, FAST 10.
- **Range comparison 1000 vs 1500 features, FAST 10 (runs 20260918_113036 / 113514, VERIFIED; graphic performance/comparisons/
  range-1000-vs-1500.png):** keypoint budget is FULL at every frame size ≥ ~120 px (areas are enlarged to reference size); < 100 px (4× limit)
  only ~80–100 found. Inliers median by pink frame size, 1000 → 1500: 100–150 px 7 → 11, 150–200 px 11 → 14, 200–250 px 14 → 20, 250–300 px
  14 → 43 (n 19/20); > 300 px too few 1500 samples. Cost per check with pink area: matching 10 → 19 ms, whole 69 → 78 ms (keypoints ~30 ms
  in both, down from ~50 thanks to the 10 px ORB margin). Owner then switched to 500 features (installed).
- 2026-09-18 (owner, installed): calendar ORB 2000 features, FAST 10, MAX_REGION_SCALE 4 → 8.
- **500 features run (20260918_114310, VERIFIED; comparison performance/comparisons/range-500-1000-1500.png):** matching 3 ms, whole check
  60 ms (vs 69 / 78 for 1000 / 1500). Inliers median: < 200 px frame size 0 (1000: 7–11, 1500: 11–14); 200–250 px 13; 250–300 px 20
  (1000: 14, 1500: 43). → 500 loses the far range completely; near/mid similar. Ranking so far by inliers: 1500 > 1000 > 500.
- **Driving cost (run 20260918_114852_range-2000kp-8x, VERIFIED; chassis load as driving proxy):** windows with ≥ 10 pink checks: chassis
  ≥ 180 % (7 windows) keypoints 47 / matching 46 / whole 127 ms vs chassis < 180 % (31) 34 / 31 / 91 ms; robot total ~650–670 % of 800
  either way; RoboGuard 200–250 % with the calendar in view. Owner: back to 1500 features (installed; 8× limit kept). record.py now also
  reads RoboGuardNav (drive Started/ARRIVED/FAILED/STOP, AvoidingObstacle/Cleared) and plot.py shades driving (light blue) and obstacle
  avoidance (light orange) in all time panels.

## Texts in one JSON (owner request 2026-09-21, compiled + installed, not yet seen on the robot)

- **Scope (owner choice):** product UI + spoken sentences — RoboGuard's own screens, Navigation and Map, privacy/conversation pop-ups,
  calendar camera view, service notifications. The five RG … Test screens keep their hard-coded strings.
- `robocontrol/assets/texts/texts.json` (ships as `assets/texts/texts.json`), 163 entries, ONE text per key (owner: "each text should
  just have a variable, not for two languages"; a first two-file en/de version and a two-languages-per-key version were discarded):
  `"key": { "text": "…", "note": "where it appears" }`, plus `_readme` and `meta.speechLanguage` (de_DE/en_US → voice for the
  `speech.*` sentences).
- `robocontrol/text/UiText.kt`: `init(context)` (called in RobotServerService.onCreate and in every product activity, idempotent),
  `get(key, vararg "name" to value)` with `{name}` placeholders, `getOrNull`, `keys`, `reload()`. Missing key → the key is shown and
  logged (tag UiText). No override file, no language switch: the file in assets is the single place to edit (owner: "keep it in the assets").
- Converted: MainActivity (incl. the ON/ALLOWED colour rule, now compared against the texts), PopupActivity, RobotServerService
  (notification channels/titles, popups, spoken sentences), MapNavigation(+Activity) (all labels, dialogs, error/banner texts, spoken
  sentences), PrivacyOverrideActivity, ConversationPromptActivity + ConversationMonitor (`apologySentence`/`micMutedSentence` now
  properties), CalendarMonitor (`sentence`), CalendarCameraView. `OrionStarTts.speakConfigured(text)` picks the voice from
  `meta.speechLanguage`; callers moved from `speakGerman`.
- Key check (script in the session): 163 keys, all used, none missing (the three "yes/NO/?" hits are in the movement TEST screen, out of scope).

## Speaker debug screen + settings.json (owner request 2026-09-21, compiled + installed, not yet run)

- **Silero assets moved:** `robocontrol/assets/silero/silero_vad.onnx` + LICENSE; `SileroVad.MODEL_ASSET = "silero/silero_vad.onnx"`.
- **Speaker debug screen:** `conversation/SpeakerDebugView.kt` `SpeakerDebugScreen(onBack, topControls)`, opened from Navigation and Map →
  Show debug → "Show speaker detection" (same activity as the camera view, so driving continues; STOP passed in as topControls).
  Detector side: `ConversationSnapshot.timeline: List<AudioFrame(timeMs, levelDb, probability, speech)>` (1500 frames = 15 s) and
  `events: List<ConversationEvent(timeMs, kind, ratio)>` with kinds CHANGE / CANDIDATE_REJECTED / RESET / MULTIPLE; collected only while
  `SpeakerChangeDetector.debugTimeline` is true, set by `ConversationMonitor.addDebugViewer/removeDebugViewer`; `ConversationMonitor.snapshot`
  mirrors the detector snapshot. Drawing: level line (−90…0 dBFS), Silero probability with its threshold, green background where the gate
  counted speech (white = cut out), coloured event marks; event list with seconds ago and ratio. Numbers only, no audio kept.
- **`robocontrol/assets/settings/settings.json` + `vision/DetectionSettings.kt`:** every ORB/pink value with a `note`, written as
  `{ "value": …, "note": "…" }` (plain values also accepted). `orb.general` = defaults for all references; `orb.images.<file name>` overrides
  single values for one reference (`enabled`, `usePinkMarker`, keypoints, FAST, levels, thresholds, homography, …). Whole-picture keys
  (pink block, workers, maxPassesPerSecond, maxRegions, consistency, cooldown, region scaling, orbMarginPx) are general only; naming them per
  image logs a warning and is ignored, as are unknown keys. Missing/broken file → built-in defaults + error log (tag DetectionSettings).
- **Wiring:** `CalendarDetectionSettings` is now a thin read-only bridge onto `DetectionSettings` (values are `get()`s, so an edited file
  takes effect on the next start). `ORB(context, config, configFor, skipReference)`: per-reference `OrbConfig` (own detector per
  features/FAST/levels combination, cached), `ObjectReference.config`, and `match()` uses the reference's own thresholds; assets with
  `enabled: false` are not loaded ("switched off in settings.json"). `markerGatedPass(thresholds: (String) -> Pair<Int, Int>, classNames)`.
  `CalendarMonitor.detectionPass` splits the references: pink-gated ones go through `markerGatedPass`, references with
  `usePinkMarker: false` are searched with `orb.evaluate` on the whole frame in the same pass.
- Texts file now 186 entries (speaker view + the new buttons).


## Navigation and Map on the phone (2026-09-21, compiled on both sides, NOT run on hardware)

- **`robocontrol/movement/NavigationHub.kt` (new):** the ONE `MapNavigation` of the process, owned by `RobotServerService`
  (`NavigationHub.start(applicationContext)` in onCreate, `stop()` in onDestroy). Holds the shared `NavigationLog` and
  `remoteControl: StateFlow<String?>` (name of the phone that sent the last command, cleared after `REMOTE_ACTIVE_MS` = 30 s).
  `MapNavigationActivity` now uses `NavigationHub.current` (own instance only as a fallback), **no longer stops the drive in
  onStop** (this closes the open issue "Navigation stops driving if I leave it"), and `startOnce()` only calls `probe.reload()`.
- **`robocontrol/movement/NavigationRoutes.kt` (new):** handlers `navigationStateJson()`, `navigationMapJson()`,
  `navigationMapPng()`, `navigationCommand(payload, client)` plus `ApplicationCall.refuseIfNotLocal()` / `isLocalAddress()`.
  Mounted in `RobotServerService`'s routing block with the EXISTING helpers `secureGet` / `securePost`, so authentication is
  unchanged (`requireClientAuth`: `X-Client-Id` + HMAC over the payload, empty string for GET). Routes: `GET /nav/state`,
  `GET /nav/map.json`, `GET /nav/map.png`, `POST /nav/command`.
  **Local-network check built and then REMOVED again (owner 2026-09-21: "is local is not really necessary right? Since the
  server only hosts on the local network anyways?" — correct):** the server binds `0.0.0.0:8443` for ALL its routes, so /nav
  is reachable exactly where /save and /capabilities already are; a "remote host must be private/link-local/loopback" test
  only restated that, and would have refused a phone in the same room on an IPv6 network (global addresses, no NAT).
  What keeps strangers out is the pairing (client id + HMAC).
  `POST /nav/command` actions: select, point, drive, stop, speed, clearPoints, saveLocation, deleteLocation, areaCircle,
  drawStart, drawUndo, drawCancel, drawFinish, deleteArea, revokeArea, answerPrivacy, reload, resetLocations, resetAreas.
  Every command answers with the NEW state plus `ok`/`message` (the robot's own refusal wording). `allowArea` deliberately
  does NOT exist: a crossing permission is only granted by answering the robot's question (`answerPrivacy`).
  State JSON: map, localized, sdkControl, navState, speed, measuredSpeed, remoteControl, now (robot clock), pose, selected,
  places, points, areas (name, allowedUntil, corners), privacyMargin, drawing, areasLoaded, the three store errors, question
  (id/area) and the last 40 log lines. map.json carries widthPx/heightPx/resolution/minX/maxX/minY/maxY (top row = highest y).
- Robot screen: blue bar `nav.label.remote_control` ("Steered from the phone app: …") while a phone is steering.
- **Phone app `~/AndroidStudioProjects/RoboGuardAndroidEnd` (owner: "no absolutely change the Phone app"):**
  - `RobotAPI.kt`: `NavCall` (Ok / Unreachable / Refused), `navState()`, `navMapInfo()`, `navMapImage()`, `navCommand(body)`,
    all signed with the existing `createSignature` + `ensureConnection()` (mDNS fallback). Any exception → `Unreachable`.
  - `NavigationModel.kt` (new): serializable state classes, `NavConnection`, `NavigationClient` (poll every 500 ms; 3 s while
    offline; map picture fetched once per map name; command answers replace the state at once).
  - `NavigationScreen.kt` (new): map picture with the robot's pose, places, tapped points, private areas (orange while
    temporarily allowed) and the area being drawn; tap = `point`; drive/STOP, speed presets, place list, save/delete position,
    area create/delete/revoke, the crossing question as a dialog, the robot's log, connection dot + offline banner with
    "Try again".
  - `MainActivity.kt`: green button "Navigation and Map" in StartUI → full-screen `NavigationScreen`.
  - The phone repo had **no `gradle/wrapper/gradle-wrapper.properties`** (only the jar); copied from this repo (Gradle 8.13)
    to be able to build it. `:app:compileDebugKotlin` passes on both sides.
  - Robot APK built and INSTALLED 2026-09-21 11:43 (110 MB debug, `adb install -r` Success), not yet run. The phone app is
    compiled but not installed by Claude.

## Phone app: texts JSON + debug switch (owner request 2026-09-21, compiled, NOT installed)

- `RoboGuardAndroidEnd/app/src/main/assets/texts/texts.json` (103 entries) + `roboguardandroid/UiText.kt` — same arrangement
  and same file format as the robot's `assets/texts/texts.json`: `"key": { "text": …, "note": … }` under a `texts` object,
  `{placeholder}` substitution, missing key → key shown + logged. `UiText.init(this)` in `MainActivity.onCreate`.
  Converted: MainActivity (pairing screen, sensor/situational/sleep sections, info + sync dialogs, all buttons),
  QRController (`isQRvalidScreen`, camera permission), NavigationScreen, NavigationModel (command answers).
- **Internal values stay values:** sensor/situational names come from the robot and are matched, not translated;
  the sleep durations are now `SLEEP_OPTIONS` ("Dont", "5 minutes", …, read by `parseSleepTimeToSeconds`) with
  `sleepTimeText(value)` for display only — translating them in the JSON does not break the phone↔robot protocol.
- `RoboGuardColors` (in UiText.kt): the app's colour code in one place — Header 0xFF1A73E8, Action (green) 0xFF4CAF50,
  Danger red, Good 0xFF2E7D32, Warn 0xFFEF6C00, Allowed 0xFFFF9100, Idle, OwnPoint 0xFF7B1FA2, error banner
  0xFFFFE5E5/0xFFB00020, Surface/LogBackground. NavigationScreen now uses those instead of its own hex values, wraps
  itself in `RoboGuardAndroidTheme` and reuses `HeaderAppName(title)` (that composable got an optional title parameter).
- **"Show debug" switch** at the bottom of the phone map screen (rememberSaveable), like the robot's: it hides the status
  lines (map, localized/SDK control, navigation state, position, who is steering) AND the "Show robot log" button + log.
  Always visible without debug: the connection banner, "navigation not running", and the three store errors — they are the
  reasons the robot refuses to drive.
- Key check: 103 keys, all used, none missing. `:app:assembleDebug` → 35 MB APK. NOT installed (the owner's Pixel 7a is
  attached over adb as `adb-43021JEHN04311-AZhd5D…`, but installing on their phone was not asked for).

## Situational settings gate the two detectors (owner 2026-09-21, installed, VERIFIED on the robot)

- Owner: "Discretion Mode" from the phone switches **conversation detection** on, "Pixelate Objects" switches **object
  detection** on; **neither may change whether the microphone or the camera are active** (that stays the sensor settings').
- `sensorcontrol/sensors.kt`: new `SituationalChangeListener`, `Sensors.situational: StateFlow<Map<String, Boolean>>`,
  `isSituationalEnabled(name)` (case-insensitive: the robot's own defaults write "pixelate objects", the phone
  "Pixelate Objects"), `updateSituational(map)` (**never calls SensorSwitches** — no hardware is touched),
  `addSituationalListener` / `removeSituationalListener`, `readSituationalFile()`. `update(settings: AppSettings)` now
  applies both parts, `reload()` likewise. **A setting the phone has never sent counts as OFF**, so nothing listens or
  watches by itself.
- `ConversationMonitor`: constant `DISCRETION_MODE`, situational listener, and `reevaluate()` refuses first with
  "Discretion Mode is off in the privacy settings". `CalendarMonitor`: same with `PIXELATE_OBJECTS` /
  "Pixelate Objects is off in the privacy settings". Both now log the reason once per change
  ("not listening: …" / "not watching: …" / "conditions met, …").
- Robot's `files/RoboSettings/privacy_settings.json` (VERIFIED 2026-09-21): sensors {Camera=false, Microphone=true,
  LIDAR=true}, situational {"Pixelate Objects": false, "Discretion Mode": false} — so after this install both detectors
  are OFF until the phone saves them as true. Confirmed in Logcat right after the install.
- Phone app (owner: comment out, do not delete): in `SensorCategory` the per-sensor room expansion is commented out —
  the arrow `Icon`, the `if (sensorExpanded) { … }` block with the room checkboxes, and the row's `.clickable`. The rooms
  are still built and still sent to the robot; only the way to fold them out is gone. Compiles; NOT installed.

## Calendar false positives in an empty room — cause and fix (2026-09-21, VERIFIED numbers, installed)

**Owner: "the robot is staring at the same place, detecting a calendar every few minutes, and there is no calendar in sight."**
Logcat (process 21464, "2/2 passes" mode, X = 20 / confirm 12) had two announcements ~2 min apart:
`20:49:07 good 51, inliers 24` and `20:51:14 good 19, inliers 14`. The per-check lines show what really happened:
```
check: frame 32 px, scale 8,00, keypoints  9, good 446, inliers 336
check: frame 42 px, scale 8,00, keypoints 35, good  95, inliers  81
check: frame 44 px, scale 8,00, keypoints 43, good  51, inliers  24   <- the announced one
```
- The accepted pink areas were **32–74 px** long side (the calendar on the wall measured ~310 px), always scaled by the full
  `maxRegionScale` 8.
- **good 446 with 9 frame keypoints is arithmetically impossible as a real match:** `match()` calls
  `batchDistance(reference.descriptors, frameDescriptors)`, so goodMatches counts REFERENCE features (~2000), each picking its
  two nearest neighbours among only 9 frame points. The ratio test then passes by chance and `findHomography` collapses
  hundreds of reference points onto a few frame points — a degenerate transform with a huge inlier count. No inlier threshold
  can filter that (336 > any threshold).
- Between the announcements: `regions 0,0 · rejected frame 20–24 · best inliers 0`, i.e. the room constantly produces small
  pink-ish blobs that the frame/white gate rejects, and every few minutes one slips through.

**Fix (installed 2026-09-21, three guards):**
1. `ORB.match`: **one frame keypoint may be claimed by at most one reference feature** (keep the closest). goodMatches can
   therefore never exceed the frame's keypoint count, which removes the degenerate case at the root.
2. `OrbConfig.minFrameKeypoints` (settings.json `minFrameKeypoints`, default **60**, per image possible): `evaluateGray`
   returns notFound for every reference when the frame/crop has fewer keypoints.
3. `markerGatedPass`: pink areas with a long side below `minRegionLongSidePx` (settings.json, general only, default **100**)
   are skipped entirely (`CalendarDetectionSettings.MIN_REGION_LONG_SIDE_PX`).

After the install (process 6916): `detected 0/23 · regions 0,0 · rejected frame 23 · best inliers 0` — the small blobs no
longer reach ORB at all, and no "check:" lines are produced for them. **Still to verify: that a real calendar at wall
distance is still detected** — it measured ~310 px, well above the 100 px floor, but that has not been re-tested.

## Voice fingerprinting: 3D-Speaker CAM++ + owner enrolment (owner request 2026-09-21, installed, not yet run on the robot)

Owner's design: the robot stores ONE voiceprint, its owner's; every other voice is only compared ("owner or not") and
forgotten. Change detection stays; a switch between the two methods is still to come.

- **Model:** `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` from the sherpa-onnx model release, shipped as
  `robocontrol/assets/speaker/campplus_en_voxceleb.onnx` (29.6 MB, sha256 357a834f702b8016…) with
  `README_LICENSE.txt` next to it (3D-Speaker toolkit Apache-2.0, weights trained on VoxCeleb = research use).
  Model I/O VERIFIED on the PC: input `x` float[N, T, 80], output `embedding` float[N, **512**] (not 192).
  APK 75 MB → **99 MB**.
- **`conversation/Fbank.kt` (new):** Kaldi-layout 80-dim log-mel fbank — DC removal, pre-emphasis 0.97, **Povey window**
  ((0.5−0.5cos)^0.85), FFT 512, power spectrum, 80 triangular mel filters 20 Hz–8 kHz (mel = 1127·ln(1+f/700)), natural
  log floored at the float epsilon, plus `cepstralMeanNormalise` (the 3D-Speaker pipeline subtracts the mean over time).
  This is a SECOND front-end next to `Mfcc.kt` (12 cepstral coefficients for the change detector); the models need the
  raw log-mel energies.
  **VERIFIED twice:** (1) the Python reference of the same algorithm + the ONNX model on sherpa's sample recordings gives
  same speaker 0.548 / 0.675, different speakers 0.087 / 0.102 / 0.236; (2) the Kotlin port compiled with kotlinc against
  the same wav matches the Python features to 4 decimals on every sampled row.
- **`conversation/SpeakerEmbedder.kt` (new):** ONNX Runtime wrapper in the `SileroVad` pattern (single-threaded session,
  `close()`); `embed(samples)` → L2-normalised FloatArray(512) or null below 1 s of audio; `similarity(a, b)` = dot
  product. Buffers zeroed after every call.
- **`conversation/OwnerVoiceprint.kt` (new):** `Voiceprint` can only be **compared** (`similarityTo`) and **erased**
  (`zero()`) — no getter, `toString` prints no values. `OwnerVoiceprintStore` (singleton) writes
  `files/robocontrol/voice/owner.print`, AES-256-GCM through `KeystoreZoneCipher("robocontrol_owner_voiceprint")` (own key,
  AAD `roboguard-voiceprint:owner.print`), temp file + fsync + rename; `info: StateFlow<VoiceprintInfo?>` exposes only
  statistics (date, pieces, seconds, dimensions, self-similarity mean/worst, threshold).
- **`conversation/VoiceEnrolment.kt` (new):** own thread, `AndroidMicSource` 16 kHz + `SileroVad` gate (p ≥ 0.5), keeps
  ONLY speech, one embedding per **3 s of speech**, target **30 s**, ≥ 4 pieces; template = normalised mean, pieces below
  0.45 similarity to the first centroid are dropped and the mean is taken again; `threshold = max(0.45, mean − 2σ)`.
  Test mode compares each piece with the stored template and publishes only the similarity. Takes the mic from
  `ConversationMonitor` via `setProbeUsingMicrophone`, zeroes every buffer in `finally`.
- **`conversation/VoiceEnrolmentView.kt` (new):** Navigation and Map → Show debug → **"Teach owner's voice"**
  (`nav.button.teach_voice`). Shows what is stored (date, pieces, seconds, dimensions, quality, threshold), records with a
  progress bar over SPEECH seconds, "Try the voice" with a similarity bar and the threshold marked, delete with
  confirmation, live Silero probability + level, and a privacy note. Asks for RECORD_AUDIO itself. 31 new text keys
  (`voice.*`, `nav.button.teach_voice`).
- NOT done yet: `OwnerVoiceDetector` (the detector that uses the template) and the debug switch between change detection
  and fingerprinting. The enrolment screen's "Try the voice" is what gives the numbers to set the threshold from.
