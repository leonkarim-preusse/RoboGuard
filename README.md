This is the source code for the robot application of RoboGuard. Check [here](https://github.com/leonkarim-preusse/RoboGuardAndroidEnd) for the smartphone application.

## Installation (Linux)
### Requirements
1. Android Studio is recommended; it already contains the Android Debug Bridge (adb) and a suitable JDK. Without Android Studio: ```sudo apt-get install adb``` and a JDK 17 or 21 (newer JDKs are not supported by the Gradle version used)
2. The robot (OrionStar GreetBot Mini) has adb switched off by default. Enable it on the robot: pull down the status bar with one finger, tap the time zone several times, enter the dynamic password (available from OrionStar support) and select "Enable debugging". "Persistent debugging" keeps it switched on after a reboot
3. Connect to the robot over USB or WiFi:
   - WiFi: ```adb mdns services``` shows the robot's address, then ```adb connect <ip>:5555```
   - ```adb devices``` lists the connected devices and their ids. Confirm the debugging dialog on the robot's screen the first time
4. The app is built for the robot's processor only (arm64-v8a); it does not run on other architectures

### Build and install
The app can be installed **with** or **without** the test screens. The test screens are additional launcher icons ("RG Voice Test", "RG TTS Test", "RG Sensor Test", "RG Movement Test", "RG Object Test", "RG Speaker Test") used to test single features on the robot. They are selected with the Gradle flag ```testScreens```:

| Variant | Command | Result |
|---|---|---|
| With test screens (default) | ```./gradlew installDebug``` or ```./gradlew installDebug -PtestScreens=true``` | RoboGuard plus the six "RG … Test" icons |
| Without test screens | ```./gradlew installDebug -PtestScreens=false``` | Only RoboGuard; the test screens are disabled and have no icon |

- With several devices connected, choose the robot with ```ANDROID_SERIAL=<id> ./gradlew installDebug ...```
- In Android Studio, "Run" builds the default variant (with test screens). To build without them, add the line ```testScreens=false``` to ```gradle.properties``` and run again
- Alternatively build an APK with ```./gradlew assembleDebug -PtestScreens=<true|false>``` and install it with ```adb -s <id> install -r app/build/outputs/apk/debug/app-debug.apk```
- If ```./gradlew``` is not executable, use ```bash ./gradlew```

### First start on the robot
1. Start RoboGuard by tapping its icon on the robot's home screen. RobotOS only gives apps started from its home launcher control over the robot (speech, navigation, sensors); apps started with ```adb shell am start``` are ignored by the robot SDK. On its first start RoboGuard makes itself the robot's default app, so it starts automatically after a reboot
2. Allow the microphone permission when asked, or beforehand: ```adb shell pm grant com.example.roboguard android.permission.RECORD_AUDIO```
3. To let RoboGuard switch the camera off through Android, make it a device admin once: ```adb shell dpm set-active-admin com.example.roboguard/com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin```
4. Allow storage access when "Navigation and Map" asks for it; the robot's map is read from its storage
5. A map has to be created with the robot's own map tool before navigation and private areas can be used
6. Pair the phone app by scanning the QR code shown on the robot's screen

## Troubleshooting
1. Uninstall: ```adb uninstall com.example.roboguard```
2. Connection issues: ```adb kill-server``` ```adb start-server```
3. The robot does not speak or move: RoboGuard was not started from the robot's home launcher. Close it and start it again from its icon
4. Debug mode: tapping the header line of RoboGuard's start screen 7 times switches it on. "Navigation and Map" then shows status information, the detection debug views and the switch "Disable RoboGuard" (stops RoboGuard and keeps it from starting at boot)

## Features
RoboGuard runs on the robot and enforces the privacy settings chosen in the phone app.
- **Server and pairing:** an HTTPS server on port 8443 that the phone app pairs with through a QR code and a one-time password. Every request is signed. The phone sends the privacy settings, which are stored on the robot. This code was written before the thesis.
- **Sensor control:** camera, microphone and LIDAR are switched on or off through RobotOS and Android when the phone saves the settings; the phone is told whether every switch was confirmed
- **Conversation detection (Discretion Mode):** when the microphone picks up a conversation, the robot asks whether it should leave the room, mute its microphone or not ask again for a while
- **Object detection (Pixelate Objects):** the robot recognises a marked calendar (or other reference images) in its camera image and asks what to do with it
- **Navigation and private areas:** the robot's map with its position, places and saved locations, on the robot's screen and in the phone app. Private areas can be drawn on the map; the robot does not enter them and asks for permission when a route leads through one
- All texts on screen and all spoken sentences are in ```assets/texts/texts.json```, all object detection settings in ```assets/settings/settings.json```

All Code under ```java/com/example/robocontrol``` was written for the thesis. The only preexisting code is under ```java/com/example/robocontrol```, although some adjustments with regard to the server routes were made there as well. Consult the thesis itself for more detailed information.
