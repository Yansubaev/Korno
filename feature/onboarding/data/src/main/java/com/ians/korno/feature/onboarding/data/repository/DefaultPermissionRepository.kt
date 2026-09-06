package com.ians.korno.feature.onboarding.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ians.korno.data.locationtracking.permission.LocationPermissionChecker
import com.ians.korno.feature.onboarding.domain.model.PermissionType
import com.ians.korno.feature.onboarding.domain.repository.PermissionRepository

internal class DefaultPermissionRepository(
    private val context: Context,
    private val locationPermissionChecker: LocationPermissionChecker,
) : PermissionRepository {

    override fun isGranted(type: PermissionType): Boolean = when (type) {
        PermissionType.FOREGROUND_LOCATION -> locationPermissionChecker.hasForegroundLocationPermission()
        PermissionType.BACKGROUND_LOCATION -> locationPermissionChecker.hasBackgroundLocationPermission()
        PermissionType.ACTIVITY_RECOGNITION -> locationPermissionChecker.hasActivityRecognitionPermission()
        PermissionType.POST_NOTIFICATIONS -> hasPostNotificationsPermission()
    }

    private fun hasPostNotificationsPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
}
