package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.secrets.SecretDescriptor
import ai.rever.boss.mcp.secrets.SecretField
import ai.rever.boss.mcp.secrets.SecretReference
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * What the operator reads for a secret-bearing call: one row per secret naming website,
 * username and field, and the sentence that keeps the dialog's other buttons honest.
 *
 * The values are not on [SecretDescriptor] at all, so there is nothing to assert absent here;
 * that guarantee is the type's, not the composable's, and `SecretReferenceInvariantTest` checks
 * the request that carries it.
 */
class SecretReferencesSectionTest {
    @get:Rule
    val rule = createComposeRule()

    private val id = "6f1d2c3e-4b5a-4c6d-8e7f-90a1b2c3d4e5"
    private val other = "00000000-0000-4000-8000-000000000001"

    @Test
    fun `one secret is announced in the singular with its descriptor`() {
        rule.setContent {
            SecretReferencesSection(
                listOf(SecretDescriptor(SecretReference(id, SecretField.PASSWORD), "github.com", "deploy-bot")),
            )
        }
        rule.onNodeWithText("This call receives 1 secret:").assertIsDisplayed()
        rule.onNodeWithText("github.com (deploy-bot) - password").assertIsDisplayed()
        rule.onNodeWithText("cannot create a durable allow rule", substring = true).assertIsDisplayed()
    }

    @Test
    fun `several secrets are counted and each gets its own row`() {
        rule.setContent {
            SecretReferencesSection(
                listOf(
                    SecretDescriptor(SecretReference(id, SecretField.PASSWORD), "github.com", "deploy-bot"),
                    SecretDescriptor(SecretReference(other, SecretField.USERNAME), "stripe.com", "billing"),
                ),
            )
        }
        rule.onNodeWithText("This call receives 2 secrets:").assertIsDisplayed()
        rule.onNodeWithText("github.com (deploy-bot) - password").assertIsDisplayed()
        rule.onNodeWithText("stripe.com (billing) - username").assertIsDisplayed()
    }
}
