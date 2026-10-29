package io.github.meko123456.ardaivitsko

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** ViewModels run on Dispatchers.Main; here it's an eager test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}

/**
 * Keeps a StateFlow's upstream running for the test, eagerly. Never advance the test clock until
 * idle with a ViewModel alive: its 30-second ticker would run forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepCollecting(flow: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/** Everything a flow of one-off events sends while the test runs. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.record(flow: Flow<T>): List<T> {
    val seen = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
    return seen
}
