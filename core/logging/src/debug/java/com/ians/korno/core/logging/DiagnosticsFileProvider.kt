package com.ians.korno.core.logging

import androidx.core.content.FileProvider

/** Separate component name prevents manifest merging with the track export provider. */
class DiagnosticsFileProvider : FileProvider()
