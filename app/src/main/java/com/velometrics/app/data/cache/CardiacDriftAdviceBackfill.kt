package com.velometrics.app.data.cache

import android.util.Log
import com.velometrics.app.data.preferences.FtpHistoryRepository
import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.service.CardiacDriftAdviceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-start backfill of the cardiac drift causes (#222) onto rides imported before they existed.
 * Only touches rides never evaluated, so after the first run it's a single empty query.
 */
@Singleton
class CardiacDriftAdviceBackfill @Inject constructor(
    private val service: CardiacDriftAdviceService,
    private val ftpHistoryRepository: FtpHistoryRepository,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start() {
        scope.launch {
            try {
                service.backfillMissing(ftpHistoryRepository.history.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort: those rides just show no drift advice until the next start.
                Log.w(TAG, "Cardiac drift advice backfill failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "CardiacDriftBackfill"
    }
}
