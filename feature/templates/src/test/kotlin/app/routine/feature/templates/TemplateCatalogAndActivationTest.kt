package app.routine.feature.templates

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.routine.core.model.PrivilegeTier
import app.routine.core.model.Template
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
    fun `all 20 built-in templates validate cleanly with unique IDs and expected tiers`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val providers = BuiltInTemplateCatalog.allProviders().sortedBy { it.order }
        assertEquals(20, providers.size)

        val templates = providers.map { it.provide(context) }
        assertEquals(20, templates.map { it.id }.toSet().size)

        templates.forEach { tpl ->
            val validation = tpl.validate()
            assertEquals("Invalid template ${tpl.id}: $validation", ValidationResult.Valid, validation)
        }

        val tiers = templates.map { it.requiredTier }.toSet()
        assertTrue(PrivilegeTier.STANDARD in tiers)
        assertTrue(PrivilegeTier.ACCESSIBILITY in tiers)
        assertTrue(PrivilegeTier.PRIVILEGED in tiers)
    }

    @Test
    fun `tapping a template card and clicking Activate invokes rule creation callback`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val sample = BuiltInTemplateCatalog.allProviders().minBy { it.order }.provide(context)
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
