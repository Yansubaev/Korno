package com.ians.korno.feature.onboarding.domain.repository

import com.ians.korno.feature.onboarding.domain.model.PermissionType

interface PermissionRepository {
    fun isGranted(type: PermissionType): Boolean
}
