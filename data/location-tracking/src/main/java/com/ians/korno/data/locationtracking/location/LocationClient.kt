package com.ians.korno.data.locationtracking.location

import kotlinx.coroutines.flow.Flow

interface LocationClient {
    fun locationUpdates(config: LocationUpdateConfig): Flow<LocationSample>
}
