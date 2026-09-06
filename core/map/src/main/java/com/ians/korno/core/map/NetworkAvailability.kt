package com.ians.korno.core.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.getSystemService

/**
 * Есть ли сейчас сеть, пригодная для загрузки тайлов.
 *
 * Нужна не ради красивой надписи: когда связь возвращается, карта должна
 * перезагрузить стиль сама — пользователь не обязан догадываться, что экран
 * надо открыть заново.
 */
@Composable
internal fun rememberNetworkAvailability(): State<Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = remember { mutableStateOf(context.hasInternet()) }

    DisposableEffect(context) {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        if (connectivityManager == null) {
            state.value = true
            return@DisposableEffect onDispose { }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                state.value = true
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                state.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }

            override fun onLost(network: Network) {
                state.value = context.hasInternet()
            }
        }

        // Регистрация может не пройти на устройстве без сетевого стека —
        // тогда считаем, что сеть есть, и полагаемся на ошибку загрузки стиля.
        val registered = runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.isSuccess
        if (!registered) state.value = true

        onDispose {
            if (registered) runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    return state
}

private fun Context.hasInternet(): Boolean {
    val connectivityManager = getSystemService<ConnectivityManager>() ?: return true
    val capabilities = runCatching {
        connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    }.getOrNull() ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
