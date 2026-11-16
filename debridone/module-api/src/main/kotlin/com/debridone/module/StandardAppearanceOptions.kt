package com.debridone.module

/** Building blocks for the options most modules offer. Each module still declares its own list. */
object StandardAppearanceOptions {
    fun accent(description: String = "Leave unset to follow the global accent colour."): AppearanceOption.Colour =
        AppearanceOption.Colour(CommonAppearanceKeys.ACCENT, "Accent colour", description)

    fun cardSize(label: String, default: String = "medium"): AppearanceOption.Choice = AppearanceOption.Choice(
        key = CommonAppearanceKeys.CARD_SIZE,
        label = label,
        choices = listOf(
            ChoiceValue("small", "Small"),
            ChoiceValue("medium", "Medium"),
            ChoiceValue("large", "Large"),
        ),
        defaultValue = default,
    )

    fun density(default: String = "comfortable"): AppearanceOption.Choice = AppearanceOption.Choice(
        key = CommonAppearanceKeys.DENSITY,
        label = "Layout density",
        choices = listOf(
            ChoiceValue("compact", "Compact"),
            ChoiceValue("comfortable", "Comfortable"),
            ChoiceValue("spacious", "Spacious"),
        ),
        defaultValue = default,
    )

    fun layout(label: String, default: String = "grid"): AppearanceOption.Choice = AppearanceOption.Choice(
        key = CommonAppearanceKeys.LAYOUT,
        label = label,
        choices = listOf(ChoiceValue("grid", "Grid"), ChoiceValue("list", "List")),
        defaultValue = default,
    )
}
