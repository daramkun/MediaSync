@file: JvmName("FilesHelper")

package `in`.daram.mediasync

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Environment.DIRECTORY_MUSIC
import android.os.storage.StorageManager
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

fun requestPermissions(activity: Activity) {
    ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO), 0)
}

fun checkPermissions(activity: Activity): Boolean {
    return ActivityCompat.checkSelfPermission(
        activity,
        android.Manifest.permission.READ_MEDIA_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
}

fun getMusicFolderPath(context: Context): String {
    val storageService = (context.getSystemService(Context.STORAGE_SERVICE) as StorageManager)
    val storageVolumes = storageService.storageVolumes

    storageVolumes.sortBy { volume ->
        if (volume.isRemovable && volume.state == Environment.MEDIA_MOUNTED)
            return@sortBy -1
        if (volume.isPrimary)
            return@sortBy 1
        return@sortBy 0
    }

    val selectedVolume = storageVolumes.first()
    if (selectedVolume.isPrimary) {
        Log.i("MediaSync", "No SDCard. Selected Primary Storage")
        return "/storage/self/primary/Music"
    }

    val uuid = selectedVolume.uuid
    Log.i("MediaSync", "SDCard: \"$uuid\"")
    return "/storage/$uuid/Music"
}

fun getMusicFolderFiles(context: Context): Array<String> {
    val path = getMusicFolderPath(context)
    val root = File(path)
    val result = ArrayList<String>(128)
    enumerateFolderFiles(root, result)

    val count = result.size
    Log.i("MediaSync", "Total Storage Music file count: $count")
    return result.toTypedArray()
}

private fun enumerateFolderFiles(root: File, result: ArrayList<String>) {
    val fileList = root.listFiles()
    if (fileList == null || fileList.size == 0) {
        return
    }

    for (file in fileList) {
        if (file.isDirectory) {
            enumerateFolderFiles(file, result)
        } else if (file.isFile) {
            val valid = file.path.endsWith(".mp3") || file.path.endsWith(".m4a") ||
                    file.path.endsWith(".flac") || file.path.endsWith(".alac") ||
                    file.path.endsWith(".wma") || file.path.endsWith(".mka") ||
                    file.path.endsWith(".opus") || file.path.endsWith(".ogg") ||
                    file.path.endsWith(".wav") || file.path.endsWith(".aiff") ||
                    file.path.endsWith(".aac")

            if (valid) {
                result.add(file.absolutePath)
            }
        }
    }
}

fun compareFileList(host: String, hostFiles: Array<String>, guest: String, guestFiles: Array<String>): Array<String> {
    val result = ArrayList<String>(128)

    val hostLength = host.length + (if (host.endsWith('/')) 1 else 0)
    val guestLength = guest.length + (if (host.endsWith('/')) 1 else 0)

    for (hostFile in hostFiles) {
        val hostFileRelative = hostFile.substring(hostLength)

        var found = false
        for (guestFile in guestFiles) {
            val guestFileRelative = guestFile.substring(guestLength)
            if (hostFileRelative == guestFileRelative) {
                found = true
                break
            }
        }

        if (!found)
            result.add(hostFile)
    }

    val count = result.size
    Log.i("MediaSync", "Different between Storage files and Network files: $count")

    return result.toTypedArray()
}

suspend fun compareFileListAsync(host: String, hostFiles: Array<String>, guest: String, guestFiles: Array<String>): Array<String> {
    return withContext(Dispatchers.IO) { compareFileList(host, hostFiles, guest, guestFiles) }
}

fun deleteFile(file: String): Boolean {
    val fileObj = File(file)
    if (fileObj.isFile) {
        try {
            fileObj.delete()
        } catch (ex: Exception) {
            ex.printStackTrace()
            Log.e("MediaSync", "File remove failed: $file")
            return false
        }
    }

    return true
}

suspend fun deleteFileAsync(file: String): Boolean {
    return withContext(Dispatchers.IO) { return@withContext deleteFile(file) }
}

fun connectToMusicListFile(host: String, username: String, password: String): HttpURLConnection? {
    val url = URL("$host/Jukebox.m3u8")
    val authHash = Base64.encodeToString(String.format("%s:%s", username, password).toByteArray(), Base64.NO_WRAP)

    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.setRequestProperty("Authorization", "Basic $authHash")

    if (connection.responseCode != 200)
        return null

    return connection
}

suspend fun connectToMusicListFileAsync(host: String, username: String, password: String): HttpURLConnection? {
    return withContext(Dispatchers.IO) { connectToMusicListFile(host, username, password) }
}

fun downloadMusicListFile(host: String, connection: HttpURLConnection): Array<String> {
    val contents = InputStreamReader(connection.inputStream).readLines()
    if (contents[0] != "#EXTM3U")
        return arrayOf()

    val files = ArrayList<String>(1024)
    for (i: Int in 2..contents.size step(2)) {
        val content = contents[i].replace('\\', '/')
        files.add("$host/$content")
    }

    val count = files.size
    Log.i("MediaSync", "Network Audio Library file count: $count")

    return files.toTypedArray()
}

suspend fun downloadMusicListFileAsync(host: String, connection: HttpURLConnection): Array<String> {
    return withContext(Dispatchers.IO) { downloadMusicListFile(host, connection) }
}

fun downloadMusicFile(host: String, username: String, password: String, file: String, targetDir: String) {
    val url = URL(file)
    val authHash = Base64.encodeToString(String.format("%s:%s", username, password).toByteArray(), Base64.NO_WRAP)

    val connection = url.openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.setRequestProperty("Authorization", "Basic $authHash")

    if (connection.responseCode != 200)
        return

    val hostLength = host.length + (if (host.endsWith('/')) 1 else 0)
    val fileWithoutHost = file.substring(hostLength)
    val localFile = File("$targetDir/$fileWithoutHost")
    localFile.parentFile?.mkdirs()
    if (!localFile.isFile && !localFile.createNewFile())
        return
    if (localFile.isFile) {
        localFile.delete()
        localFile.createNewFile()
    }

    val hostInputStream = connection.inputStream
    val fileOutputStream = localFile.outputStream()

    if (hostInputStream.copyTo(fileOutputStream) == 0L) {
        localFile.delete()
        return
    }
    hostInputStream.close()

    fileOutputStream.flush()
    fileOutputStream.close()
}

suspend fun downloadMusicFileAsync(host: String, username: String, password: String, file: String, targetDir: String) {
    return withContext(Dispatchers.IO) { downloadMusicFile(host, username, password, file, targetDir) }
}