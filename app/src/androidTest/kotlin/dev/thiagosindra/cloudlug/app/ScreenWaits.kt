package dev.thiagosindra.cloudlug.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText

/**
 * Waiting and failing legibly, shared by the journey tests.
 *
 * The app works off the main thread — the engine, Room, the accounts list —
 * so every step is awaited rather than clicked into a screen that has not
 * composed yet.
 *
 * Everything here reads the **unmerged** semantics tree. Matching the merged
 * tree found nothing while the screen plainly held the text: the failure that
 * established this reported `Never found "New Transfer". On screen: ... |
 * CloudLug | New Transfer`, the two halves disagreeing because the diagnosis
 * read the unmerged tree and the wait did not. That is the observation; why
 * Material3's FAB does not surface its label to a merged-tree text match is not
 * something these tests need to settle.
 *
 * ### Why the diagnosis is one line
 *
 * A bare `waitUntil` fails with `Condition still not satisfied after 15000 ms`
 * and nothing else, which costs a seven-minute emulator cycle and answers
 * nothing — it cannot even distinguish "the text is missing" from "the app
 * crashed and is showing the crash reporter". This is not hypothetical: the
 * first version of `AccountsScreenTest` used a bare `waitUntil` and failed in
 * CI with exactly that message.
 *
 * An earlier version of the diagnosis printed the whole semantics tree, which
 * was the right idea on the wrong channel: Gradle's console kept the first two
 * lines of the message and dropped the tree. Hence one line, every string on
 * screen, joined.
 */
fun ComposeTestRule.awaitText(text: String, timeoutMillis: Long = 20_000) {
    try {
        waitUntil(timeoutMillis) {
            onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError(diagnose(text), timeout)
    }
}

/** Waits for [text] to exist, then returns it for clicking. */
fun ComposeTestRule.node(text: String): SemanticsNodeInteraction {
    awaitText(text)
    return onNodeWithText(text, useUnmergedTree = true)
}

/**
 * Waits for a node labelled [description], then returns it for clicking.
 *
 * The source picker's checkbox carries no text — it is a checkbox — and since
 * the row it sits on now opens a folder rather than selecting it, the checkbox
 * is the only way to take a folder whole. So a test has to be able to aim at it.
 */
fun ComposeTestRule.labelled(description: String): SemanticsNodeInteraction {
    try {
        waitUntil(20_000) {
            onAllNodesWithContentDescription(description, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError(diagnose(description), timeout)
    }
    return onNodeWithContentDescription(description, useUnmergedTree = true)
}

/** Every string the screen is currently drawing, flattened. */
fun ComposeTestRule.textsOnScreen(): List<String> =
    onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

private fun ComposeTestRule.diagnose(missing: String): String {
    val onScreen = runCatching { textsOnScreen() }
        .getOrElse { return "Never found \"$missing\", and the screen could not be read: $it" }

    val prefix = if (onScreen.any { it.startsWith(CRASH_SCREEN_TITLE) }) {
        // Not a flaw in the test. The instrumented tests share one app data
        // directory, so a crash recorded by an earlier one is displayed on the
        // next launch — meaning the app threw an uncaught exception, which
        // matters far more than the assertion that tripped over it.
        "THE APP CRASHED EARLIER: MainActivity is showing the crash reporter, " +
            "not the screen under test. Fix the exception below, not this test. "
    } else {
        ""
    }

    return prefix + "Never found \"$missing\". On screen: " +
        onScreen.joinToString(" | ") { it.replace('\n', ' ') }.take(3000)
}

private const val CRASH_SCREEN_TITLE = "CloudLug crashed last time"
