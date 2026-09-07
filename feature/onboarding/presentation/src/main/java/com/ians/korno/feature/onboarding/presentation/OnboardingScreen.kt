package com.ians.korno.feature.onboarding.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ians.korno.core.designsystem.theme.KornoTheme
import com.ians.korno.feature.onboarding.domain.model.PermissionType
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun OnboardingScreen(
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onContinueClick()
    }

    var showIntro by rememberSaveable { mutableStateOf(true) }

    if (showIntro) {
        OnboardingIntroContent(
            onIntroFinished = { showIntro = false },
            modifier = modifier,
        )
    } else {
        val context = LocalContext.current
        val activity = LocalActivity.current

        // Разрешение могли выдать на системном экране настроек, куда ведёт часть
        // карточек: возврат сюда должен показать это без перезапуска экрана.
        LifecycleResumeEffect(viewModel) {
            viewModel.onScreenResumed()
            onPauseOrDispose {}
        }

        var pendingType by remember { mutableStateOf<PermissionType?>(null) }
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            val type = pendingType ?: return@rememberLauncherForActivityResult
            viewModel.onPermissionResult(
                type = type,
                granted = results.values.all { it },
                // Система готова показать диалог ещё раз, только пока просит объяснить
                // отказ. Иначе повторное нажатие «Разрешить» не сделает вообще ничего.
                canAskAgain = activity != null && type.toAndroidPermissions().any { permission ->
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                },
            )
        }

        OnboardingPermissionsContent(
            state = state,
            onStepClick = { step ->
                when (step.action) {
                    PermissionAction.REQUEST -> {
                        pendingType = step.type
                        permissionLauncher.launch(step.type.toAndroidPermissions())
                    }
                    PermissionAction.OPEN_SETTINGS -> context.openAppSettings()
                }
            },
            onContinueClick = viewModel::onContinueClicked,
            modifier = modifier,
        )
    }
}

/**
 * Экран приложения в системных настройках: оттуда доступны все разрешения, включая
 * те, которых диалог не выдаёт.
 */
private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private data class IntroSlide(
    val icon: ImageVector,
    val containerColor: @Composable () -> Color,
    val contentColor: @Composable () -> Color,
    val titleRes: Int,
    val bodyRes: Int,
)

private val NEXT_BUTTON_WIDTH = 140.dp

private val INTRO_SLIDES = listOf(
    IntroSlide(
        icon = Icons.Filled.LocationOn,
        containerColor = { MaterialTheme.colorScheme.primaryContainer },
        contentColor = { MaterialTheme.colorScheme.onPrimaryContainer },
        titleRes = R.string.onboarding_intro_slide1_title,
        bodyRes = R.string.onboarding_intro_slide1_body,
    ),
    IntroSlide(
        icon = Icons.Filled.CloudOff,
        containerColor = { MaterialTheme.colorScheme.tertiaryContainer },
        contentColor = { MaterialTheme.colorScheme.onTertiaryContainer },
        titleRes = R.string.onboarding_intro_slide2_title,
        bodyRes = R.string.onboarding_intro_slide2_body,
    ),
    IntroSlide(
        icon = Icons.Filled.Check,
        containerColor = { MaterialTheme.colorScheme.secondaryContainer },
        contentColor = { MaterialTheme.colorScheme.onSecondaryContainer },
        titleRes = R.string.onboarding_intro_slide3_title,
        bodyRes = R.string.onboarding_intro_slide3_body,
    ),
)

@Composable
private fun OnboardingIntroContent(
    onIntroFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { INTRO_SLIDES.size }
    val coroutineScope = rememberCoroutineScope()
    val lastIndex = INTRO_SLIDES.lastIndex
    val isLast = pagerState.currentPage == lastIndex

    // Continuous 0f..1f progress of the swipe from the second-to-last page into the
    // last page, so the bottom controls morph in sync with the drag instead of
    // snapping once the page settles.
    val lastPageProgress by remember {
        derivedStateOf {
            val pageOffset = pagerState.currentPage + pagerState.currentPageOffsetFraction
            (pageOffset - (lastIndex - 1)).coerceIn(0f, 1f)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(vertical = 32.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val slide = INTRO_SLIDES[page]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(slide.containerColor(), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = slide.icon,
                        contentDescription = null,
                        tint = slide.contentColor(),
                        modifier = Modifier.size(36.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(slide.titleRes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(slide.bodyRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            INTRO_SLIDES.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .background(
                            color = if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).height(56.dp),
        ) {
            if (lastPageProgress < 1f) {
                TextButton(
                    onClick = onIntroFinished,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .alpha(1f - lastPageProgress),
                ) {
                    Text(text = stringResource(R.string.onboarding_skip))
                }
            }

            val primaryButtonWidth = lerp(NEXT_BUTTON_WIDTH, maxWidth, lastPageProgress)
            Button(
                onClick = {
                    if (isLast) {
                        onIntroFinished()
                    } else {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(primaryButtonWidth)
                    .fillMaxHeight(),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.onboarding_next),
                        modifier = Modifier.alpha(1f - lastPageProgress),
                    )
                    Text(
                        text = stringResource(R.string.onboarding_get_started),
                        modifier = Modifier.alpha(lastPageProgress),
                    )
                }
            }
        }
    }
}

private fun PermissionType.icon(): ImageVector = when (this) {
    PermissionType.FOREGROUND_LOCATION, PermissionType.BACKGROUND_LOCATION -> Icons.Filled.LocationOn
    PermissionType.ACTIVITY_RECOGNITION -> Icons.Filled.DirectionsWalk
    PermissionType.POST_NOTIFICATIONS -> Icons.Filled.Notifications
}

@Composable
private fun PermissionType.accentColor(): Color = when (this) {
    PermissionType.FOREGROUND_LOCATION -> MaterialTheme.colorScheme.primary
    PermissionType.BACKGROUND_LOCATION -> MaterialTheme.colorScheme.tertiary
    PermissionType.ACTIVITY_RECOGNITION -> MaterialTheme.colorScheme.secondary
    PermissionType.POST_NOTIFICATIONS -> MaterialTheme.colorScheme.secondary
}

@Composable
internal fun OnboardingPermissionsContent(
    state: OnboardingUiState,
    onStepClick: (OnboardingStep) -> Unit,
    onContinueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 8.dp)) {
            Text(
                text = stringResource(R.string.onboarding_permissions_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.onboarding_permissions_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.steps.forEach { step ->
                PermissionCard(
                    step = step,
                    isDeniedRequired = step.type == PermissionType.FOREGROUND_LOCATION &&
                        state.isForegroundLocationDenied,
                    onActionClick = { onStepClick(step) },
                )
            }
        }
        Box(modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 28.dp)) {
            Button(
                onClick = onContinueClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(text = stringResource(R.string.onboarding_continue))
            }
        }
    }
}

@Composable
private fun PermissionCard(
    step: OnboardingStep,
    isDeniedRequired: Boolean,
    onActionClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = step.type.icon(),
            contentDescription = null,
            tint = step.type.accentColor(),
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(step.type.titleRes()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(step.type.rationaleRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isDeniedRequired) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.onboarding_foreground_location_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!step.isGranted && step.isBlocked) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.onboarding_background_location_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (step.isGranted) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.onboarding_granted),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Button(
                    onClick = onActionClick,
                    enabled = !step.isBlocked,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(
                        text = stringResource(
                            when (step.action) {
                                PermissionAction.REQUEST -> R.string.onboarding_allow
                                PermissionAction.OPEN_SETTINGS -> R.string.onboarding_open_settings
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Suppress("InlinedApi")
private fun PermissionType.toAndroidPermissions(): Array<String> = when (this) {
    PermissionType.FOREGROUND_LOCATION -> arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    PermissionType.BACKGROUND_LOCATION -> arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    PermissionType.ACTIVITY_RECOGNITION -> arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)
    PermissionType.POST_NOTIFICATIONS -> arrayOf(Manifest.permission.POST_NOTIFICATIONS)
}

private fun PermissionType.titleRes(): Int = when (this) {
    PermissionType.FOREGROUND_LOCATION -> R.string.onboarding_step_foreground_location_title
    PermissionType.BACKGROUND_LOCATION -> R.string.onboarding_step_background_location_title
    PermissionType.ACTIVITY_RECOGNITION -> R.string.onboarding_step_activity_recognition_title
    PermissionType.POST_NOTIFICATIONS -> R.string.onboarding_step_notifications_title
}

private fun PermissionType.rationaleRes(): Int = when (this) {
    PermissionType.FOREGROUND_LOCATION -> R.string.onboarding_step_foreground_location_rationale
    PermissionType.BACKGROUND_LOCATION -> R.string.onboarding_step_background_location_rationale
    PermissionType.ACTIVITY_RECOGNITION -> R.string.onboarding_step_activity_recognition_rationale
    PermissionType.POST_NOTIFICATIONS -> R.string.onboarding_step_notifications_rationale
}

@Preview(showBackground = true)
@Composable
private fun OnboardingIntroContentPreview() {
    KornoTheme {
        OnboardingIntroContent(onIntroFinished = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPermissionsContentPreview() {
    KornoTheme {
        OnboardingPermissionsContent(
            state = OnboardingUiState(
                steps = PermissionType.entries.map { type ->
                    OnboardingStep(
                        type = type,
                        isGranted = type == PermissionType.FOREGROUND_LOCATION,
                        action = if (type == PermissionType.BACKGROUND_LOCATION) {
                            PermissionAction.OPEN_SETTINGS
                        } else {
                            PermissionAction.REQUEST
                        },
                        isBlocked = false,
                    )
                },
            ),
            onStepClick = {},
            onContinueClick = {},
        )
    }
}
