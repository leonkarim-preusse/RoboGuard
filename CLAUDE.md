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
- **Results go here once run on the robot.**
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
- **Results go here once run.**

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
   **RobotServerService hook: described to the owner, NOT applied by Claude (owner applies it).** In `/save`,
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
   **Results go here once run.**
   **Ways to reach RobotServerService members from robocontrol:** (a) bind: `LocalBinder.getService()`
   already exists, and MainActivity.kt:52–69 does `startForegroundService` + `bindService(BIND_AUTO_CREATE)` and
   gets the instance in `onServiceConnected` (no RoboGuard change needed); (b) a companion-object instance
   reference set in onCreate and cleared in onDestroy (RoboGuard change); (c) move settings logic to
   top-level `fun …(context)` functions (RoboGuard change, cleanest).
   Casing mismatch: defaults use situational key `"pixelate objects"`, capabilities use `"Pixelate Objects"`.
   The actual default sensors are only Camera/LIDAR/Microphone (the DTO note above also lists Ultrasonic/Collision).
4. The map must still be built and zones defined before anything is enforced.
5. **IDE shows ~100 errors in `app/build.gradle.kts`** (2026-09-14, via Android Studio
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
