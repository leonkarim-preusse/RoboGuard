This is the source code for the robot application of roboguard. Check [here](https://github.com/leonkarim-preusse/RoboGuardAndroidEnd) for the smartphone application.
## Installation (Linux)
0. Android Studio is recommended, it already intergrates the Android Debugging Bridge
1. install Android Debugging Bridge ```sudo apt-get install adb```
2. On your target device enable Developer Options (usually tapping "Build Number" 7 times, untill it says " You are now a developer")
   -> Enable USB Debugging or Wireless Debugging afterwards and connect the target device (consult https://developer.android.com/tools/adb?hl=eng for further information)
3. if you are using Android Studio, the wireless connection can be established from within Android Studio and your target device will be shown as a potential target to run the app on
4. Run the app with your target device chosen over Android Studio or generate an apk using android studion and install it on your target device:
   a) open a terminal
   b) ```adb devices``` -> you will see the id of your target device, needed if you are connected to multiple devices
   c) ```adb -s <id> install -r path/to/your/app.apk```

## Troubelshooting
1. Uninstall: ```adb uninstall com.example.roboguard```
2. Connection issues: ```adb kill-server``` ```adb start-server```

## Documentation
1. ```Praktikumsbericht_fixed``` contains detailed explanations (in german)
2. All source code is found under ```~/app/src/main/java/com/example/roboguard``` and documented in the Code
3. ```Main Activity.kt``` controls the UI and ```RobotServerService``` controls the server therefore they control the application flow

## Testing the voice probes
The voice probes under ```app/src/main/java/com/example/robocontrol/voiceprobe``` are hardware tests for detecting whether more than one person is speaking. They answer three questions before the detector is built:
1. Can the app record audio itself while the robot's own speech service is running, and how many real microphones does it get?
2. What does the robot's speech service report to an app (voice activity, volume, "multiple mode")?
3. Does the robot report the direction a voice came from?

The probes only use audio and sound direction, never the camera. No audio is saved. The log file contains no transcripts, only the length and format of what the speech service sent.

### Preparation
1. Install the app on the robot as described in the Installation section above (a debug build is needed to read the log file later)
2. Grant the microphone permission, either when the probe screen asks for it or beforehand:
   ```adb shell pm grant com.example.roboguard android.permission.RECORD_AUDIO```
3. Test in a quiet room with two people available. Write down where the robot stands and where the people stand
4. Start the probe screen by tapping the **RG Voice Test** icon on the robot's home screen (swipe to the next page if needed). Do not start it with ```adb shell am start```: RobotOS only lets apps started from its home launcher use the robot SDK
5. Keep the probe screen in the foreground for the whole test. The robot only serves its SDK to the app in front, so if another app or popup appears, the results of probes 2 and 3 are not valid

### Test 1: Mic access (run this first)
1. Press **1 Mic access**. The run takes about 15–25 seconds
2. While it runs, keep talking normally at about 1 m from the robot. After the run, say the wake word once and check whether the robot still reacts
3. Read the result in the log:

| Log output | Meaning | Consequence |
|---|---|---|
| ```RESULT: no source delivered signal``` | The robot's speech service holds the microphone | Detection from raw audio is not possible, only tests 2 and 3 matter |
| a source works, all ```corrToCh0``` are about ```1.000``` | One microphone, copied into several channels | Speaker-change detection works, but direction has to come from the robot (test 3) |
| ```distinct≈``` greater than 1 | Several real microphones are available | The app could calculate the sound direction itself |
| robot no longer reacts to the wake word after the run | The recording interferes with the speech service | Note it; the detector must not block the robot's voice control |

### Test 2: Speech service
1. Press **2 Speech service**. The log must show ```connected, registering callback```. If it shows ```disabled```, the probe screen is not in the foreground or the app lacks permission
2. Press **TTS status** once while the robot is silent and once while it is talking, and note both values
3. Go through these three situations, about 30 seconds each, and press **Clear screen** in between so each situation is easy to read:
   a) one person talks to the robot
   b) two people take turns talking
   c) two people talk over each other
4. Press **Toggle multiple mode** and repeat a) to c)
5. For each situation, note which callbacks appear (```onVadMuteTime```, ```onVolumeChange```, ```onSpeechStreamData```, ```onGetMultipleModeInfos```, ...) and how their length and format differ between a), b) and c)

### Test 3: Sound direction
1. Press **3 Sound direction**. The log must show ```RobotApi connected```
2. Say the wake word from the front, left, right and behind the robot, at about 1.5 m, and wait for the robot to react each time
3. Check whether an angle appears (in ```onSendRequest```, in a ```broadcast``` line or in ```status status_speaker```) and whether it matches where you stood
4. Talk from different sides without the wake word and check whether any direction is reported at all
5. Press **Stop listeners** when done

### Getting the results
The log file name is shown in the first line on the probe screen. To copy it to your computer:
1. ```adb shell run-as com.example.roboguard ls files/voiceprobe```
2. ```adb exec-out run-as com.example.roboguard cat files/voiceprobe/<file name> > probe.log```

Add the findings to ```CLAUDE.md``` under "robocontrol/voiceprobe", noting which test they came from and the date.

## Testing text-to-speech
```app/src/main/java/com/example/robocontrol/audio/OrionStarTts.kt``` lets the robot speak English and German sentences through the OrionStar SDK. The TTS test screen checks on the robot that this works, and answers two open questions: whether a German voice is installed, and which language code format the robot expects.

### Preparation
1. Install the app on the robot as described in the Installation section above
2. Turn the robot's volume up and stand within about 1 m of it
3. Start the TTS test screen by tapping the **RG TTS Test** icon on the robot's home screen (swipe to the next page if needed). Do not start it with ```adb shell am start```: RobotOS only lets apps started from its home launcher use the robot SDK, and every speech command would be silently ignored
4. The log must show ```OrionStarTts connected```, ```raw SkillApi connected``` and **```SDK control active: yes```**. If it says ```SDK control active: NO```, close the screen and start it again from the home launcher icon
5. Keep the test screen in the foreground for the whole test

### Running the tests
Run the tests in order. Only one test runs at a time. After every test that ends with "Press ✔ or ✘", press **✔ As expected** or **✘ Wrong**; your answer is written into the log file.

| Button | What happens | Expected | If not |
|---|---|---|---|
| **0 Say sentences** | The robot says three English and then three German sentences, one after another | All six sentences spoken completely; each log line shows ```-> finished``` | Nothing audible: check the volume and that the log says ```connected```. German sounds English: continue with tests 1 and 3 |
| **1 Voices** | Lists the installed voices for English and German | A non-empty result for both | Empty or ```null``` for German: no German voice is installed, so tests 2 German and 3 will not sound German |
| **2 English** | Speaks one English sentence | Clear English speech; ```RESULT: finished``` | ```failed``` or no callback: note the log lines |
| **2 German** | Speaks one German sentence with ü, ö and ß | A German voice with correct umlauts | English pronunciation of German words: the robot ignored the language code, run test 3 |
| **3 German (code 7)** | Same German sentence, but with the numeric language code ```7``` instead of ```de_DE``` | Compare with 2 German | Only this one sounds German: ```OrionStarTts``` has to be changed to the numeric code |
| **4 Validation** | Tries blank text, text that is too long and text that looks like JSON | Three ```PASS``` lines and **no sound** | Any ```FAIL``` line, or anything audible |
| **5 Interrupt** | Starts a long sentence and stops it after 1.5 s | Speech actually stops; ```PASS: result Interrupted``` | Voice keeps talking, or ```FAIL``` |
| **6 Overlap** | Sends a second sentence while the first is still speaking | No right answer, this documents the robot's behaviour | Read the order of the log lines: A interrupted then B = interrupts; A finished then B = queues; B failed = rejected |
| **7 Play status** | Reads the speaking status every 0.3 s before, during and after a sentence | No right answer | Note which status values only appear between ```started``` and ```finished``` |

**Stop** cancels the running test and silences the robot. **Clear screen** clears only the visible log.

### Getting the results
Same as for the voice probes: the log file name is shown in the first line on the screen.
1. ```adb shell run-as com.example.roboguard ls files/voiceprobe```
2. ```adb exec-out run-as com.example.roboguard cat files/voiceprobe/<file name> > tts.log```

Add the findings to ```CLAUDE.md``` under "TTS", especially the answers to test 1 (German voice installed?), test 3 (code name or numeric code?), test 6 (queue or interrupt?) and test 7 (status values while speaking).

## Testing sensor switching
```app/src/main/java/com/example/robocontrol/sensorcontrol``` switches the robot's sensors according to the privacy settings: LIDAR through the OrionStar SDK, and the microphone and camera through both the SDK and Android. The sensor test screen checks on the robot what each switch really does.

Every test switches one sensor and then checks three things:
1. **Report**: did each SDK or Android call succeed? (```CONFIRMED``` = read back and correct, ```SENT``` = accepted but cannot be read back, ```FAILED```, ```PENDING``` = SDK not connected yet)
2. **Read-back**: what the SDK and Android report afterwards
3. **Effect**: is a test recording silent, does a camera snapshot fail or come back black, does the LIDAR visibly stop

The tests only change the settings in memory, never the saved settings file. Closing the test screen switches everything back to the saved settings.

### Preparation
1. Install the app on the robot as described in the Installation section above
2. **Put the robot in a safe spot where it cannot drive off**: without LIDAR it cannot see obstacles
3. Grant the microphone permission (the test screen also asks for it):
   ```adb shell pm grant com.example.roboguard android.permission.RECORD_AUDIO```
4. For the Android camera switch, make the app a device admin once:
   ```adb shell dpm set-active-admin com.example.roboguard/com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin```
   Without it, the Android camera switch reports ```FAILED``` with this command as the reason; all other switches still work
5. Start the sensor test screen by tapping the **RG Sensor Test** icon on the robot's home screen (swipe to the next page if needed). Do not start it with ```adb shell am start```: RobotOS only lets apps started from its home launcher use the robot SDK
6. Keep the test screen in the foreground for the whole test

### Running the tests
Run the tests in order. After every test that ends with "Press ✔ or ✘", press **✔ As expected** or **✘ Wrong**; your answer is written into the log file.

| Button | What to do | Expected | If not |
|---|---|---|---|
| **1 Status** | Nothing; wait a few seconds after opening the screen first | ```RobotApi connected: true```; all read-backs listed | ```connected: false```: wait and press again. ```device admin active: false```: see preparation step 4 |
| **2 Microphone ON** | Talk or clap during the 1 s recording | ```PASS```, recording has signal | ```FAIL``` (silent) here means the app cannot record at all; the OFF test below then proves nothing. Check with the voice probes' Mic access test |
| **3 Microphone OFF** | Talk or clap during the recording, then say the wake word | ```Android ... CONFIRMED```, ```PASS``` (silent), robot does not react to the wake word | Note which of the two switches reported what, and whether the wake word still worked |
| **4 Camera ON** | Stand in front of the robot | Snapshot with normal brightness, ```PASS``` | Snapshot fails while ON: note the reason (e.g. ```CameraBusy```) |
| **5 Camera OFF** | Step in front of the robot afterwards | ```stopVision ... CONFIRMED```, ```PASS``` (blocked or black), no reaction to your face | Snapshot still works: the switches do not block the shared camera stream. Note which reports say ```CONFIRMED``` |
| **6 LIDAR OFF** | Look at the LIDAR on the robot's base | ```updateRadarStatus ... CONFIRMED```, radar status closed, LIDAR visibly stopped | LIDAR keeps running despite ```CONFIRMED```: the SDK call does not really switch it off |
| **7 LIDAR ON** | Look at the LIDAR again | ```CONFIRMED```, LIDAR running; the robot may need to relocalize | LIDAR stays off: restart the robot before driving it |

Finally press **Restore saved settings** (or close the screen).

### Testing the full path from the phone app
Only works once ```RobotServerService``` calls ```Sensors.get(applicationContext).update(settings)``` in its ```/save``` handler.
1. Keep the sensor test screen open on the robot
2. In the phone app, switch off one sensor and save
3. The log must show ```settings change applied: <sensor>=OFF```; then press **1 Status** to see the reports
4. Switch it back on in the phone app and save again

### Getting the results
Same as for the other probes: the log file name is shown in the first line on the screen.
1. ```adb shell run-as com.example.roboguard ls files/voiceprobe```
2. ```adb exec-out run-as com.example.roboguard cat files/voiceprobe/<file name> > sensors.log```

Add the findings to ```CLAUDE.md``` under "Sensor on/off switches": for each sensor and each method (SDK or Android), whether it really switched the sensor.

## Testing movement
```app/src/main/java/com/example/robocontrol/movementprobe``` is a test screen for driving the robot: it shows the robot's current map to scale, the robot's live position, the places saved in RobotOS, and points you add by tapping the map. You can then let the robot drive to any of them.

### Preparation
1. A map must exist and be active on the robot (created with the robot's map tool). The robot must know where it is on that map (localized)
2. **Clear the area the robot will drive through** and stay close enough to press STOP
3. Start the test screen by tapping the **RG Movement Test** icon on the robot's home screen. Do not start it with ```adb shell am start```: RobotOS only lets apps started from its home launcher use the robot SDK
4. Allow storage access when asked: the map is read from the robot's storage

### The screen
- **Left**: the red **STOP** button, status (map name, SDK control, localized, robot position, navigation state), **Drive to selected**, **Reload map**, **Clear my points**, and all points as a list
- **Right**: the map (grey = unknown, white = free, dark = walls) with the robot (green circle, the line shows its heading), saved places (blue) and your points (orange); the selected point has a red ring. The log is below the map

### Running the tests
| Step | What to do | Expected | If not |
|---|---|---|---|
| **1 Check status** | Wait a few seconds after opening | ```SDK control: yes```, ```Localized: yes```, the map shows, the green robot sits where the robot really is | ```SDK control: NO```: restart the screen from the home icon. ```Localized: NO```: relocalize in the map tool. Robot in the wrong spot on the map: note it, the map orientation or position conversion may be wrong |
| **2 Move the robot by hand** | Push or turn the robot a little (or drive it with the map tool) | The green circle and its heading line follow within a second | Note which direction is mirrored or rotated |
| **3 Drive to a saved place** | Tap a blue place in the list, then **Drive to selected** | The robot drives there; log ends with ```ARRIVED``` | Note the ```FAILED: ...``` reason from the log |
| **4 Drive to your own point** | Tap an open, reachable spot on the map (it becomes P1 and is selected), then **Drive to selected** | The robot drives to that spot and the green circle ends on the orange point; ```ARRIVED``` | Robot drives somewhere else: the tap-to-position conversion is off |
| **5 STOP** | Start a drive, then press **STOP** | The robot stops immediately; log shows ```STOP``` | The robot keeps driving: press the robot's emergency stop |
| **6 Unreachable point** | Tap a spot inside a wall or outside the map, then drive | Navigation fails with a clear reason (e.g. ```Unreachable```) and the robot does not move | Note what happened |
| **8 Save a location** | Drive or push the robot to a spot, check ```Localized: yes```, press **Save current position…**, enter a name, **Save** | The name appears under "My locations" (purple on the map, at the robot's position) and is selected; closing and reopening the screen keeps it | The button is greyed out: the robot is not localized. An error in the dialog explains why saving was refused |
| **9 Drive to a saved location** | Move the robot away, select your location, **Drive to selected** | The robot returns to the saved spot; ```ARRIVED``` | Note the log |
| **7 No-go zone** | If the map has no-go lines, place a point behind one and drive | RobotOS refuses or drives around, never through the zone | Note it |

Leaving the screen (home button, another app) stops any navigation.

### No-go line test (changes the robot's map)
RoboGuard can write no-go lines into the robot's map the same way RobotOS's map tool does; the robot's own route planner then avoids them. This is the basis for private areas. Existing no-go lines are shown in **blue** on the map.

**Before the first write, make a backup on your PC** (it was done once on 2026-09-16 into ```~/RoboGuard_map_backups/```):
```adb pull "/sdcard/robot/map/RoboGuard Lab-0916110443/." ~/RoboGuard_map_backups/RoboGuard_Lab_<date>/```
RoboGuard also keeps its own copy of the original map image before its first change.

| Step | What to do | Expected | If not |
|---|---|---|---|
| **1 Choose A and B** | Select the charging point in the list, press **A = selected**; select the Empfangsstelle, press **B = selected** | ```A: …  B: …``` shows both | |
| **2 Preview** | **Preview line across A–B** | A **red** line crosses the room halfway between A and B, from wall to wall. Nothing is written yet | The line ends in the open or cuts through a place: do not write, note the log |
| **3 Baseline drive** | Drive the robot to A, then to B (before writing) | Robot drives directly to B | |
| **4 Write** | **Write no-go line to robot map…** → **Write** | The dialog shows ```no-go line written```; after a moment the line appears **blue** on the map. The log shows ```setMapForbidLineFlag … answered``` and ```setMapUpdateTime … answered``` | Note the error in the dialog and log |
| **5 Observe** | Drive the robot back to A, then to B | The robot does not cross the line: it detours around it if there is a way, or navigation fails (e.g. ```Unreachable```) | The robot drives straight through: RobotOS may only load the change after reloading the map. Try switching the map in the map tool (or restarting the robot), relocalize, and repeat step 5. Note what was needed |
| **6 Restore** | **Restore original map…** → **Restore** | ```original map restored```; the red/blue line from step 4 disappears after reload | See "Restoring from the PC backup" below |

**Result (2026-09-16):** lines written by RoboGuard were ignored by RobotOS (the robot drove through), while lines drawn by hand in the map tool worked (navigation failed with ```The global is path search failed```). Privacy areas are therefore enforced by RoboGuard itself, see below.

### Privacy area test
RoboGuard enforces private areas itself: it refuses drives whose target lies in a private area, and stops a drive as soon as the robot comes into or close to a private area on the way. In both cases the robot says: *"Weg führt durch privaten Bereich. Ich halte an und fahre nicht weiter."*

| Step | What to do | Expected | If not |
|---|---|---|---|
| **1 Create the area** | Select **Empfangsstelle** in the list, press **Private area around selected** | A red filled circle (1 m) with an outer ring (+0.5 m margin) appears around the Empfangsstelle; the log shows ```PRIVATE area "Privat: Empfangsstelle"``` | |
| **2 Target inside** | With Empfangsstelle still selected, press **Drive to selected** | The robot does not move; it says the sentence; log: ```REFUSED``` | The robot drives: note the log |
| **3 Route through the area** | Tap a point on the far side of the red circle (so the direct path crosses it), then **Drive to selected** | The robot starts, stops at the outer ring, says the sentence, then **turns on the spot** (it does not drive) to face away from the area; log: ```ABORTED at (x, y)```, ```turn away: heading …° → …°```, ```turn away finished, heading now …°``` | The robot drives through: note how far into the circle it got. It turns the wrong way (towards the area): note both headings from the log, the turn direction may be inverted |
| **3b Leave again** | Right after step 3, select a point away from the area (e.g. where the robot came from) and drive there | The robot drives away normally, although it started close to the area | The robot is stopped again straight away: note the log |
| **4 Route around the area** | Choose a target whose path does not cross the circle | Normal drive, ```ARRIVED``` | |
| **5 Clear** | **Clear private areas**, then repeat step 2 | The robot drives to the Empfangsstelle | |
| **6 Draw an area** | Tap the map to open full screen, press **Draw private area**, tap the corners of an area one after another (zoom in first if needed), press **Finish area** | While drawing, red corners and lines appear; after Finish the area is filled red with its margin ring and listed as ```Privat: Bereich 1```. Drives into or through it behave like steps 2 and 3 | **Undo corner** removes the last corner, **Cancel** discards the drawing |

Private areas are kept only while the screen is open. While driving, the robot checks its position every 0.15 s, so at *Slow* speed it can move about 4 cm past the ring (plus braking distance) before it stops.

#### Restoring from the PC backup
If the app cannot restore the map (or the map is broken), copy the original image back from the PC, then reload the map in the map tool or restart the robot:
```adb push ~/RoboGuard_map_backups/<backup folder>/navi_data/map.pgm "/sdcard/robot/map/RoboGuard Lab-0916110443/navi_data/map.pgm"```

### Getting the results
Same as for the other test screens: the log file name is shown in the first log line.
1. ```adb shell run-as com.example.roboguard ls files/voiceprobe```
2. ```adb exec-out run-as com.example.roboguard cat files/voiceprobe/<file name> > movement.log```

Add the findings to ```CLAUDE.md``` under "Current state / known issues", item 4 (map).
