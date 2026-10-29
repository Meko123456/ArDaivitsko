package io.github.meko123456.ardaivitsko.ui

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.meko123456.ardaivitsko.MainActivity
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.OccurrenceState
import io.github.meko123456.ardaivitsko.deleteAllTasks
import io.github.meko123456.ardaivitsko.time.today
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** A user adds a task on Today and marks it Done there, through the real screens and service. */
@RunWith(AndroidJUnit4::class)
class AddTaskAndDoneTest {

    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = compose.activity.appContainer

    @After
    fun cleanUp() = container.deleteAllTasks()

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun add_a_task_and_mark_it_done() {
        waitFor(hasText("Nothing for today", substring = true))

        // The extended FAB clears its text's semantics; its icon carries the label.
        compose.onNodeWithContentDescription("Add task").performClick()
        waitFor(hasText("New task"))
        compose.onNodeWithText("Title").performTextInput("Pay the credit card bill")
        compose.onNodeWithText("Save").performClick()

        waitFor(hasContentDescription("Done: Pay the credit card bill"))
        compose.onNodeWithText("Due today by", substring = true).assertExists()
        compose.onNodeWithContentDescription("Done: Pay the credit card bill").performClick()

        waitFor(hasContentDescription("Mark not done: Pay the credit card bill"))
        compose.onNodeWithText("Done: Pay the credit card bill").assertExists() // the snackbar, with Undo
        val saved = runBlocking {
            container.store.observeActive(container.time.today()).first().single { it.task.title == "Pay the credit card bill" }
        }
        assertEquals(OccurrenceState.DONE, saved.state)
    }
}
