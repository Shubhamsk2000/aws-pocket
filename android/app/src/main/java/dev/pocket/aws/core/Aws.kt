package dev.pocket.aws.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class Instance(val state: String, val publicIp: String?, val type: String?, val name: String?)

/**
 * Minimal EC2 client (Query API + SigV4) to start / stop the instance and learn its new public IP.
 * Needs an IAM user limited to ec2:DescribeInstances, ec2:StartInstances, ec2:StopInstances.
 */
object Aws {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))
    private fun hmac(key: ByteArray, s: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(s.toByteArray())
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private suspend fun call(s: Server, action: String): String = withContext(Dispatchers.IO) {
        val region = s.awsRegion.trim()
        val host = "ec2.$region.amazonaws.com"
        val params = sortedMapOf("Action" to action, "Version" to "2016-11-15", "InstanceId.1" to s.awsInstance.trim())
        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val now = Date()
        val amzDate = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val day = amzDate.substring(0, 8)
        val canonical = "GET\n/\n$query\nhost:$host\nx-amz-date:$amzDate\n\nhost;x-amz-date\n${sha256("")}"
        val scope = "$day/$region/ec2/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256(canonical)}"
        var k = hmac(("AWS4" + s.awsSecret.trim()).toByteArray(), day)
        k = hmac(k, region); k = hmac(k, "ec2"); k = hmac(k, "aws4_request")
        val sig = hex(hmac(k, toSign))
        val conn = URL("https://$host/?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 10000
        conn.setRequestProperty("x-amz-date", amzDate)
        conn.setRequestProperty("Authorization", "AWS4-HMAC-SHA256 Credential=${s.awsAccess.trim()}/$scope, SignedHeaders=host;x-amz-date, Signature=$sig")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = Regex("<Message>(.*?)</Message>").find(body)?.groupValues?.get(1) ?: "AWS error $code"
            throw IllegalStateException(msg)
        }
        body
    }

    suspend fun describe(s: Server): Instance {
        val x = call(s, "DescribeInstances")
        val inst = x.substringAfter("<instancesSet>").substringAfter("<item>")
        val state = Regex("<instanceState>\\s*<code>\\d+</code>\\s*<name>(\\w+)</name>").find(inst)?.groupValues?.get(1) ?: "unknown"
        val ip = Regex("<ipAddress>([\\d.]+)</ipAddress>").find(inst)?.groupValues?.get(1)
        val type = Regex("<instanceType>([^<]+)</instanceType>").find(inst)?.groupValues?.get(1)
        val name = Regex("<key>Name</key>\\s*<value>([^<]*)</value>").find(inst)?.groupValues?.get(1)
        return Instance(state, ip, type, name)
    }

    suspend fun start(s: Server) { call(s, "StartInstances") }
    suspend fun stop(s: Server) { call(s, "StopInstances") }
}
