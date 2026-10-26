package app.routine.core.model

import kotlinx.serialization.Serializable

/**
 * Pre-built one-tap automation recipe displayed on Routine's home screen.
 */
@Serializable
data class Template(
    val id: String,
    val title: String,
    val description: String,
    val iconName: String,
    val rule: Rule,
    val requiredTier: PrivilegeTier,
) {
    /**
     * Validates template metadata and its embedded [Rule].
     */
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        if (id.isBlank()) errors += "Template id must not be blank."
        if (title.isBlank()) errors += "Template title must not be blank."
        if (description.isBlank()) errors += "Template description must not be blank."
        if (iconName.isBlank()) errors += "Template iconName must not be blank."
        when (val ruleValidation = rule.validate()) {
            ValidationResult.Valid -> Unit
            is ValidationResult.Invalid -> errors += ruleValidation.reasons
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}
