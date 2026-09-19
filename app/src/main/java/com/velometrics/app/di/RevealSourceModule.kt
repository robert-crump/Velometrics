package com.velometrics.app.di

import com.velometrics.app.domain.service.IntervalAchievementRevealSource
import com.velometrics.app.domain.service.PowerCurveAchievementEvaluator
import com.velometrics.app.domain.service.RevealCandidateSource
import com.velometrics.app.domain.service.RideMilestoneEvaluator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** The Ride Reveal sources. A new source is one adapter class plus one binding here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RevealSourceModule {

    @Binds @IntoSet
    abstract fun bindMilestones(impl: RideMilestoneEvaluator): RevealCandidateSource

    @Binds @IntoSet
    abstract fun bindPowerCurve(impl: PowerCurveAchievementEvaluator): RevealCandidateSource

    @Binds @IntoSet
    abstract fun bindIntervals(impl: IntervalAchievementRevealSource): RevealCandidateSource
}
