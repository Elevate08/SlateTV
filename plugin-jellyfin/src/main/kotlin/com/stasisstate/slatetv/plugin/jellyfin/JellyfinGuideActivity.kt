package com.stasisstate.slatetv.plugin.jellyfin

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class JellyfinGuideActivity : JellyfinTvActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var loadMoreButton: Button
    private lateinit var status: TextView
    private lateinit var synopsis: TextView
    private lateinit var adapter: GuideAdapter
    private var load: Job? = null
    private var selectedChannel: String? = null
    private var activeApi: JellyfinApiClient? = null
    private var activeUserId: String? = null
    private var totalChannels: Int = 0
    private var nextStartIndex: Int = 0
    private val loadedChannels = mutableListOf<Channel>()
    private val upcomingByChannel = mutableMapOf<String, Program>()
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedChannel = intent.getStringExtra(CHANNEL_ID)
        val root = rootColumn()
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(label("Live TV guide", 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Refresh") { refresh() })
        header.addView(button("Settings") { settings() })
        root.addView(header)
        status = label("")
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        root.addView(status)

        adapter = GuideAdapter()
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@JellyfinGuideActivity)
            this.adapter = this@JellyfinGuideActivity.adapter
            itemAnimator = null
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

        loadMoreButton = button("Load more channels") { loadNextPage() }.apply {
            visibility = View.GONE
        }
        root.addView(loadMoreButton, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(6)
        })

        synopsis = label("", 18f).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }
        root.addView(synopsis)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        load?.cancel()
        loadedChannels.clear()
        upcomingByChannel.clear()
        totalChannels = 0
        nextStartIndex = 0
        loadMoreButton.visibility = View.GONE
        load = scope.launch {
            status.visibility = View.VISIBLE
            status.text = "Loading Jellyfin Live TV…"
            try {
                val config = JellyfinConfigStore(applicationContext).load()
                if (config.apiKey.isBlank() || config.serverUrl.isBlank()) {
                    empty("Open Settings to connect your Jellyfin server.")
                    return@launch
                }
                val api = JellyfinApiClient(config)
                if (!api.liveTvEnabled()) {
                    empty("Live TV is unavailable on this Jellyfin server.")
                    return@launch
                }
                val userId = config.userId.ifBlank { api.detectUserId() }
                activeApi = api
                activeUserId = userId

                val firstPage = api.channelPage(userId, PAGE_SIZE, false, 0)
                if (firstPage.items.isEmpty()) {
                    empty("No Live TV channels found.")
                    return@launch
                }
                loadedChannels.addAll(firstPage.items)
                totalChannels = maxOf(firstPage.total, firstPage.items.size)
                nextStartIndex = firstPage.received
                status.visibility = View.GONE
                updateLoadMoreButton()
                render(focusSelected = true)
                fetchUpNextFor(api, userId, firstPage.items)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                empty("Could not load Live TV. Check Settings or select Refresh.")
            }
        }
    }

    private fun loadNextPage() {
        val api = activeApi ?: return
        val userId = activeUserId ?: return
        if (load?.isActive == true || nextStartIndex >= totalChannels) return
        load = scope.launch {
            loadMoreButton.isEnabled = false
            loadMoreButton.text = "Loading more channels…"
            try {
                val page = api.channelPage(userId, PAGE_SIZE, false, nextStartIndex)
                if (page.items.isNotEmpty()) {
                    val existingIds = loadedChannels.mapTo(HashSet()) { it.id }
                    val added = page.items.filter { existingIds.add(it.id) }
                    loadedChannels.addAll(added)
                    totalChannels = maxOf(page.total, loadedChannels.size)
                    nextStartIndex += page.received
                    render(focusSelected = false)
                    fetchUpNextFor(api, userId, added)
                } else {
                    nextStartIndex = totalChannels
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status.visibility = View.VISIBLE
                status.text = "Could not load additional channels."
            } finally {
                loadMoreButton.isEnabled = true
                updateLoadMoreButton()
            }
        }
    }

    private suspend fun fetchUpNextFor(api: JellyfinApiClient, userId: String, channels: List<Channel>) {
        if (channels.isEmpty()) return
        val now = Instant.now()
        try {
            for (group in channels.chunked(20)) {
                val next = api.upNext(userId, group.map(Channel::id), now)
                upcomingByChannel.putAll(next)
            }
            render(focusSelected = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status.visibility = View.VISIBLE
            status.text = "Up Next is temporarily unavailable."
        }
    }

    private fun updateLoadMoreButton() {
        val remaining = totalChannels - loadedChannels.size
        if (remaining > 0) {
            loadMoreButton.visibility = View.VISIBLE
            loadMoreButton.text = "Load more channels (${loadedChannels.size} of $totalChannels)"
        } else {
            loadMoreButton.visibility = View.GONE
        }
    }

    private fun empty(message: String) {
        loadedChannels.clear()
        upcomingByChannel.clear()
        adapter.submitList(emptyList())
        loadMoreButton.visibility = View.GONE
        synopsis.text = ""
        status.visibility = View.VISIBLE
        status.text = message
    }

    private fun render(focusSelected: Boolean) {
        val now = Instant.now()
        val rows = loadedChannels.map { channel ->
            GuideRow(channel, upcomingByChannel[channel.id], now)
        }
        adapter.submitList(rows) {
            if (focusSelected && loadedChannels.isNotEmpty()) {
                val targetIdx = loadedChannels.indexOfFirst { it.id == selectedChannel }.coerceAtLeast(0)
                recycler.scrollToPosition(targetIdx)
                recycler.post {
                    recycler.findViewHolderForAdapterPosition(targetIdx)?.itemView?.requestFocus()
                        ?: recycler.getChildAt(0)?.requestFocus()
                }
            }
        }
    }

    private data class GuideRow(
        val channel: Channel,
        val upcoming: Program?,
        val now: Instant,
    )

    private inner class GuideAdapter : ListAdapter<GuideRow, GuideAdapter.Holder>(
        object : DiffUtil.ItemCallback<GuideRow>() {
            override fun areItemsTheSame(oldItem: GuideRow, newItem: GuideRow) =
                oldItem.channel.id == newItem.channel.id

            override fun areContentsTheSame(oldItem: GuideRow, newItem: GuideRow) =
                oldItem.channel == newItem.channel && oldItem.upcoming == newItem.upcoming
        },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@JellyfinGuideActivity).apply {
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = focusBackground()
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            val stationView = label("", 19f, true)
            row.addView(stationView, LinearLayout.LayoutParams(0, -2, 1f))

            val onNowCol = column()
            val onNowTitle = label("")
            val remainingView = label("", 15f)
            val progressBar = ProgressBar(
                this@JellyfinGuideActivity,
                null,
                android.R.attr.progressBarStyleHorizontal,
            )
            onNowCol.addView(onNowTitle)
            onNowCol.addView(remainingView)
            onNowCol.addView(progressBar)
            row.addView(onNowCol, LinearLayout.LayoutParams(0, -2, 2f).apply { marginEnd = dp(16) })

            val upNextView = label("")
            row.addView(upNextView, LinearLayout.LayoutParams(0, -2, 2f))

            return Holder(row, stationView, onNowTitle, remainingView, progressBar, upNextView)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val channel = item.channel
            val current = channel.current
            val upcoming = item.upcoming
            val now = item.now

            holder.stationView.text = listOfNotNull(channel.number, channel.name).joinToString("  ")
            holder.onNowTitle.text = "On now · ${current?.title ?: "No program data"}"

            val remaining = current?.remainingTime(now)
            holder.remainingView.text = remaining.orEmpty()
            holder.remainingView.visibility = if (remaining != null) View.VISIBLE else View.GONE

            val progress = current?.progressPercent(now) ?: -1
            holder.progressBar.visibility = if (progress >= 0) View.VISIBLE else View.GONE
            if (progress >= 0) holder.progressBar.progress = progress

            holder.upNextView.text = upcoming?.let { "Up next · ${timeFormat.format(it.start)}\n${it.title}" }
                ?: "Up next · —"

            holder.itemView.setOnClickListener { launchJellyfin() }
            holder.itemView.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    selectedChannel = channel.id
                    synopsis.text = current?.overview ?: upcoming?.overview ?: "No program synopsis available."
                }
            }
        }

        inner class Holder(
            view: View,
            val stationView: TextView,
            val onNowTitle: TextView,
            val remainingView: TextView,
            val progressBar: ProgressBar,
            val upNextView: TextView,
        ) : RecyclerView.ViewHolder(view)
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}
