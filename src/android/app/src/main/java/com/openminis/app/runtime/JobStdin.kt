package com.openminis.app.runtime

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The standard input of one background job: a named pipe the app owns the writing end of.
 *
 * The job's shell is fed its commands over its own stdin, so a command that read stdin would eat the shell's
 * protocol lines; the job's command therefore reads this pipe instead (`{ command } < pipe`). The app holds the
 * writing end open for as long as the job lives, which is what makes it behave like a terminal's stdin: a program
 * that reads a line gets it and waits for the next one, and [close] gives it end-of-file. The pipe sits in the
 * session's `/tmp`, which the app creates and the guest sees at the same place.
 */
internal class JobStdin(
    private val host: File,
    /** Where the guest sees [host]. */
    val guestPath: String,
    private val makeFifo: (File) -> Unit = { Os.mkfifo(it.path, 384 /* 0600 */) },
) {
    @Volatile private var out: FileOutputStream? = null
    @Volatile private var closed = false
    private val opened = CountDownLatch(1)
    private var opener: Thread? = null

    /** Creates the pipe and starts waiting for the job to open its reading end. False when the pipe cannot be made. */
    fun create(): Boolean {
        return try {
            host.delete()
            makeFifo(host)
            // Opening a pipe for writing waits for a reader, so it happens off to the side; the job's shell opens
            // the reading end the moment it starts the command.
            opener = Thread({
                try {
                    out = FileOutputStream(host)
                } catch (_: IOException) {
                    // closed before a reader came
                } finally {
                    opened.countDown()
                }
            }, "job-stdin-open").apply { isDaemon = true; start() }
            true
        } catch (_: Exception) {
            host.delete()
            false
        }
    }

    /** Writes [data] to the job; when [eof], the job's stdin is closed afterwards. Null on success, else why not. */
    suspend fun write(data: String, eof: Boolean, waitForJobMs: Long = START_WAIT_MS): String? = withContext(Dispatchers.IO) {
        if (closed) return@withContext "the job's input is already closed"
        if (!opened.await(waitForJobMs, TimeUnit.MILLISECONDS)) return@withContext "the job has not started reading its input yet; try again in a moment"
        val stream = out ?: return@withContext "the job's input is closed"
        try {
            synchronized(this@JobStdin) {
                if (closed) return@withContext "the job's input is already closed"
                if (data.isNotEmpty()) {
                    stream.write(data.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                if (eof) closeLocked()
            }
            null
        } catch (_: IOException) {
            "the job no longer reads input (it has finished or closed its stdin)"
        }
    }

    /** Ends the job's input and removes the pipe. Safe to call more than once. */
    fun close() {
        synchronized(this) { closeLocked() }
        // A writer still waiting for a reader is released by opening the other end ourselves.
        if (opened.count > 0L) {
            runCatching { java.io.FileInputStream(host).close() }
            runCatching { opener?.join(500) }
        }
        host.delete()
    }

    private fun closeLocked() {
        closed = true
        runCatching { out?.close() }
    }

    companion object {
        const val START_WAIT_MS = 10_000L

        /**
         * The script that runs [command] reading its stdin from the pipe at [guestPath]. Should the guest not see the
         * pipe (it is checked, not assumed), the command runs as it always did, without input, rather than failing.
         */
        fun wrap(command: String, guestPath: String): String =
            "__minis_job() {\n$command\n}\nif [ -p '$guestPath' ]; then __minis_job < '$guestPath'; else __minis_job; fi"
    }
}
