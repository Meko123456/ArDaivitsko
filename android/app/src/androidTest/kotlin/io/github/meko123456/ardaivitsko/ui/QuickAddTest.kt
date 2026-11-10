package io.github.meko123456.ardaivitsko.ui

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.meko123456.ardaivitsko.MainActivity
import io.github.meko123456.ardaivitsko.appContainer
import io.github.meko123456.ardaivitsko.core.Repeat
import io.github.meko123456.ardaivitsko.deleteAllTasks
import io.github.meko123456.ardaivitsko.time.today
import io.github.meko123456.ardaivitsko.ui.quickadd.QUICK_ADD_TITLE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime
import kotlin.test.assertEquals

/**
 * A user taps the mic on Today and says a task. The phone's speech recognizer is stood in for by
 * Espresso Intents, which answers the recognizer intent with the words, so the test runs the same
 * on a phone with Google's recognizer and on an emulator without one.
 */
@RunWith(AndroidJUnit4::class)
class QuickAddTest {

    @get:Rule(order = 0) val notifications: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = compose.activity.appContainer

    @Before
    fun stubTheRecognizer() {
        Intents.init()
        val heard = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("remind me to renew the parking permit tomorrow at 10"))
        Intents.intending(hasAction(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, heard))
    }

    @After
    fun cleanUp() {
        Intents.release()
        container.deleteAllTasks()
    }

    private fun waitFor(matcher: SemanticsMatcher) =
        compose.waitUntil(10_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun say_a_task_and_add_it() {
        waitFor(hasText("Nothing for today", substring = true))

        compose.onNodeWithContentDescription("Say a task").performClick()
        waitFor(hasText("Say a task"))
        // The stubbed recognizer answered at once: the sentence is in the field and read underneath.
        waitFor(hasText("Renew the parking permit"))
        compose.onNodeWithTag(QUICK_ADD_TITLE_TAG).assertTextEquals("Renew the parking permit")
        compose.onNodeWithText("10:00", substring = true).assertExists()
        // Below the fold on a small screen.
        compose.onNodeWithText("Add task").performScrollTo().performClick()

        waitFor(hasText("Nothing for today", substring = true)) // tomorrow's task isn't on Today
        val task = runBlocking { container.store.observeSeries().first().single().task }
        assertEquals("Renew the parking permit", task.title)
        assertEquals(container.time.today().plusDays(1), task.day)
        assertEquals(LocalTime.of(10, 0), task.deadline)
        assertEquals(Repeat.NEVER, task.repeat)
    }
}
