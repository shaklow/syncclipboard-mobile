package io.github.erenche.syncclipboard.xposed.api

import android.util.Base64
import io.github.erenche.syncclipboard.common.model.ClipboardContent
import io.github.erenche.syncclipboard.common.model.ClipboardContentType
import io.github.erenche.syncclipboard.common.model.ProfileDto
import io.github.erenche.syncclipboard.common.util.HashUtils
import io.github.erenche.syncclipboard.common.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.serialization.json.Json
import java.io.File

/**
 * SyncClipboard 服务器 HTTP 客户端。
 *
 * 端口自 TypeScript SyncClipboardClient.ts。
 * 使用 Ktor HttpClient + kotlinx.serialization。
 */
class SyncClipboardHttpClient(
    private val baseUrl: String,
    private val username: String? = null,
    private val password: String? = null
) : SyncClipboardApi {

    companion object {
        private const val TAG = "SyncClipboardHttp"
        private const val CLIPBOARD_ENDPOINT = "/SyncClipboard.json"
        private const val FILE_ENDPOINT = "/file/"
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
    }

    private fun buildAuthHeader(): String {
        val credentials = "$username:$password"
        return "Basic " + Base64.encodeToString(
            credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    override suspend fun getClipboard(): ProfileDto? {
        return try {
            val response = client.get("$baseUrl$CLIPBOARD_ENDPOINT") {
                if (username != null && password != null) {
                    header(HttpHeaders.Authorization, buildAuthHeader())
                }
            }
            android.util.Log.d("SyncClipboard", "[SyncClipboardHttp] GET $baseUrl$CLIPBOARD_ENDPOINT -> status=${response.status.value}")
            if (response.status.value !in 200..299) {
                val body = response.bodyAsText()
                android.util.Log.w("SyncClipboard", "[SyncClipboardHttp] Server returned ${response.status.value}: ${body.take(300)}")
                return null
            }
            response.body<ProfileDto>()
        } catch (e: Exception) {
            Logger.error(TAG, "Failed to get clipboard: ${e.message}", e)
            null
        }
    }

    override suspend fun putClipboard(profile: ProfileDto) {
        client.put("$baseUrl$CLIPBOARD_ENDPOINT") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
            contentType(ContentType.Application.Json)
            setBody(profile)
        }
    }

    override suspend fun downloadFile(
        fileName: String,
        destinationPath: String,
        onProgress: ((Float) -> Unit)?
    ): String {
        val destFile = File(destinationPath)
        destFile.parentFile?.mkdirs()

        client.get("$baseUrl$FILE_ENDPOINT${java.net.URLEncoder.encode(fileName, "UTF-8")}") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
        }.bodyAsChannel().toInputStream().use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Logger.info(TAG, "File downloaded: $fileName -> $destinationPath")
        return destinationPath
    }

    override suspend fun putFile(fileName: String, filePath: String, onProgress: ((Float) -> Unit)?) {
        val file = File(filePath)
        if (!file.exists()) throw IllegalStateException("File not found: $filePath")

        // Upload via multipart or raw bytes depending on server implementation
        client.put("$baseUrl$FILE_ENDPOINT${java.net.URLEncoder.encode(fileName, "UTF-8")}") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
            setBody(file.readBytes())
        }
    }

    override suspend fun putContent(content: ClipboardContent) {
        // 如果有文件数据，先上传文件
        if (content.hasData && content.fileUri != null && content.fileName != null) {
            val name = content.fileName!!
            val uri = content.fileUri!!
            putFile(name, uri)
        }

        // 计算 profile hash
        val hash = HashUtils.computeProfileHash(
            content.type.name.lowercase(),
            content.text
        )

        // 上传 profile
        val profile = ProfileDto(
            type = content.type,
            hash = hash,
            text = content.text,
            hasData = content.hasData,
            dataName = content.fileName,
            size = content.fileSize
        )
        putClipboard(profile)
    }

    override suspend fun testConnection() {
        val response = client.get("$baseUrl$CLIPBOARD_ENDPOINT") {
            if (username != null && password != null) {
                header(HttpHeaders.Authorization, buildAuthHeader())
            }
        }
        if (response.status.value !in 200..299) {
            val body = response.bodyAsText()
            throw IllegalStateException("Server returned ${response.status.value}: ${body.take(200)}")
        }
        Logger.info(TAG, "Connection test successful")
    }
}
