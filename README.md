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
4. Start the probe screen:
   ```adb shell am start -n com.example.roboguard/com.example.robocontrol.voiceprobe.VoiceProbeActivity```
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
3. Start the TTS test screen:
   ```adb shell am start -n com.example.roboguard/com.example.robocontrol.voiceprobe.TtsProbeActivity```
4. The log must show ```OrionStarTts connected``` and ```raw SkillApi connected```. If it shows ```disabled```, the test screen is not in the foreground; bring it back to the front and restart it
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
5. Start the sensor test screen:
   ```adb shell am start -n com.example.roboguard/com.example.robocontrol.sensorprobe.SensorProbeActivity```
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
