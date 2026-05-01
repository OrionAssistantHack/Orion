package com.orion

import com.orion.core.AppCategory
import com.orion.core.ParsedGoal

// Ride: "book/get/order/find/call/hail/request [me/us] [a] cab/ride/taxi/lyft/uber/car/vehicle to X"
private val RIDE_PATTERN = Regex(
    """(?:book|get|order|find|call|hail|request)(?:\s+(?:me|us))?\s+(?:an?\s+)?""" +
    """(?:\w+\s+)*(?:cab|ride|taxi|lyft|uber|car|vehicle|transport)\s+""" +
    """(?:to|toward|towards|going to|headed to|heading to)\s+(.+)""",
    RegexOption.IGNORE_CASE
)

// Food with item: "order [a/some/the] <item> from <restaurant>"
private val FOOD_WITH_ITEM_PATTERN = Regex(
    """(?:order|get|buy)\s+(?:a\s+|some\s+|the\s+)?(.+?)\s+from\s+(.+)""",
    RegexOption.IGNORE_CASE
)

// Food without item: "order/get [food/delivery/something] from <restaurant>"
private val FOOD_NO_ITEM_PATTERN = Regex(
    """(?:order|get)\s+(?:food|delivery|something)?\s*from\s+(.+)""",
    RegexOption.IGNORE_CASE
)

private val FOOD_DELIVERY_KEYWORDS = setOf("food", "delivery", "something")

// Returns a ParsedGoal if the goal matches a known comparison-eligible intent, null otherwise.
fun parseGoal(goal: String): ParsedGoal? {
    val trimmed = goal.trim()
    if (trimmed.isBlank()) return null

    RIDE_PATTERN.find(trimmed)?.let { m ->
        return ParsedGoal.RideRequest(destination = m.groupValues[1].trim(), rawGoal = trimmed)
    }

    // Try food with item first ("order a burger from Shake Shack")
    FOOD_WITH_ITEM_PATTERN.find(trimmed)?.let { m ->
        val item = m.groupValues[1].trim()
            .replace(Regex("^(?:a|an|the)\\s+", RegexOption.IGNORE_CASE), "")
        val restaurant = m.groupValues[2].trim()
        if (item.lowercase() !in FOOD_DELIVERY_KEYWORDS) {
            return ParsedGoal.FoodOrder(restaurant = restaurant, item = item, rawGoal = trimmed)
        }
    }

    // Fall back to food without item ("order from Chipotle", "get delivery from McDonald's")
    FOOD_NO_ITEM_PATTERN.find(trimmed)?.let { m ->
        return ParsedGoal.FoodOrder(restaurant = m.groupValues[1].trim(), item = null, rawGoal = trimmed)
    }

    return null
}

// Returns the AppCategory for a given ParsedGoal.
fun ParsedGoal.toCategory(): AppCategory = when (this) {
    is ParsedGoal.RideRequest -> AppCategory.RIDES
    is ParsedGoal.FoodOrder -> AppCategory.FOOD_DELIVERY
}
