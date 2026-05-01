package com.orion.core

data class ExecutionResult(
    val success: Boolean,
    val fallbackUsed: Boolean = false,
    val errorCode: String? = null
)

enum class OrionMode { IDLE, WATCHING, COMPARING, READY }

enum class ScreenPhase { UNKNOWN, HOME, SEARCH_INPUT, FARE_ESTIMATE, CONFIRMATION }

data class TapTarget(val nodeText: String, val boundsHint: List<Int>? = null)

data class PerceptionResult(
    val screenPhase: ScreenPhase,
    val extractedData: Map<String, String>,
    val tapTarget: TapTarget?,
    val confidence: Float,
    val rawDescription: String,
)

data class PlanAction(
    val type: String,
    val nodeText: String? = null,
    val nodeIndex: Int? = null,
    val x: Float? = null,
    val y: Float? = null,
    val text: String? = null,
    val app: String? = null,
    val fallbackUri: String? = null,
    val waitForPhase: String? = null,
)

data class Plan(val summaryForUser: String, val actions: List<PlanAction>)
