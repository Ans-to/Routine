package app.routine.feature.templates

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.routine.core.model.ActionType
import app.routine.core.model.PrivilegeTier
import app.routine.core.model.Template
import app.routine.core.model.TriggerType
import app.routine.core.model.ValidationResult
import app.routine.core.permissions.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TemplateCatalogAndActivationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `all 40 built-in templates validate cleanly with unique IDs, full trigger coverage, and safe keys`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val coreProviders = BuiltInTemplatesModule.provideBuiltInTemplates()
        val researchedProviders = ResearchedTemplatesModule.provideResearchedTemplates()
        assertEquals(20, coreProviders.size)
        assertEquals(20, researchedProviders.size)

        val providers = BuiltInTemplateCatalog.allProviders().sortedBy { it.order }
        assertEquals(40, providers.size)
        assertEquals((1..40).toList(), providers.map { it.order })

        val templates = providers.map { it.provide(context) }
        assertEquals(40, templates.map { it.id }.toSet().size)
        assertEquals(40, templates.map { it.title }.toSet().size)

        val safeKeyRegex = Regex("^[a-zA-Z0-9_.]+$")
        val validNamespaces = setOf("SECURE", "GLOBAL", "SYSTEM")
        templates.forEach { tpl ->
            val validation = tpl.validate()
            assertEquals("Invalid template ${tpl.id}: $validation", ValidationResult.Valid, validation)
            tpl.rule.actions
                .filter { it.type == ActionType.SECURE_SETTING }
                .forEach { action ->
                    val ns = action.config["namespace"] ?: "SECURE"
                    val key = action.config["key"].orEmpty()
                    assertTrue("Invalid namespace '$ns' in ${tpl.id}", ns in validNamespaces)
                    assertTrue("Unsafe setting key '$key' in ${tpl.id}", safeKeyRegex.matches(key))
                }
        }

        val coveredTriggers = templates.map { it.rule.trigger.type }.toSet()
        assertEquals(TriggerType.entries.toSet(), coveredTriggers)

        val tiers = templates.map { it.requiredTier }.toSet()
        assertTrue(PrivilegeTier.STANDARD in tiers)
        assertTrue(PrivilegeTier.ACCESSIBILITY in tiers)
        assertTrue(PrivilegeTier.PRIVILEGED in tiers)

        val zeroPermissionIds = templates
            .filter { it.rule.requiredPermissions().isEmpty() }
            .map { it.id }
            .toSet()
        assertTrue("tpl_05_battery_saver_20" in zeroPermissionIds)
        assertTrue("tpl_40_wakeup_music_alarm" in zeroPermissionIds)
    }

    @Test
    fun `tapping a template card and clicking Activate invokes rule creation callback`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sample = ResearchedTemplateCatalog.providers().maxBy { it.order }.provide(context)
        var activatedTemplate: Template? = null
        var activatedName: String? = null

        composeTestRule.setContent {
            MaterialTheme {
                TemplatesGridContent(
                    cards = listOf(TemplateCardUiModel(sample, Capability.Ready)),
                    onActivate = { tpl, customName ->
                        activatedTemplate = tpl
                        activatedName = customName
                    },
                    onNavigateToPrivilegeSetup = {},
                    onRequestRuntimePermission = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("template_card_${sample.id}").performClick()
        composeTestRule.onNodeWithTag("confirm_activate_template_button").performClick()

        assertNotNull(activatedTemplate)
        assertEquals(sample.id, activatedTemplate?.id)
        assertEquals(sample.title, activatedName)
    }
}
