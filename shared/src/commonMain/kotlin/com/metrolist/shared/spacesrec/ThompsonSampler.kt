/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.spacesrec

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Pure Thompson sampling over Beta(1 + completions, 1 + skips) arms using
 * Marsaglia & Tsang's Gamma sampler. Injectable [Random] makes every draw deterministic in tests.
 */
object ThompsonSampler {
    fun sampleBeta(
        alpha: Double,
        beta: Double,
        random: Random = Random.Default,
    ): Double {
        require(alpha > 0.0 && alpha.isFinite()) { "alpha must be positive and finite, got $alpha" }
        require(beta > 0.0 && beta.isFinite()) { "beta must be positive and finite, got $beta" }
        val x = sampleGamma(alpha, random)
        val y = sampleGamma(beta, random)
        val sum = x + y
        return if (sum <= 0.0 || !sum.isFinite()) 0.5 else (x / sum).coerceIn(0.0, 1.0)
    }

    /**
     * Marsaglia & Tsang (2000) Gamma(shape, scale = 1) sampler.
     */
    internal fun sampleGamma(
        shape: Double,
        random: Random,
    ): Double {
        require(shape > 0.0 && shape.isFinite()) { "shape must be positive and finite, got $shape" }
        if (shape < 1.0) {
            val u = nextOpenUnitDouble(random)
            return sampleGamma(shape + 1.0, random) * u.pow(1.0 / shape)
        }

        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            val x = nextStandardNormal(random)
            val v = 1.0 + c * x
            if (v <= 0.0) continue
            val v3 = v * v * v
            val u = nextOpenUnitDouble(random)
            val x2 = x * x
            if (u < 1.0 - 0.0331 * x2 * x2) {
                return d * v3
            }
            if (ln(u) < 0.5 * x2 + d * (1.0 - v3 + ln(v3))) {
                return d * v3
            }
        }
    }

    private fun nextOpenUnitDouble(random: Random): Double {
        var u = random.nextDouble()
        while (u <= 0.0 || u >= 1.0) {
            u = random.nextDouble()
        }
        return u
    }

    private fun nextStandardNormal(random: Random): Double {
        val u1 = nextOpenUnitDouble(random)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * Selects up to [count] seed arms (at most one per artist), skipping arms used
     * within the last [SpaceRecConfig.seedCooldownRefreshes] refreshes unless fewer
     * than [count] eligible arms remain.
     */
    fun selectSeeds(
        arms: List<SeedArmState>,
        currentRefreshSeq: Int,
        count: Int,
        config: SpaceRecConfig = SpaceRecConfig.Default,
        random: Random = Random.Default,
    ): List<SeedArmState> {
        if (arms.isEmpty() || count <= 0) return emptyList()

        // Enforce 1 arm per artist (keep the most recently updated / highest completions if duplicates exist).
        val uniqueByArtist =
            arms
                .groupBy { it.artistId }
                .values
                .map { group -> group.maxBy { it.completions - it.skips } }

        val nonCooldown =
            uniqueByArtist.filter { arm ->
                arm.lastUsedSeq < 0 || (currentRefreshSeq - arm.lastUsedSeq) > config.seedCooldownRefreshes
            }

        val primaryPool =
            if (nonCooldown.size >= count) {
                nonCooldown
            } else {
                uniqueByArtist
            }

        val scored =
            primaryPool.map { arm ->
                val alpha = 1.0 + arm.completions.coerceAtLeast(0).toDouble()
                val beta = 1.0 + arm.skips.coerceAtLeast(0).toDouble()
                arm to sampleBeta(alpha, beta, random)
            }

        // If we had to fall back to include cooldown arms, still prioritize non-cooldown arms first.
        val nonCooldownIds = nonCooldown.mapTo(hashSetOf()) { it.artistId }
        val sorted =
            if (nonCooldown.size < count && nonCooldown.isNotEmpty()) {
                scored.sortedWith(
                    compareByDescending<Pair<SeedArmState, Double>> { it.first.artistId in nonCooldownIds }
                        .thenByDescending { it.second },
                )
            } else {
                scored.sortedByDescending { it.second }
            }

        return sorted.take(count).map { it.first }
    }

    /**
     * Applies a play event outcome to an arm using the completion rule
     * (>= 30s played OR >= 50% of track duration).
     */
    fun recordOutcome(
        arm: SeedArmState,
        play: PlayRecord,
        config: SpaceRecConfig = SpaceRecConfig.Default,
    ): SeedArmState =
        if (play.isCompleted(config)) {
            arm.copy(completions = arm.completions + 1)
        } else {
            arm.copy(skips = arm.skips + 1)
        }
}
