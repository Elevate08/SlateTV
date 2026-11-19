package com.stasisstate.slatetv.wallpaper.plugins

import android.content.Context
import android.os.Environment
import com.stasisstate.slatetv.plugin.api.BackgroundMedia
import com.stasisstate.slatetv.plugin.api.BackgroundPlugin
import com.stasisstate.slatetv.plugin.api.PluginMetadata
import com.stasisstate.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Displays local image files placed in the app's external Pictures directory
 * (`Android/data/com.stasisstate.slatetv/files/Pictures` — requires zero storage permissions).
 */
class LocalFolderPlugin(private val context: Context) : BackgroundPlugin {

    private val mutex = Mutex()
    private var shuffledFiles: List<File> = emptyList()
    private var cursor = 0

    override val metadata: PluginMetadata = PluginMetadata(
        id = PLUGIN_ID,
        name = "Local Photos",
        description = "Photos from app Pictures folder (no network required)",
        version = "1.0.0",
        isExternal = false,
        supportsNext = true,
    )

    fun picturesDirectory(): File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        scanImages().isNotEmpty()
    }

    override suspend fun fetchNext(request: PluginRequestContext): BackgroundMedia? =
        withContext(Dispatchers.IO) {
            val file = mutex.withLock {
                if (shuffledFiles.isEmpty() || cursor >= shuffledFiles.size) {
                    shuffledFiles = scanImages().shuffled()
                    cursor = 0
                }
                if (shuffledFiles.isEmpty()) null else shuffledFiles[cursor++]
            } ?: return@withContext null

            BackgroundMedia(
                id = "local:${file.name}:${file.lastModified()}",
                imageUri = "file://${file.toURI().rawPath}",
                subtitle = file.nameWithoutExtension,
            )
        }

    private fun scanImages(): List<File> {
        val dir = picturesDirectory() ?: return emptyList()
        if (!dir.exists()) {
            dir.mkdirs()
            return emptyList()
        }
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTS }
            .orEmpty()
    }

    companion object {
        const val PLUGIN_ID = "builtin.local_folder"
        private val SUPPORTED_EXTS = setOf("jpg", "jpeg", "png", "webp")
    }
}
