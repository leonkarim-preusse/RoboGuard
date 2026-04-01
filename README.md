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
