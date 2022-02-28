package com.ysj.stu.myhttp.connection

import java.io.IOException
import java.security.cert.Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * TLS 握手记录。
 */
class Handshake(
    /** [SSLSession.getProtocol] */
    val tlsVersion: String,
    /** [SSLSession.getCipherSuite] */
    val cipherSuite: String,
    /** [SSLSession.getPeerCertificates] */
    val peerCertificates: Array<Certificate>,
    /** [SSLSession.getLocalCertificates] */
    val localCertificates: Array<Certificate>,
) {

    companion object {

        @Throws(IOException::class)
        fun get(session: SSLSession): Handshake {
            val cipherSuite = session.cipherSuite
            checkNotNull(cipherSuite) { "cipherSuite == null" }
            if ("SSL_NULL_WITH_NULL_NULL" == cipherSuite) {
                throw IOException("cipherSuite == SSL_NULL_WITH_NULL_NULL")
            }

            val tlsVersion = session.protocol
            checkNotNull(tlsVersion) { "tlsVersion == null" }
            if ("NONE" == tlsVersion) throw IOException("tlsVersion == NONE")

            val peerCertificates = try {
                session.peerCertificates
            } catch (e: SSLPeerUnverifiedException) {
                emptyArray()
            }

            val localCertificates = session.localCertificates ?: emptyArray()

            return Handshake(tlsVersion, cipherSuite, peerCertificates, localCertificates)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Handshake

        if (tlsVersion != other.tlsVersion) return false
        if (cipherSuite != other.cipherSuite) return false
        if (!peerCertificates.contentEquals(other.peerCertificates)) return false
        if (!localCertificates.contentEquals(other.localCertificates)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tlsVersion.hashCode()
        result = 31 * result + cipherSuite.hashCode()
        result = 31 * result + peerCertificates.contentHashCode()
        result = 31 * result + localCertificates.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "Handshake(" +
            "tlsVersion='$tlsVersion', " +
            "cipherSuite='$cipherSuite', " +
            "peerCertificates=${peerCertificates.contentToString()}, " +
            "localCertificates=${localCertificates.contentToString()})"
    }

}