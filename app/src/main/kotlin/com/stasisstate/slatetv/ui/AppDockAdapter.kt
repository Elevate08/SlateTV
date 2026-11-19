package com.stasisstate.slatetv.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.stasisstate.slatetv.R
import com.stasisstate.slatetv.apps.AppInfo

class AppDockAdapter(
    private val onAppClick: (AppInfo) -> Unit,
    private val onAppLongClick: (AppInfo) -> Unit,
    private val onAppFocused: (AppInfo) -> Unit,
) : RecyclerView.Adapter<AppDockAdapter.DockViewHolder>() {

    private val items = mutableListOf<AppInfo>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

    fun submitList(apps: List<AppInfo>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = apps.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                items[oldItemPosition].packageName == apps[newItemPosition].packageName

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = apps[newItemPosition]
                return old.packageName == new.packageName && old.label == new.label
            }
        })
        items.clear()
        items.addAll(apps)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DockViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_dock_app, parent, false)
        return DockViewHolder(view)
    }

    override fun onBindViewHolder(holder: DockViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class DockViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val bannerImage: ImageView = itemView.findViewById(R.id.appBannerImage)
        private val fallbackContainer: View = itemView.findViewById(R.id.appIconFallbackContainer)
        private val iconImage: ImageView = itemView.findViewById(R.id.appIconImage)
        private val shortTitle: TextView = itemView.findViewById(R.id.appShortTitle)

        fun bind(app: AppInfo) {
            if (app.banner != null) {
                bannerImage.setImageDrawable(app.banner)
                bannerImage.visibility = View.VISIBLE
                fallbackContainer.visibility = View.GONE
            } else {
                bannerImage.setImageDrawable(null)
                bannerImage.visibility = View.GONE
                fallbackContainer.visibility = View.VISIBLE
                iconImage.setImageDrawable(app.icon)
                shortTitle.text = app.label
            }

            itemView.setOnClickListener { onAppClick(app) }
            itemView.setOnLongClickListener {
                onAppLongClick(app)
                true
            }
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
                    onAppLongClick(app)
                    true
                } else {
                    false
                }
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.08f else 1.0f
                val elevation = if (hasFocus) 12f else 0f
                v.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevation)
                    .setDuration(130L)
                    .start()
                if (hasFocus) {
                    onAppFocused(app)
                }
            }
        }
    }
}
