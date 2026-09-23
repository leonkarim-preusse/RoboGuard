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

## 7. Texts and wording (2026-09-21)

- All texts the robot shows or says (product screens, pop-ups, spoken sentences) come from one file `assets/texts/texts.json`: one entry per text with the text itself and a note explaining where it appears.
- Reason: wording and language can be changed in one place, without touching code — useful for the user study (instructions, robot sentences) and for a German/English version.
- Loader `robocontrol/text/UiText.kt`: reads the file once, `UiText.get("key", "placeholder" to value)`; placeholders in curly braces stay readable in the file; missing key → the key name appears on screen, so a gap is visible.
- Speech: `meta.speechLanguage` in the same file picks the voice (de_DE / en_US) for the spoken sentences.
- Test/probe screens keep their hard-coded texts on purpose (developer tools, not part of the study).

## 8. Debug screens and settings file (2026-09-21)

- **Speaker detection debug screen** (Navigation and Map → Show debug → "Show speaker detection"): shows the last 15 s of microphone input — loudness, Silero's speech probability and, as a green background, the frames the gate counted as speech (white = what Silero cut out). Marks for counted voice changes, rejected candidates, conversation resets and "more than one speaker", plus the current numbers (evidence, KL2, speech seconds) and an event list.
  - The timeline is only collected while the screen is open; no audio is stored, only numbers per 10 ms frame.
  - Purpose for the thesis: makes visible why a conversation was (not) detected — especially what the neural speech gate discards.
- **Detection settings in `assets/settings/settings.json`**: all ORB and pink-marker values (keypoints, FAST, pyramid levels, thresholds, scaling, homography method, pink search, workers, consistency, cooldown) with a note per value.
  - `general` applies to every reference image; a section under `images` named after a reference file overrides single values for that image only (e.g. more keypoints, other thresholds, `enabled: false`, or `usePinkMarker: false` = search this picture in the whole camera image instead of inside pink frames).
  - Values that describe the whole camera picture (pink search, workers, passes per second) are general only; naming them per image is ignored and logged.
  - Reason: the user study needs different objects with different demands without code changes, and the thesis can report the exact settings of each run.
- Silero model moved to `assets/silero/` (own folder next to `texts/`, `settings/`, `ORB_img/`).

## 9. Navigation and Map on the phone (2026-09-21)

- Goal: the person should be able to see and steer the robot's "Navigation and Map" screen from the phone app — the same map,
  the same position, the same private areas, synchronized in both directions.
- **The navigation moved out of the screen into the service** (`movement/NavigationHub.kt`, started in `RobotServerService`):
  one `MapNavigation` for the robot screen and the phone, so both see the same state. Side effect: a drive no longer stops
  when the robot shows another screen (the open issue below is thereby solved); stopping is now a decision of the person
  (STOP on either screen) or of the privacy rules.
- **Robot server**: new routes `GET /nav/state`, `GET /nav/map.json`, `GET /nav/map.png`, `POST /nav/command`
  (`movement/NavigationRoutes.kt`). They are mounted with the server's EXISTING `secureGet` / `securePost` helpers, i.e.
  the same check as `/save`: client id plus an HMAC signature over the request body (empty string for GET). No second
  authentication mechanism was introduced.
- **Reach**: the routes are as reachable as the rest of the server, which binds all interfaces on port 8443 — in a flat that
  is the WiFi and nothing beyond it. An extra "caller must have a private address" check was built and removed again: it only
  repeated what the network already decides, and on an IPv6 network (global addresses, no NAT) it would have refused a phone
  standing in the same room. The protection is the pairing: without the client id and the HMAC signature over the payload,
  no call is accepted, wherever it comes from.
- **The phone decides nothing.** It sends the same commands the robot's own buttons send; targets in private areas are still
  refused by the robot, the in-motion check still stops it, and the "may I cross this area?" question can be answered on the
  robot screen or on the phone (whichever answers first). A phone that is out of range therefore cannot weaken the rules.
- **Visible state** (thesis argument): while the phone is steering, the robot's own screen shows a blue bar "Steered from the
  phone app: <phone name>" (30 s after the last command it disappears). Deleting a private area from the phone is written
  into the robot's event log.
- **Error handling on the phone**: the connection state is its own thing (`NavConnection`): connected / connecting /
  offline / refused. When the robot server cannot be found, the last known state stays on screen with a red banner naming
  the likely cause (same WiFi? robot switched on?), the age of the data and a "Try again" button; polling slows from
  0.5 s to 3 s instead of hammering the network. 403 and 401 are shown as their own wording ("only inside the local
  network", "pairing no longer valid").

## 10. Texts of the phone app (2026-09-21)

- The phone app now reads every displayed text from `assets/texts/texts.json`, the same arrangement the robot has since
  section 7: one entry per text with a note saying where it appears, placeholders in curly braces.
- Both ends of RoboGuard are therefore worded in one readable file each — relevant for the user study (the participants
  read the phone, not the code) and for a German version without touching Kotlin.
- What is deliberately NOT a text: sensor names (they come from the robot and are matched), and the sleep durations
  (they are turned into seconds for the robot). Translating them would break the protocol between phone and robot, so they
  stay values and only their labels are texts.
- The phone map screen got the robot's "Show debug" switch: status lines and the robot's event log sit behind it, while
  anything that explains why the robot refuses to drive (offline, not localized, unreadable private areas) is always
  visible. Colours follow the app's existing code (blue header, red for stopping and deleting, green for a yes),
  collected in one `RoboGuardColors` object so both screens say the same thing with the same colour.

## 11. Timing of the conversation question (2026-09-21)

- The hold before the conversation question (1 s of "more than one speaker") is switched off for now, so the question
  appears as soon as the detector says so. The remaining wait is the evidence rule inside the detector.

## 12. Situational settings decide what is detected (2026-09-21)

- The two situational switches of the phone app now have a real effect: **Discretion Mode** turns conversation detection
  on, **Pixelate Objects** turns object detection on. Off — or never sent — means the robot does not run that detection.
- Deliberately separate from the sensor switches: neither of them touches the microphone or the camera. A sensor says
  whether the robot may sense at all; a situational setting says whether a feature that uses it may run. So "microphone on,
  Discretion Mode off" is a legitimate state: the robot can still be spoken to, but it does not analyse conversations.
- Default is off. Nothing starts listening or watching until the phone has sent that setting as true, which fits the
  opt-out-by-default argument: a feature that processes what people say or what hangs on their wall has to be asked for.
- Both monitors log why they are not running ("not listening: Discretion Mode is off in the privacy settings"), so the
  state is inspectable instead of silent.
- Per-room sensor settings are hidden in the phone app for now (commented out, not removed): a sensor is set for the whole
  flat. The rooms are still sent to the robot, so the data model is unchanged.

## 13. A calendar in an empty room (2026-09-21)

- The robot announced a calendar every few minutes while standing still in a room that had none. The logs showed the cause:
  the pink gate occasionally let a 30-70 px speck through, and ORB then "matched" it — in one check 9 keypoints in the
  picture produced 446 matches and 336 agreeing points, which cannot happen in a real match.
- Why it can happen: the matching runs from the reference picture into the camera picture, so about 2000 reference features
  each pick their nearest neighbour among a handful of points; the usual distinctiveness test passes by chance, and the
  geometric fit then folds all of them onto those few points. The result looks like an extremely strong detection.
- Worth stating in the evaluation: a detection threshold ("how many points must agree") cannot defend against this, because
  the degenerate case produces MORE agreement than a real one, not less. The defences are structural: one point can only be
  matched once, a minimum amount of texture before matching at all, and a minimum size for the area that is searched.
- All three are now in place, with the two numbers (60 keypoints, 100 px) in settings.json so a study run can report them.

## 14. Voice fingerprinting: the owner's voice (2026-09-21)

- Decision (owner): the robot stores exactly ONE voiceprint — the owner's, who consents and can delete it. Every other
  voice is compared against it, labelled "not the owner" and immediately forgotten. Change detection stays in the code;
  the two methods will be switchable so the evaluation can compare them.
- Model: CAM++ from 3D-Speaker (Apache-2.0 toolkit, VoxCeleb weights, research use), 29.6 MB, run on the robot with ONNX
  Runtime. Nothing is sent anywhere. The app grew from 75 MB to 99 MB.
- The front-end had to be written from scratch (Kaldi-style 80-band log-mel), because the neural model needs different
  features than the change detector's MFCC. It was verified twice: against a Python reference with the model on sample
  recordings (same speaker 0.55/0.68, different speakers 0.09-0.24), and the Kotlin port against that reference,
  matching to four decimals.
- Enrolment: 30 seconds of SPEECH (pauses do not count), split into 3 s pieces, each turned into a pattern; pieces that
  do not fit the rest are dropped, so one cough or a second person in the room does not move the template. The decision
  threshold is derived from how consistent the owner's own pieces were, not guessed.
- What is stored, and what is not: only the averaged pattern, encrypted with its own key on the robot, deletable with one
  button. The recording is never written; other people's patterns exist for a fraction of a second in a buffer that is
  overwritten. The type holding the template offers no way to read it out — it can be compared and erased, nothing else.
- The screen shows all of it: when the voice was taught, how many pieces, how consistent they were, where the threshold
  sits, and a "try it" mode that displays the similarity live. That is the visibility the thesis argues for: a robot that
  recognises its owner should be able to say so, and be switched off again.

## 15. Why the voice comparison needed a cohort (2026-09-23)

- First measurement on the robot: the owner's own voice matched the stored pattern with 0.89–0.91, another person with
  0.8+, and the threshold the enrolment had computed was 0.94 — so the robot called its owner a stranger.
- Two lessons, both worth stating in the evaluation. First, a threshold derived from one recording measures how consistent
  that recording was, not how consistent a voice is; the spread within thirty seconds is far smaller than the spread across
  situations. Second, and more interesting: every recording made through one microphone in one room shares a large part
  that says nothing about the person. It lifts all similarities together — which is why everyone landed at 0.8 and above,
  while the same model on close-talk recordings separates 0.55–0.68 from 0.09–0.24.
- The standard answer is centring: record many other voices through the same microphone, keep only their average, and
  subtract it from both sides before comparing. What remains is the part that actually differs between people.
- Implemented so that nothing about those other people is kept: the robot records the voices, averages them, and stores
  only the average — one anonymous vector that matches no single person and cannot be traced back to one.
- The voices come from LibriSpeech (CC BY 4.0), played into the room from a phone so they pass through the robot's own
  microphone. A published dataset used this way costs nobody their privacy and makes the step reproducible for a reader.
- Both detection methods now run behind the same interface with a switch between them, so the thesis can report what the
  identification actually buys — speed and directness — against what it costs, which is a stored voiceprint of one person.

## 16. What the cohort actually measured (2026-09-23, numbers from the robot)

**The recording.** The playback file holds 40 speakers, three utterances each, in round-robin order so that a run stopped
early still covers every voice. The robot stops at 180 seconds of *speech* — pauses do not count — which took about three
and a half minutes of the eight-and-a-half-minute file and produced 60 pieces spanning all 40 speakers. Only their average
is stored.

**The effect, measured on the same voices before and after centring:**

| | similarity, centred | similarity, raw |
|---|---|---|
| owner | 0.34 – 0.51 | 0.894 – 0.924 |
| another person | −0.28 – 0.25 | 0.668 – 0.877 |
| a video (other voices only) | −0.16 – −0.33 | 0.713 – 0.807 |

Raw, the owner's lowest reading (0.894) and the other person's highest (0.877) are 0.017 apart — the two groups touch, and
no threshold separates them reliably. After subtracting the average of the other voices, the same recordings sit 0.09 apart
on a scale that now spans about 0.8. That is roughly a fivefold gain in margin, and it is the difference between a feature
that works and one that does not.

**Why this happens** is worth a paragraph in the thesis rather than a footnote: every recording made through one microphone
in one room shares a large component that describes the room and the microphone, not the person. It lifts all comparisons
together, which is why a model that separates 0.55–0.68 from 0.09–0.24 on close-talk recordings collapsed to "everyone is
above 0.8" here. Removing that shared part is standard practice in speaker verification, and doing it with a published
dataset played into the room keeps it reproducible and costs no one their privacy.

**The threshold was then set from the measurement**, not from a formula: 0.31, between the two clusters. The formula the
enrolment uses (mean − 2σ over the pieces of one recording) had produced 0.94 and would have rejected the owner every time,
because the spread within thirty seconds of one sitting says nothing about the spread across situations.

**Where the errors are: at the transitions, not in the steady state.** With only a video playing, six pieces in a row read
−0.16 to −0.33, correctly "not the owner". The piece that spanned the moment the video was stopped read 0.402 — an
embedding of a mixture lands between the two voices it is made of. This matters for the decision rule, which currently
needs two pieces to say "somebody else" but only one to say "owner": a single straddling piece can therefore invent an
"owner plus other" situation in a room where only a television is talking. Candidate fixes are symmetry (two owner pieces
too), shorter pieces, and a purity check that embeds both halves of a piece and discards it when they disagree.

**Two operational findings from the same session**, both worth stating because they shaped the design: an enrolment that is
stopped early stores nothing, while a cohort run stores whatever it has collected — the asymmetry is deliberate but
surprising, and it means a stray press plus twelve seconds of speech would replace the cohort. And a new cohort run
replaces the old one rather than adding to it, so mixing languages or sources has to happen inside a single run.

## Open issues / to adjust later

- ~~**Driving stops when the Navigation screen is left**~~ (solved 2026-09-21 by the service-owned navigation, see section 9).
- **Driving stops when the Navigation screen is left** (`MapNavigationActivity.onStop` → stop, on purpose: "a robot must never keep driving while nobody sees the screen that controls it"). Owner (2026-09-17): must be adjusted eventually, e.g. for popups, other RoboGuard screens or background tasks during a drive. Debug camera view was therefore built inside the navigation screen.

