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

enum class AppCategory { RIDES, FOOD_DELIVERY }

enum class Preference { NONE, CHEAPEST, FASTEST }

data class KnownApp(
    val packageName: String,
    val displayName: String,
    val deepLinkScheme: String?,
    val supportsSetText: Boolean,
    val destinationFieldHint: String,
    val category: AppCategory,
)

data class FareData(
    val price: String,
    val eta: Int?,
    val confidence: Float
)

sealed class ParsedGoal {
    abstract val rawGoal: String

    data class RideRequest(
        val destination: String,
        override val rawGoal: String,
        val preference: Preference = Preference.NONE,
    ) : ParsedGoal()

    data class FoodOrder(
        val restaurant: String,
        val item: String?,
        override val rawGoal: String,
        val preference: Preference = Preference.NONE,
    ) : ParsedGoal()
}

class ComparisonSession(
    val parsedGoal: ParsedGoal,
    val apps: List<KnownApp>,
) {
    var currentIndex: Int = 0
        private set
    val collectedFares: MutableMap<String, FareData> = java.util.concurrent.ConcurrentHashMap()

    val currentApp: KnownApp? get() = apps.getOrNull(currentIndex)
    val isComplete: Boolean get() = currentIndex >= apps.size

    fun advance() { currentIndex++ }

    private fun priceValue(fare: FareData): Double =
        fare.price.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: Double.MAX_VALUE

    val cheapestApp: KnownApp?
        get() = collectedFares.entries
            .minByOrNull { priceValue(it.value) }
            ?.key
            ?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }

    val fastestApp: KnownApp?
        get() = collectedFares.entries
            .filter { it.value.eta != null }
            .minByOrNull { it.value.eta!! }
            ?.key
            ?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
}
