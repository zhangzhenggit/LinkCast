package com.linkcast.receiver

interface AuthProvider {
    val certificate: ByteArray?
    /** True when this provider can actually produce MFi challenge signatures. */
    val canSign: Boolean get() = false
    fun respond(challenge: ByteArray, length: Int): ByteArray?
    fun release()
}

object EmptyAuthProvider : AuthProvider {
    override val certificate: ByteArray? = null
    override val canSign: Boolean = false
    override fun respond(challenge: ByteArray, length: Int): ByteArray? = null
    override fun release() = Unit
}
