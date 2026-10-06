package com.openminis.app.ui.preview

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The real WebView: a previewed page may read its own folder but not the app's private files. */
@RunWith(AndroidJUnit4::class)
class PreviewFileJailInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pageDir: File
    private lateinit var secret: File
    private var holder: WebViewHolder? = null

    @Before
    fun setUp() {
        UbuntuPaths.initialize(context)
        pageDir = File(UbuntuPaths.hostSessions, "jail-test").apply { mkdirs() }
        File(pageDir, "ok.txt").writeText("inside")
        secret = File(context.filesDir, "jail-secret.txt").apply { writeText("private") }
        File(pageDir, "index.html").writeText(
            """
            <html><body><script>
            function read(url) {
              return new Promise(function (resolve) {
                var x = new XMLHttpRequest();
                x.onload = function () { resolve(x.status + ':' + x.responseText); };
                x.onerror = function () { resolve('error'); };
                x.open('GET', url);
                x.send();
              });
            }
            Promise.all([read('ok.txt'), read('file://${secret.absolutePath}')]).then(function (r) {
              document.title = 'inside=' + r[0] + ' | outside=' + r[1];
            });
            </script></body></html>
            """.trimIndent(),
        )
    }

    @After
    fun tearDown() {
        Handler(Looper.getMainLooper()).post { holder?.destroy() }
        pageDir.deleteRecursively()
        secret.delete()
    }

    @Test
    fun pageReadsItsOwnFolderButNotTheAppsPrivateFiles() {
        val created = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val url = "file://${File(pageDir, "index.html").absolutePath}"
            holder = WebViewHolder(context, url)
            // startIfNeeded() waits for a window; the client under test is the same either way.
            holder!!.webView.loadUrl(url)
            created.countDown()
        }
        assertEquals(true, created.await(5, TimeUnit.SECONDS))

        var title = ""
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !title.startsWith("inside=")) {
            Thread.sleep(200)
            val got = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                title = holder?.webView?.title.orEmpty()
                got.countDown()
            }
            got.await(2, TimeUnit.SECONDS)
        }
        assertEquals(true, title.startsWith("inside="))
        assertEquals(true, title.contains("inside=200:inside") || title.contains("inside=0:inside"))
        assertEquals("the private file must not be readable: $title", false, title.contains("private"))
    }
}
