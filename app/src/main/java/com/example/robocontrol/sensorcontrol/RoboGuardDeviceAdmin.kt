package com.example.robocontrol.sensorcontrol

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin component. It lets [SensorSwitches] disable the camera through Android's
 * `DevicePolicyManager.setCameraDisabled`.
 *
 * It does nothing until activated once on the robot:
 *   adb shell dpm set-active-admin com.example.roboguard/com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin
 *
 * The only policy requested is `disable-camera` (res/xml/robocontrol_device_admin.xml).
 */
class RoboGuardDeviceAdmin : DeviceAdminReceiver()
