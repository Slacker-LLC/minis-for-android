package com.openminis.app.ui.sandbox

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * The browser lists its first directory from `init`. A property declared below the `init` block is still null
 * at that moment, which crashed every guest-rooted browser (chat files, Settings → File management).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserViewModelInitTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aGuestRootedBrowserCanBeCreated() {
        val vm = FileBrowserViewModel(
            rootPath = File("/tmp/guest-root"),
            guestRootPath = "/var/minis/workspace",
            guestSessionId = "session-1",
        )
        assertNotNull(vm.uiState.value)
    }
}
