package com.celica.dash.obd

/**
 * A byte pipe to an ELM327. Bluetooth SPP in the car; a scripted one in tests. The protocol
 * layer only ever writes a line and reads until the '>' prompt, so this is all it needs.
 */
interface ObdTransport {
    val name: String
    suspend fun open()
    suspend fun write(bytes: ByteArray)
    /** Read whatever is available, blocking up to [timeoutMs]; empty array on timeout. */
    suspend fun read(timeoutMs: Long): ByteArray
    fun close()
}

class ObdException(msg: String) : Exception(msg)
