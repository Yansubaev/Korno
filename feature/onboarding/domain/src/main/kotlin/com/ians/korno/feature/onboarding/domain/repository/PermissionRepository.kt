package com.ians.korno.feature.onboarding.domain.repository

import com.ians.korno.feature.onboarding.domain.model.PermissionType

interface PermissionRepository {
    fun isGranted(type: PermissionType): Boolean

    /**
     * Разрешение, которого системный диалог не выдаёт вовсе: единственный работающий
     * путь ведёт на экран настроек приложения.
     *
     * Так устроена фоновая геолокация начиная с Android 11 — запрос из приложения там
     * просто возвращает отказ, и предлагать пользователю кнопку «Разрешить» значит
     * предлагать ему ничего.
     */
    fun isSettingsOnly(type: PermissionType): Boolean
}
