package com.jusika.app

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelInstaller {
    const val MODEL_NAME = "vosk-model-small-ko-0.22"
    private const val SOURCE = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    fun modelDir(files: File) = File(files, "speech-model/$MODEL_NAME")
    fun isReady(files: File) = File(files, "speech-model/installed").isFile &&
        File(modelDir(files), "am/final.mdl").isFile

    /** Installation is explicit, independent of voice standby; raw recordings are never saved. */
    @Synchronized fun download(files: File) {
        if (isReady(files)) return
        val staging = File(files, "speech-model-installing")
        staging.deleteRecursively()
        check(staging.mkdirs())
        val connection = URL(SOURCE).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            check(connection.responseCode == 200)
            check(connection.contentLengthLong in -1..150_000_000L)
            connection.inputStream.use { extract(it, staging) }
            check(File(staging, "$MODEL_NAME/am/final.mdl").isFile)
            check(File(staging, "$MODEL_NAME/conf/model.conf").isFile)
            val target = File(files, "speech-model")
            target.deleteRecursively()
            check(staging.renameTo(target))
            File(target, "installed").writeText(MODEL_NAME)
        } finally {
            connection.disconnect()
            staging.deleteRecursively()
        }
    }

    internal fun extract(source: InputStream, target: File) {
        var bytes = 0L
        var entries = 0
        val root = target.canonicalPath + File.separator
        ZipInputStream(source).use { zip ->
            while (true) {
                check(!Thread.currentThread().isInterrupted)
                val entry = zip.nextEntry ?: break
                check(++entries <= 2_000)
                check(entry.name.startsWith("$MODEL_NAME/"))
                val file = File(target, entry.name)
                check(file.canonicalPath.startsWith(root)) { "Invalid archive path" }
                if (entry.isDirectory) {
                    check(file.isDirectory || file.mkdirs())
                } else {
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.outputStream().use { output ->
                        val buffer = ByteArray(16_384)
                        while (true) {
                            check(!Thread.currentThread().isInterrupted)
                            val read = zip.read(buffer)
                            if (read < 0) break
                            bytes += read
                            check(bytes <= 512_000_000L)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
