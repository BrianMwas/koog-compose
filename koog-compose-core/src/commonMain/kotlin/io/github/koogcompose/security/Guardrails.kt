package io.github.koogcompose.security

import kotlin.time.Duration

/**
 * Guardrail configuration for AI agents.
 * Ensures the agent cannot perform runaway actions or access unapproved system features.
 */
public data class Guardrails(
    val toolRateLimits: Map<String, RateLimit> = emptyMap(),
    val allowedIntentActions: Set<String> = emptySet(),
    val allowedWorkTags: Set<String> = emptySet(),
    val maxScheduledJobs: Int = 5
) {
    public data class RateLimit(val max: Int, val window: Duration)

    public class Builder {
        private val toolRateLimits = mutableMapOf<String, RateLimit>()
        private val allowedIntentActions = mutableSetOf<String>()
        private val allowedWorkTags = mutableSetOf<String>()
        private var maxScheduledJobs = 5

        /**
         * Limits how many times a specific tool can be called in a given window.
         * Example: rateLimit("WorkManagerTool", max = 1, per = 1.hours)
         */
        public fun rateLimit(tool: String, max: Int, per: Duration) {
            toolRateLimits[tool] = RateLimit(max, per)
        }

        /**
         * Approves specific Android Intent actions the agent is allowed to trigger.
         */
        public fun allowIntent(action: String) {
            allowedIntentActions.add(action)
        }

        /**
         * Restricts WorkManager jobs to specific tags.
         */
        public fun allowWorkTag(tag: String) {
            allowedWorkTags.add(tag)
        }

        public fun maxScheduledJobs(count: Int) {
            maxScheduledJobs = count
        }

        public fun build(): Guardrails = Guardrails(
            toolRateLimits = toolRateLimits.toMap(),
            allowedIntentActions = allowedIntentActions.toSet(),
            allowedWorkTags = allowedWorkTags.toSet(),
            maxScheduledJobs = maxScheduledJobs
        )
    }

    public companion object {
        public val Default: Guardrails = Guardrails()
    }
}
