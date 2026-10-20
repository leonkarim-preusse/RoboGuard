package com.example.robocontrol.sensorcontrol

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin component that allows [SensorSwitches] to disable the camera with `DevicePolicyManager.setCameraDisabled`.
 * Must be activated once: `adb shell dpm set-active-admin com.example.roboguard/com.example.robocontrol.sensorcontrol.RoboGuardDeviceAdmin`.
 */
class RoboGuardDeviceAdmin : DeviceAdminReceiver()
