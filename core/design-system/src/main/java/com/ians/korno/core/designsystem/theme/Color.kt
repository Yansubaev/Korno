package com.ians.korno.core.designsystem.theme

import androidx.compose.ui.graphics.Color

// Цвета выведены из иконки приложения (ic_launcher) по правилам Material 3:
// исходные цвета переведены в HCT, из них построены тональные палитры, а роли
// схемы взяты как фиксированные тона этих палитр (см. m3.material.io/styles/color/roles).
//
// Опорные цвета иконки и палитры, которые из них получились:
//   primary          hue 152.5 / chroma 60  — зелёный градиента велосипедиста (#61F08D, #3EC26E)
//   secondary        hue 152.5 / chroma 24  — тот же тон, приглушённый
//   tertiary         hue 190.0 / chroma 48  — бирюзовый конец градиента (#02E1C9, #02AB9F)
//   neutral          hue 200.0 / chroma 6   — тёмно-бирюзовый фон иконки (#062A2F), сведённый в серый
//   neutralVariant   hue 200.0 / chroma 12
//   error            hue 25.0  / chroma 84  — стандартная палитра ошибок M3
//
// Значения сгенерированы, править их вручную не нужно: чтобы изменить схему,
// меняются исходные цвета/палитры выше, а тона ролей остаются как в спецификации.

// Роли светлой схемы
internal val LightPrimary = Color(0xFF006D32) // tone 40
internal val LightOnPrimary = Color(0xFFFFFFFF) // tone 100
internal val LightPrimaryContainer = Color(0xFF9EE8B0) // tone 90
internal val LightOnPrimaryContainer = Color(0xFF005224) // tone 30
internal val LightSecondary = Color(0xFF46664B) // tone 40
internal val LightOnSecondary = Color(0xFFFFFFFF) // tone 100
internal val LightSecondaryContainer = Color(0xFFC8ECC9) // tone 90
internal val LightOnSecondaryContainer = Color(0xFF2F4E34) // tone 30
internal val LightTertiary = Color(0xFF006A64) // tone 40
internal val LightOnTertiary = Color(0xFFFFFFFF) // tone 100
internal val LightTertiaryContainer = Color(0xFF71F7EC) // tone 90
internal val LightOnTertiaryContainer = Color(0xFF00504B) // tone 30
internal val LightError = Color(0xFFBA1A1A) // tone 40
internal val LightOnError = Color(0xFFFFFFFF) // tone 100
internal val LightErrorContainer = Color(0xFFFFDAD6) // tone 90
internal val LightOnErrorContainer = Color(0xFF93000A) // tone 30
internal val LightBackground = Color(0xFFF4FBFA) // tone 98
internal val LightOnBackground = Color(0xFF161D1D) // tone 10
internal val LightSurface = Color(0xFFF4FBFA) // tone 98
internal val LightOnSurface = Color(0xFF161D1D) // tone 10
internal val LightSurfaceVariant = Color(0xFFD4E6E6) // tone 90
internal val LightOnSurfaceVariant = Color(0xFF394A4A) // tone 30
internal val LightOutline = Color(0xFF697A7B) // tone 50
internal val LightOutlineVariant = Color(0xFFB8CACA) // tone 80
internal val LightScrim = Color(0xFF000000) // tone 0
internal val LightInverseSurface = Color(0xFF2B3232) // tone 20
internal val LightInverseOnSurface = Color(0xFFECF2F2) // tone 95
internal val LightInversePrimary = Color(0xFF67DE87) // tone 80
internal val LightSurfaceTint = Color(0xFF006D32) // tone 40
internal val LightSurfaceDim = Color(0xFFD5DBDB) // tone 87
internal val LightSurfaceBright = Color(0xFFF4FBFA) // tone 98
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF) // tone 100
internal val LightSurfaceContainerLow = Color(0xFFEFF5F4) // tone 96
internal val LightSurfaceContainer = Color(0xFFE9EFEF) // tone 94
internal val LightSurfaceContainerHigh = Color(0xFFE3E9E9) // tone 92
internal val LightSurfaceContainerHighest = Color(0xFFDDE4E3) // tone 90

// Роли тёмной схемы
internal val DarkPrimary = Color(0xFF67DE87) // tone 80
internal val DarkOnPrimary = Color(0xFF003917) // tone 20
internal val DarkPrimaryContainer = Color(0xFF005224) // tone 30
internal val DarkOnPrimaryContainer = Color(0xFF84FBA0) // tone 90
internal val DarkSecondary = Color(0xFFACCFAE) // tone 80
internal val DarkOnSecondary = Color(0xFF18371F) // tone 20
internal val DarkSecondaryContainer = Color(0xFF2F4E34) // tone 30
internal val DarkOnSecondaryContainer = Color(0xFFC8ECC9) // tone 90
internal val DarkTertiary = Color(0xFF50DBD0) // tone 80
internal val DarkOnTertiary = Color(0xFF003734) // tone 20
internal val DarkTertiaryContainer = Color(0xFF00504B) // tone 30
internal val DarkOnTertiaryContainer = Color(0xFF71F7EC) // tone 90
internal val DarkError = Color(0xFFFFB4AB) // tone 80
internal val DarkOnError = Color(0xFF690004) // tone 20
internal val DarkErrorContainer = Color(0xFF93000A) // tone 30
internal val DarkOnErrorContainer = Color(0xFFFFDAD6) // tone 90
internal val DarkBackground = Color(0xFF0E1415) // tone 6
internal val DarkOnBackground = Color(0xFFDDE4E3) // tone 90
internal val DarkSurface = Color(0xFF0E1415) // tone 6
internal val DarkOnSurface = Color(0xFFDDE4E3) // tone 90
internal val DarkSurfaceVariant = Color(0xFF394A4A) // tone 30
internal val DarkOnSurfaceVariant = Color(0xFFB8CACA) // tone 80
internal val DarkOutline = Color(0xFF829494) // tone 60
internal val DarkOutlineVariant = Color(0xFF394A4A) // tone 30
internal val DarkScrim = Color(0xFF000000) // tone 0
internal val DarkInverseSurface = Color(0xFFDDE4E3) // tone 90
internal val DarkInverseOnSurface = Color(0xFF2B3232) // tone 20
internal val DarkInversePrimary = Color(0xFF006D32) // tone 40
internal val DarkSurfaceTint = Color(0xFF67DE87) // tone 80
internal val DarkSurfaceDim = Color(0xFF0E1415) // tone 6
internal val DarkSurfaceBright = Color(0xFF343A3A) // tone 24
internal val DarkSurfaceContainerLowest = Color(0xFF090F0F) // tone 4
internal val DarkSurfaceContainerLow = Color(0xFF161D1D) // tone 10
internal val DarkSurfaceContainer = Color(0xFF1A2121) // tone 12
internal val DarkSurfaceContainerHigh = Color(0xFF252B2B) // tone 17
internal val DarkSurfaceContainerHighest = Color(0xFF303636) // tone 22
