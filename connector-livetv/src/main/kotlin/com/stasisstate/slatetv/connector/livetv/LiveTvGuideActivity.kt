package com.stasisstate.slatetv.connector.livetv

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class LiveTvGuideActivity : LiveTvBaseActivity() {
    private lateinit var recycler: RecyclerView
    private lateinit var status: TextView
    private lateinit var synopsis: TextView
    private lateinit var adapter: GuideAdapter
    private var load: Job? = null
    private var selectedChannel: String? = null
    private val loadedChannels = mutableListOf<Channel>()
    private val upcomingByChannel = mutableMapOf<String, Program>()
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedChannel = intent.getStringExtra(BuiltInLiveTvShelfPlugin.EXTRA_CHANNEL_ID)
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
            layoutManager = LinearLayoutManager(this@LiveTvGuideActivity)
            this.adapter = this@LiveTvGuideActivity.adapter
            itemAnimator = null
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))

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
        load = scope.launch {
            status.visibility = View.VISIBLE
            status.text = "Loading Live TV…"
            try {
                loadSystemChannels()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                empty("Could not load Live TV. Check Settings or select Refresh.")
            }
        }
    }

    private suspend fun loadSystemChannels() {
        if (!hasTvListingsPermission(applicationContext)) {
            empty("Open Settings and select Grant Live TV Listings Access.")
            return
        }
        val result = withContext(Dispatchers.IO) { systemTvSource(applicationContext).load() }
        when (result) {
            SystemTvResult.NeedsPermission -> empty("Open Settings and select Grant Live TV Listings Access.")
            is SystemTvResult.Ready -> {
                if (result.channels.isEmpty()) {
                    empty("No Live Channels found. Set up channels in Android TV Live Channels.")
                    return
                }
                loadedChannels.addAll(result.channels)
                upcomingByChannel.putAll(result.upcoming)
                status.visibility = View.GONE
                render(focusSelected = true)
            }
        }
    }

    private fun empty(message: String) {
        loadedChannels.clear()
        upcomingByChannel.clear()
        adapter.submitList(emptyList())
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
                oldItem.channel == newItem.channel && oldItem.upcoming == newItem.upcoming &&
                    oldItem.channel.current?.remainingTime(oldItem.now) == newItem.channel.current?.remainingTime(newItem.now) &&
                    oldItem.channel.current?.progressPercent(oldItem.now) == newItem.channel.current?.progressPercent(newItem.now)
        },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(this@LiveTvGuideActivity).apply {
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
                this@LiveTvGuideActivity,
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

            holder.itemView.setOnClickListener {
                tuneChannel(channel.id.toLongOrNull() ?: -1L)
            }
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
}
