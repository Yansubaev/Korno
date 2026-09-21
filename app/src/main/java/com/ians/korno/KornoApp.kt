package com.ians.korno

import android.Manifest
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.animation.DISINTEGRATING_ENTRY_KEY
import com.ians.korno.core.designsystem.animation.LocalSharedTransitionScope
import com.ians.korno.core.designsystem.animation.StartRecordingTransition
import com.ians.korno.core.designsystem.layout.LocalBottomBarPadding
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
                    navController.navigate(TrackingSetupRoute(event.kind))
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
    val currentTab = currentBackStackEntry?.tabIndex() ?: -1
    val showBottomBar = currentTab >= 0
    // Пока панель уезжает, текущий экран уже не вкладка — подсветку оставляем на вкладке,
    // с которой ушли (она лежит в стеке прямо под ним), иначе индикатор гаснет на ходу.
    val selectedTab = if (showBottomBar) currentTab else navController.previousBackStackEntry?.tabIndex() ?: -1

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

    // Высота панели запоминается, пока она видна: экрану вкладки, на который возвращаемся,
    // отступ нужен с первого кадра перехода, когда сама панель ещё только выезжает.
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    val bottomBarPadding = (bottomBarHeight - WindowInsets.systemBars.asPaddingValues().calculateBottomPadding())
        .coerceAtLeast(0.dp)

    Scaffold(
        modifier = modifier,
        // Панель не в слоте bottomBar, поэтому snackbar над ней поднимаем сами.
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = if (showBottomBar) bottomBarPadding else 0.dp),
            )
        },
    ) { padding ->
        SharedTransitionLayout {
            // Панель внутри SharedTransitionLayout, чтобы раскрывающийся экран рисовался поверх неё.
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalBottomBarPadding provides bottomBarPadding,
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = if (appState.onboardingCompleted) HomeRoute else OnboardingRoute,
                        modifier = Modifier.padding(padding),
                        // pop-переходы берут те же лямбды: направление считается по вкладкам, а не по стеку,
                        // и возврат на Home через popUpTo навигатор может отдать и как pop, и как push.
                        enterTransition = {
                            when {
                                // Под рассыпающимся экраном следующий должен быть уже целиком, а не
                                // проявляться сквозь пыль. Запись в стеке ещё жива: NavHost зовёт
                                // переходы только для видимых записей.
                                initialState.savedStateHandle.contains(DISINTEGRATING_ENTRY_KEY) -> EnterTransition.None
                                else -> tabSlideDirection()?.let { slideIntoContainer(it) } ?: fadeIn(tween(fadeMillis()))
                            }
                        },
                        exitTransition = {
                            tabSlideDirection()?.let { slideOutOfContainer(it) } ?: fadeOut(tween(fadeMillis()))
                        },
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
                AnimatedVisibility(
                    visible = showBottomBar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    // В такт переходу Home ↔ настройка записи — там панель заметнее всего.
                    enter = slideInVertically(tween(StartRecordingTransition.DURATION_MILLIS)) { it },
                    exit = slideOutVertically(tween(StartRecordingTransition.DURATION_MILLIS)) { it },
                ) {
                    KornoBottomBar(
                        isHomeSelected = selectedTab == 0,
                        isListSelected = selectedTab == 1,
                        isSettingsSelected = selectedTab == 2,
                        onHomeClick = {
                            navController.navigate(HomeRoute) { launchSingleTop = true; popUpTo(HomeRoute) }
                        },
                        onListClick = {
                            navController.navigate(ActivityListRoute) { launchSingleTop = true; popUpTo(HomeRoute) }
                        },
                        onSettingsClick = {
                            navController.navigate(SettingsRoute) { launchSingleTop = true; popUpTo(HomeRoute) }
                        },
                        modifier = Modifier.onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } },
                    )
                }
            }
        }
    }
}

private fun NavBackStackEntry.tabIndex(): Int = when {
    destination.hasRoute<HomeRoute>() -> 0
    destination.hasRoute<ActivityListRoute>() -> 1
    destination.hasRoute<SettingsRoute>() -> 2
    else -> -1
}

/** Вкладка правее в нижней панели — экран въезжает справа, левее — слева. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSlideDirection(): SlideDirection? {
    val from = initialState.tabIndex()
    val to = targetState.tabIndex()
    if (from < 0 || to < 0 || from == to) return null
    return if (to > from) SlideDirection.Left else SlideDirection.Right
}

/** Home ↔ настройка записи идут в такт раскрытию кнопки; остальным — дефолт NavHost. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.fadeMillis(): Int {
    val homeAndSetup = setOf(initialState, targetState).let { entries ->
        entries.any { it.destination.hasRoute<HomeRoute>() } &&
            entries.any { it.destination.hasRoute<TrackingSetupRoute>() }
    }
    return if (homeAndSetup) StartRecordingTransition.DURATION_MILLIS else 700
}

@Composable
private fun KornoBottomBar(
    isHomeSelected: Boolean,
    isListSelected: Boolean,
    isSettingsSelected: Boolean,
    onHomeClick: () -> Unit,
    onListClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            selected = isHomeSelected,
            onClick = { if (!isHomeSelected) onHomeClick() },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home)) },
        )
        NavigationBarItem(
            selected = isListSelected,
            onClick = { if (!isListSelected) onListClick() },
            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_activities)) },
        )
        NavigationBarItem(
            selected = isSettingsSelected,
            onClick = { if (!isSettingsSelected) onSettingsClick() },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}
