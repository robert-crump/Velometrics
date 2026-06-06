package com.velometrics.app.data.location

import com.velometrics.app.domain.model.LocationFix
import com.velometrics.app.domain.service.LocationException
import com.velometrics.app.domain.service.LocationSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeLocationSource : LocationSource {

    private val fixChannel = Channel<LocationFix>(Channel.UNLIMITED)
    private var subscriptionException: LocationException? = null
    private var lastKnownResult: LocationFix? = null

    /** Enqueue a fix to be emitted the next time fixes() is collected. */
    fun emitFix(fix: LocationFix) { fixChannel.trySend(fix) }

    /** Causes fixes() to throw the given exception on the next subscription. */
    fun setSubscriptionException(e: LocationException) { subscriptionException = e }

    /** Sets what lastKnownFix() returns. */
    fun setLastKnown(fix: LocationFix?) { lastKnownResult = fix }

    override fun fixes(): Flow<LocationFix> = flow {
        subscriptionException?.let { throw it }
        for (fix in fixChannel) {
            emit(fix)
        }
    }

    override suspend fun lastKnownFix(maxAccuracyM: Float): LocationFix? = lastKnownResult
}
