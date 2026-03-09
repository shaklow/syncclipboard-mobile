package expo.modules.nativeutil

import android.net.Uri
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NativeUtilModule : Module() {
    companion object {
        private const val CHUNK_SIZE = 4 * 1024 * 1024
        private const val UPLOAD_BUFFER_SIZE = 8 * 1024
        private const val EVENT_HASH_PROGRESS = "onHashProgress"
    }

    private val executor = Executors.newCachedThreadPool()
    private val cancelFlags = ConcurrentHashMap<String, AtomicBoolean>()
    private val pendingJobs = ConcurrentHashMap<String, CompletableFuture<Any>>()

    override fun definition() = ModuleDefinition {
        Name("NativeUtilModule")

        Events(EVENT_HASH_PROGRESS)

        Function("startCalculateFileHash") { fileUri: String ->
            val jobId = UUID.randomUUID().toString()
            val cancelFlag = AtomicBoolean(false)
            cancelFlags[jobId] = cancelFlag
            val future = CompletableFuture<Any>()
            pendingJobs[jobId] = future

            executor.submit {
                try {
                    val path = resolveFilePath(fileUri)
                    val file = File(path)

                    if (!file.exists()) {
                        cancelFlags.remove(jobId)
                        future.complete(FileNotFoundException(path))
                        return@submit
                    }

                    val totalBytes = file.length()
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(CHUNK_SIZE)
                    var bytesRead = 0L

                    FileInputStream(file).use { stream ->
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1) {
                            if (cancelFlag.get()) {
                                cancelFlags.remove(jobId)
                                future.complete(CancelledException())
                                return@submit
                            }

                            digest.update(buffer, 0, read)
                            bytesRead += read

                            if (totalBytes > 0) {
                                val progress = bytesRead.toDouble() / totalBytes.toDouble()
                                sendEvent(EVENT_HASH_PROGRESS, mapOf(
                                    "progress" to progress,
                                    "bytesRead" to bytesRead.toDouble(),
                                    "totalBytes" to totalBytes.toDouble()
                                ))
                            }
                        }
                    }

                    cancelFlags.remove(jobId)

                    if (cancelFlag.get()) {
                        future.complete(CancelledException())
                        return@submit
                    }

                    val hashBytes = digest.digest()
                    val hashHex = hashBytes.joinToString("") { "%02x".format(it) }.uppercase()
                    future.complete(hashHex)
                } catch (e: Exception) {
                    cancelFlags.remove(jobId)
                    future.complete(HashErrorException(e.message ?: "Unknown error", e))
                }
            }

            return@Function jobId
        }

        AsyncFunction("waitForJob") { jobId: String, promise: Promise ->
            val future = pendingJobs.remove(jobId)
            if (future == null) {
                promise.reject(JobNotFoundException(jobId))
                return@AsyncFunction
            }

            executor.submit {
                try {
                    val result = future.get()
                    when (result) {
                        is String -> promise.resolve(result)
                        is CodedException -> promise.reject(result)
                        else -> promise.reject(UnexpectedResultException(result?.javaClass?.simpleName ?: "null"))
                    }
                } catch (e: Exception) {
                    promise.reject(WaitForJobException(e.message ?: "Unknown error", e))
                }
            }
        }

        AsyncFunction("cancelJob") { jobId: String ->
            cancelFlags[jobId]?.set(true)
        }

        AsyncFunction("copyFile") { srcUri: String, destUri: String, promise: Promise ->
            executor.submit {
                try {
                    val srcPath = resolveFilePath(srcUri)
                    val src = File(srcPath)
                    if (!src.exists()) {
                        promise.reject(FileNotFoundException(srcPath))
                        return@submit
                    }

                    val dest = Uri.parse(destUri)
                    val outputStream = appContext.reactContext?.contentResolver?.openOutputStream(dest)
                        ?: run {
                            promise.reject(OpenFailedException(destUri))
                            return@submit
                        }

                    FileInputStream(src).channel.use { srcChannel ->
                        outputStream.use { output ->
                            val destChannel = Channels.newChannel(output)
                            var position = 0L
                            val size = srcChannel.size()
                            while (position < size) {
                                position += srcChannel.transferTo(position, size - position, destChannel)
                            }
                        }
                    }

                    promise.resolve(null)
                } catch (e: Exception) {
                    promise.reject(CopyErrorException(e.message ?: "Unknown error", e))
                }
            }
        }

        Function("startUploadFile") { url: String, headers: Map<String, String>, fileUri: String ->
            val jobId = UUID.randomUUID().toString()
            val cancelFlag = AtomicBoolean(false)
            cancelFlags[jobId] = cancelFlag
            val future = CompletableFuture<Any>()
            pendingJobs[jobId] = future

            executor.submit {
                var connection: HttpURLConnection? = null
                try {
                    val path = resolveFilePath(fileUri)
                    val file = File(path)

                    if (!file.exists()) {
                        cancelFlags.remove(jobId)
                        future.complete(FileNotFoundException(path))
                        return@submit
                    }

                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "PUT"
                        doOutput = true
                        connectTimeout = 30_000
                        readTimeout = 0

                        setFixedLengthStreamingMode(file.length())

                        headers.forEach { (key, value) ->
                            setRequestProperty(key, value)
                        }
                    }

                    connection.outputStream.use { output ->
                        FileInputStream(file).use { input ->
                            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (cancelFlag.get()) {
                                    cancelFlags.remove(jobId)
                                    future.complete(CancelledException())
                                    return@submit
                                }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }

                    val responseCode = connection.responseCode
                    cancelFlags.remove(jobId)

                    if (responseCode in 200..299) {
                        future.complete("success")
                    } else {
                        val body = try {
                            connection.errorStream?.bufferedReader()?.readText() ?: ""
                        } catch (_: Exception) { "" }
                        future.complete(HttpErrorException(responseCode, body))
                    }
                } catch (e: Exception) {
                    cancelFlags.remove(jobId)
                    future.complete(UploadErrorException(e.message ?: "Unknown error", e))
                } finally {
                    connection?.disconnect()
                }
            }

            return@Function jobId
        }

        Function("startDownloadFile") { url: String, headers: Map<String, String>, fileUri: String ->
            val jobId = UUID.randomUUID().toString()
            val cancelFlag = AtomicBoolean(false)
            cancelFlags[jobId] = cancelFlag
            val future = CompletableFuture<Any>()
            pendingJobs[jobId] = future

            executor.submit {
                var connection: HttpURLConnection? = null
                try {
                    val path = resolveFilePath(fileUri)
                    val file = File(path)
                    val parentDir = file.parentFile

                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs()
                    }

                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 30_000
                        readTimeout = 0

                        headers.forEach { (key, value) ->
                            setRequestProperty(key, value)
                        }
                    }

                    val responseCode = connection.responseCode

                    if (responseCode !in 200..299) {
                        cancelFlags.remove(jobId)
                        val body = try {
                            connection.errorStream?.bufferedReader()?.readText() ?: ""
                        } catch (_: Exception) { "" }
                        future.complete(HttpErrorException(responseCode, body))
                        return@submit
                    }

                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(UPLOAD_BUFFER_SIZE)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                if (cancelFlag.get()) {
                                    cancelFlags.remove(jobId)
                                    file.delete()
                                    future.complete(CancelledException())
                                    return@submit
                                }
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }

                    cancelFlags.remove(jobId)
                    future.complete("success")
                } catch (e: Exception) {
                    cancelFlags.remove(jobId)
                    future.complete(DownloadErrorException(e.message ?: "Unknown error", e))
                } finally {
                    connection?.disconnect()
                }
            }

            return@Function jobId
        }
    }

    private fun resolveFilePath(fileUri: String): String {
        return if (fileUri.startsWith("file://", ignoreCase = true)) {
            Uri.parse(fileUri).path ?: fileUri.removePrefix("file://")
        } else {
            fileUri
        }
    }
}

class JobNotFoundException(jobId: String) : CodedException("Job not found: $jobId")

class FileNotFoundException(path: String) : CodedException("File not found: $path")

class OpenFailedException(uri: String) : CodedException("Cannot open output stream for: $uri")

class CopyErrorException(message: String, cause: Throwable? = null) : CodedException(message, cause)

class HashErrorException(message: String, cause: Throwable? = null) : CodedException(message, cause)

class CancelledException : CodedException("Operation was cancelled")

class HttpErrorException(statusCode: Int, body: String) : CodedException("HTTP $statusCode: $body")

class UploadErrorException(message: String, cause: Throwable? = null) : CodedException(message, cause)

class DownloadErrorException(message: String, cause: Throwable? = null) : CodedException(message, cause)

class UnexpectedResultException(type: String) : CodedException("Unexpected result type: $type")

class WaitForJobException(message: String, cause: Throwable? = null) : CodedException(message, cause)
