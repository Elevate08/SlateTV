package dev.slatetv.launcher.wallpaper.plugins

import android.content.Context
import android.os.Environment
import dev.slatetv.plugin.api.BackgroundMedia
import dev.slatetv.plugin.api.BackgroundPlugin
import dev.slatetv.plugin.api.PluginMetadata
import dev.slatetv.plugin.api.PluginRequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Displays local image files placed in the app's external Pictures directory
 * (`Android/data/dev.slatetv.launcher/files/Pictures` — requires zero storage permissions).
 */
class LocalFolderPlugin(private val context: Context) : BackgroundPlugin {

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
            if (shuffledFiles.isEmpty() || cursor >= shuffledFiles.size) {
                shuffledFiles = scanImages().shuffled()
                cursor = 0
            }
            if (shuffledFiles.isEmpty()) return@withContext null
            val file = shuffledFiles[cursor++]
            BackgroundMedia(
                id = "local:${file.name}:${file.lastModified()}",
                imageUri = file.toURI().toString(),
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
