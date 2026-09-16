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

### robocontrol/voiceprobe (2026-09-15, compiled, NOT yet run on hardware)

Test code, not product code. `VoiceProbeActivity` (Compose) is started with
`adb shell am start -n com.example.roboguard/com.example.robocontrol.voiceprobe.VoiceProbeActivity`.
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
  run). Start with `adb shell am start -n com.example.roboguard/com.example.robocontrol.voiceprobe.TtsProbeActivity`.
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
   Also seen in the jar, unexplored: `RobotApi.delegateControl(String): Boolean`. Previous note, kept for history: In `/save`,
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
   **Hardware test (compiled 2026-09-15, not yet run):** `robocontrol/sensorprobe/SensorProbe.kt` + `SensorProbeActivity`
   (`adb shell am start -n com.example.roboguard/com.example.robocontrol.sensorprobe.SensorProbeActivity`).
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
   **Movement test (compiled 2026-09-16, not yet run):** `robocontrol/movementprobe/` (`MovementProbe`, `MovementProbeActivity`,
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
