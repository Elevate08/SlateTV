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
    private var reorderingPackage: String? = null

    val currentList: List<AppInfo>
        get() = items.toList()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].packageName.hashCode().toLong()

    fun setReorderingPackage(packageName: String?) {
        if (reorderingPackage == packageName) return
        val previous = reorderingPackage
        reorderingPackage = packageName
        val positions = listOfNotNull(indexOfPackage(previous), indexOfPackage(packageName)).distinct()
        if (positions.isEmpty()) {
            if (itemCount > 0) notifyDataSetChanged()
        } else {
            positions.forEach { notifyItemChanged(it) }
        }
    }

    fun submitList(apps: List<AppInfo>, commitCallback: (() -> Unit)? = null) {
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
        commitCallback?.invoke()
    }

    private fun indexOfPackage(packageName: String?): Int? {
        if (packageName == null) return null
        val index = items.indexOfFirst { it.packageName == packageName }
        return index.takeIf { it >= 0 }
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
        private val reorderHint: TextView = itemView.findViewById(R.id.reorderHint)

        fun bind(app: AppInfo) {
            val reordering = reorderingPackage != null && app.packageName == reorderingPackage
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
            itemView.isActivated = reordering
            reorderHint.visibility = if (reordering) View.VISIBLE else View.GONE

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
            fun applyChrome(target: View, hasFocus: Boolean) {
                val scale = when {
                    hasFocus && reordering -> 1.16f
                    hasFocus || reordering -> 1.08f
                    else -> 1.0f
                }
                val elevation = when {
                    reordering -> 22f
                    hasFocus -> 12f
                    else -> 0f
                }
                target.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .translationZ(elevation)
                    .setDuration(130L)
                    .start()
            }
            itemView.setOnFocusChangeListener { v, hasFocus ->
                applyChrome(v, hasFocus)
                if (hasFocus) onAppFocused(app)
            }
            applyChrome(itemView, itemView.hasFocus())
        }
    }
}
