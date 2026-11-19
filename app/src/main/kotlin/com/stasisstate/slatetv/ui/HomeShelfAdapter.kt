package com.stasisstate.slatetv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.stasisstate.slatetv.NetworkClientFactory.executeCancellable
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.plugin.api.ShelfCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal class HomeShelfAdapter(
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val onClick: (ShelfCard) -> Unit,
) : ListAdapter<ShelfCard, HomeShelfAdapter.Holder>(object : DiffUtil.ItemCallback<ShelfCard>() {
    override fun areItemsTheSame(oldItem: ShelfCard, newItem: ShelfCard) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: ShelfCard, newItem: ShelfCard) = oldItem == newItem
}) {
    private data class ImageKey(val uri: String, val headers: Map<String, String>)

    private val cache = object : LruCache<ImageKey, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: ImageKey, value: Bitmap) = value.allocationByteCount
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_card, parent, false),
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = getItem(position)
        holder.imageJob?.cancel()
        holder.logo.setImageDrawable(null)
        holder.title.text = card.title
        holder.subtitle.text = card.subtitle
        holder.label.text = listOfNotNull(card.channelNumber, card.badge).joinToString(" · ")
        holder.progress.visibility = if (card.progressPercent in 0..100) View.VISIBLE else View.INVISIBLE
        holder.progress.progress = card.progressPercent.coerceIn(0, 100)
        holder.itemView.setOnClickListener { onClick(card) }

        val key = ImageKey(card.imageUri ?: return, card.imageHeaders)
        val cached = cache.get(key)
        if (cached != null) {
            holder.logo.setImageBitmap(cached)
            return
        }
        holder.imageJob = scope.launch {
            loadImage(key)?.let {
                cache.put(key, it)
                holder.logo.setImageBitmap(it)
            }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.imageJob?.cancel()
        holder.logo.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    private suspend fun loadImage(key: ImageKey): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(key.uri).apply {
                key.headers.forEach { (name, value) -> header(name, value) }
            }.build()
            client.executeCancellable(request).use { response ->
                val body = response.body ?: return@use null
                if (!response.isSuccessful || body.contentLength() > MAX_IMAGE_BYTES) return@use null
                val source = body.source()
                if (source.request(MAX_IMAGE_BYTES + 1)) return@use null
                val bytes = source.readByteArray()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@use null
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 256) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.shelfLogo)
        val title: TextView = view.findViewById(R.id.shelfCardTitle)
        val subtitle: TextView = view.findViewById(R.id.shelfCardSubtitle)
        val label: TextView = view.findViewById(R.id.shelfCardLabel)
        val progress: ProgressBar = view.findViewById(R.id.shelfProgress)
        var imageJob: Job? = null
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2L * 1024 * 1024
    }
}
