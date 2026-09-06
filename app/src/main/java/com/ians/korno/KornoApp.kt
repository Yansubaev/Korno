package com.ians.korno

import android.Manifest
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.util.Consumer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.feature.activitydetail.presentation.ActivityDetailRoute
import com.ians.korno.feature.activitydetail.presentation.activityDetailGraph
import com.ians.korno.feature.activitylist.presentation.ActivityListRoute
import com.ians.korno.feature.activitylist.presentation.activityListGraph
import com.ians.korno.feature.home.presentation.HomeRoute
import com.ians.korno.feature.home.presentation.homeGraph
import com.ians.korno.feature.onboarding.presentation.OnboardingRoute
import com.ians.korno.feature.onboarding.presentation.onboardingGraph
import com.ians.korno.feature.settings.presentation.SettingsRoute
import com.ians.korno.feature.settings.presentation.settingsGraph
import com.ians.korno.feature.tracking.domain.navigation.TrackingDeepLinks
import com.ians.korno.feature.tracking.presentation.PendingSummaryEffect
import com.ians.korno.feature.tracking.presentation.TrackingRecordingRoute
import com.ians.korno.feature.tracking.presentation.TrackingSetupRoute
import com.ians.korno.feature.tracking.presentation.TrackingSummaryRoute
import com.ians.korno.feature.tracking.presentation.trackingGraph
import org.koin.androidx.compose.koinViewModel

@Composable
fun KornoApp(
    modifier: Modifier = Modifier,
    viewModel: KornoAppViewModel = koinViewModel(),
) {
    val appState by viewModel.state.collectAsStateWithLifecycle()
    if (!appState.isReady) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val locationPermissionDeniedMessage = stringResource(R.string.location_permission_required)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        viewModel.onForegroundPermissionResult(results.values.any { it })
    }

    LaunchedEffect(viewModel, navController) {
        viewModel.events.collect { event ->
            when (event) {
                KornoAppEvent.RequestForegroundLocation -> permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
                KornoAppEvent.LocationPermissionDenied -> {
                    snackbarHostState.showSnackbar(locationPermissionDeniedMessage)
                }
                is KornoAppEvent.OpenTrackingSetup -> {
                    navController.navigate(TrackingSetupRoute(event.activityType))
                }
            }
        }
    }
    // Диплинк из стартового интента NavHost разбирает сам, но тап по уведомлению при уже
    // живой Activity приходит в onNewIntent — туда NavHost не смотрит, отдаём интент вручную.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, navController) {
        if (activity == null) return@DisposableEffect onDispose { }
        val listener = Consumer<Intent> { intent ->
            // Пользователь уже на экране записи — обрабатывать нечего, а handleDeepLink
            // пересобрал бы стек и выбросил состояние экрана.
            val alreadyThere = intent.data?.toString() == TrackingDeepLinks.RECORDING &&
                navController.currentDestination?.hasRoute<TrackingRecordingRoute>() == true
            if (!alreadyThere) navController.handleDeepLink(intent)
        }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val destination = currentBackStackEntry?.destination
    val isHomeSelected = destination?.hasRoute<HomeRoute>() == true
    val isListSelected = destination?.hasRoute<ActivityListRoute>() == true
    val isSettingsSelected = destination?.hasRoute<SettingsRoute>() == true
    val showBottomBar = isHomeSelected || isListSelected || isSettingsSelected

    // Поверх онбординга и уже открытого экрана сохранения ничего открывать не нужно;
    // в остальном экран сохранения имеет право прервать любой другой экран.
    val canOpenPendingSummary = destination != null &&
        destination.hasRoute<OnboardingRoute>().not() &&
        destination.hasRoute<TrackingSummaryRoute>().not()

    PendingSummaryEffect(
        isEnabled = canOpenPendingSummary,
        onOpenSummary = { activityId ->
            navController.navigate(TrackingSummaryRoute(activityId)) {
                // Экран записи (если он ещё в стеке) заменяем, а не накрываем: запись уже
                // закончилась кнопкой в уведомлении, возвращаться к ней некуда.
                popUpTo<TrackingRecordingRoute> { inclusive = true }
                launchSingleTop = true
            }
        },
    )

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                KornoBottomBar(
                    isHomeSelected = isHomeSelected,
                    isListSelected = isListSelected,
                    isSettingsSelected = isSettingsSelected,
                    onHomeClick = {
                        navController.navigate(HomeRoute) { launchSingleTop = true; popUpTo(HomeRoute) }
                    },
                    onListClick = {
                        navController.navigate(ActivityListRoute) { launchSingleTop = true; popUpTo(HomeRoute) }
                    },
                    onSettingsClick = {
                        navController.navigate(SettingsRoute) { launchSingleTop = true; popUpTo(HomeRoute) }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (appState.onboardingCompleted) HomeRoute else OnboardingRoute,
            modifier = Modifier.padding(padding),
        ) {
            onboardingGraph(
                onOnboardingFinished = {
                    navController.navigate(HomeRoute) {
                        popUpTo<OnboardingRoute> { inclusive = true }
                    }
                },
            )
            homeGraph(
                onStartSetup = viewModel::onStartActivityRequested,
                onSeeAllActivities = { navController.navigate(ActivityListRoute) },
                onOpenActivity = { activityId -> navController.navigate(ActivityDetailRoute(activityId)) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
            trackingGraph(
                navController = navController,
                onFinished = { activityId ->
                    // Именно экран сохранения, а не setup: до него можно дойти и мимо setup —
                    // когда запись остановили из уведомления, и экран открылся при возврате.
                    navController.navigate(ActivityDetailRoute(activityId)) {
                        popUpTo<TrackingSummaryRoute> { inclusive = true }
                    }
                },
                onDiscarded = {
                    navController.popBackStack(HomeRoute, inclusive = false)
                },
            )
            activityListGraph(
                onOpenActivity = { activityId -> navController.navigate(ActivityDetailRoute(activityId)) },
            )
            activityDetailGraph(navController = navController)
            settingsGraph(
                onOpenPermissions = { navController.navigate(OnboardingRoute) },
                onViewOnboarding = { navController.navigate(OnboardingRoute) },
            )
        }
    }
}

@Composable
private fun KornoBottomBar(
    isHomeSelected: Boolean,
    isListSelected: Boolean,
    isSettingsSelected: Boolean,
    onHomeClick: () -> Unit,
    onListClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = isHomeSelected,
            onClick = onHomeClick,
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home)) },
        )
        NavigationBarItem(
            selected = isListSelected,
            onClick = onListClick,
            icon = { Icon(Icons.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_activities)) },
        )
        NavigationBarItem(
            selected = isSettingsSelected,
            onClick = onSettingsClick,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}
