package com.ians.korno.data.locationtracking.permission

interface LocationPermissionChecker {
    fun hasForegroundLocationPermission(): Boolean

    fun hasBackgroundLocationPermission(): Boolean

    fun hasActivityRecognitionPermission(): Boolean
}
