package com.openminis.app.remote

/** Shared JSON-RPC error identity; transports preserve the existing wire code. */
internal class RPCException(val code: Int, override val message: String) : Exception(message)
