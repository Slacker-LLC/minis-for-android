package com.openminis.app.util

/**
 * Quote [value] as one POSIX shell word.
 *
 * The whole value goes inside single quotes, where the shell expands nothing;
 * each embedded single quote closes the quoted run, adds a double-quoted `'`
 * and reopens it. Every script the app builds for `sh`, `su` or the guest
 * shell quotes its arguments through this one function.
 */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"
