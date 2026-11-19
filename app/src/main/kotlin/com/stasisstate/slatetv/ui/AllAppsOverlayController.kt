package com.stasisstate.slatetv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.apps.AppInfo

class AllAppsOverlayController(
    private val overlayView: View,
    private val recyclerView: RecyclerView,
    private val onLaunchApp: (AppInfo) -> Unit,
    private val onAppContextAction: (AppInfo) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val adapter = GridAdapter()
    private var pinnedPackages: Set<String> = emptySet()

    init {
        recyclerView.layoutManager = GridLayoutManager(overlayView.context, 5)
        recyclerView.adapter = adapter
    }

    val isVisible: Boolean
        get() = overlayView.visibility == View.VISIBLE

    fun updateApps(allApps: List<AppInfo>, pinned: Set<String>) {
        pinnedPackages = pinned
        adapter.submit(allApps)
    }

    fun open() {
        overlayView.visibility = View.VISIBLE
        overlayView.alpha = 0f
        overlayView.animate().alpha(1f).setDuration(150L).start()
        recyclerView.post {
            recyclerView.layoutManager?.findViewByPosition(0)?.requestFocus()
                ?: recyclerView.requestFocus()
        }
    }

    fun close() {
        if (!isVisible) return
        overlayView.visibility = View.GONE
        onClosed()
    }

    private inner class GridAdapter : RecyclerView.Adapter<GridViewHolder>() {
        private val apps = mutableListOf<AppInfo>()

        fun submit(list: List<AppInfo>) {
            apps.clear()
            apps.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_grid_app, parent, false)
            return GridViewHolder(view)
        }

        override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
            holder.bind(apps[position])
        }

        override fun getItemCount(): Int = apps.size
    }

    private inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val banner: ImageView = itemView.findViewById(R.id.gridAppBanner)
        private val icon: ImageView = itemView.findViewById(R.id.gridAppIcon)
        private val label: TextView = itemView.findViewById(R.id.gridAppLabel)
        private val pinnedBadge: View = itemView.findViewById(R.id.gridPinnedBadge)

        fun bind(app: AppInfo) {
            label.text = app.label
            pinnedBadge.visibility = if (app.packageName in pinnedPackages) View.VISIBLE else View.GONE

            if (app.banner != null) {
                banner.setImageDrawable(app.banner)
                banner.visibility = View.VISIBLE
                icon.visibility = View.GONE
            } else {
                banner.setImageDrawable(null)
                banner.visibility = View.GONE
                icon.setImageDrawable(app.icon)
                icon.visibility = View.VISIBLE
            }

            itemView.setOnClickListener {
                close()
                onLaunchApp(app)
            }
            itemView.setOnLongClickListener {
                onAppContextAction(app)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    onAppContextAction(app)
                    true
                } else {
                    false
                }
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) 1.06f else 1.0f
                v.animate().scaleX(s).scaleY(s).setDuration(110L).start()
            }
        }
    }
}
