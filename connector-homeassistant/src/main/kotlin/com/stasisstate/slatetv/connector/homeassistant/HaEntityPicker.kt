package com.stasisstate.slatetv.connector.homeassistant

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A 10-foot entity picker: rows show friendly name, area/device and current state, grouped as
 * Selected → Suggested → areas → area-less domains. Multi-select keeps a visible "n of max" counter
 * and a Save button one press to the right of any row; single-select picks on press.
 * Grouping is computed once per filter change, so toggling never moves rows under the cursor.
 */
internal class HaEntityPicker private constructor(
    private val context: Context,
    private val title: String,
    private val items: List<HaPickerItem>,
    private val domains: Set<String>,
    initial: List<String>,
    /** Null for single choice. */
    private val max: Int?,
    private val noneLabel: String?,
    private val score: (HaPickerItem) -> Int,
    private val onDone: (List<String>) -> Unit,
) {
    private val selected = initial.distinct().toMutableList()
    private val pinned = selected.toList()
    private val single get() = max == null
    private val dialog = Dialog(context)
    private val list = LinearLayout(context)
    private val indicators = mutableMapOf<String?, TextView>()
    private val saveId = View.generateViewId()
    private var counter: TextView? = null
    private var showSecondary = false
    private var query = ""

    companion object {
        fun multi(
            context: Context,
            title: String,
            items: List<HaPickerItem>,
            domains: Set<String>,
            selected: List<String>,
            max: Int,
            onSave: (List<String>) -> Unit,
        ) = HaEntityPicker(context, title, items, domains, selected, max, null, { 0 }, onSave).show()

        fun single(
            context: Context,
            title: String,
            items: List<HaPickerItem>,
            domains: Set<String>,
            current: String?,
            noneLabel: String? = null,
            score: (HaPickerItem) -> Int = { 0 },
            onPick: (String?) -> Unit,
        ) = HaEntityPicker(context, title, items, domains, listOfNotNull(current), null, noneLabel, score) {
            onPick(it.firstOrNull())
        }.show()

        private val BG = Color.parseColor("#0A0D12")
        private val CARD = Color.parseColor("#141923")
        private val ROW = Color.parseColor("#192030")
        private val FOCUSED_FILL = Color.parseColor("#243047")
        private val FOCUS_RING = Color.parseColor("#F8FAFC")
        private val TEXT_PRIMARY = Color.parseColor("#F8FAFC")
        private val TEXT_MUTED = Color.parseColor("#94A3B8")
        private val TEXT_FAINT = Color.parseColor("#64748B")
        private val ACCENT = Color.parseColor("#7DD3FC")
        private val CHIP = Color.parseColor("#1E293B") to Color.parseColor("#CBD5E1")
        private val CHIP_ON = Color.parseColor("#0F3D2E") to Color.parseColor("#86EFAC")
        private val CHIP_BAD = Color.parseColor("#3A1D24") to Color.parseColor("#FCA5A5")
        private val COUNTER_FULL = Color.parseColor("#4A3510") to Color.parseColor("#FCD34D")
    }

    private fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(20), dp(30), dp(14))
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), FOCUSED_FILL)
            }
        }
        root.addView(header())
        root.addView(toolbar(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(2) })
        list.apply {
            orientation = LinearLayout.VERTICAL
            // Room for the focused row's zoom and ring; clipping must be off for both.
            setPadding(dp(10), dp(4), dp(10), dp(12))
            clipToPadding = false
            clipChildren = false
        }
        root.addView(ScrollView(context).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!single) root.addView(footer(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val metrics = context.resources.displayMetrics
            setLayout((metrics.widthPixels * 0.72f).toInt(), (metrics.heightPixels * 0.92f).toInt())
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        val first = render()
        dialog.show()
        first?.post { first.requestFocus() }
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT_PRIMARY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!single) {
            counter = TextView(context).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(16), dp(6), dp(16), dp(6))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(counter, LinearLayout.LayoutParams(-2, -2))
            updateCounter()
        }
    }

    private fun toolbar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(10), 0)
        val filter = EditText(context).apply {
            hint = "Filter by name, room or device"
            setHintTextColor(TEXT_FAINT)
            setTextColor(TEXT_PRIMARY)
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(18), dp(7), dp(18), dp(7))
            background = focusable(Color.parseColor("#0F141D"), radius = 40)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    render()
                }
            })
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
                firstRow()?.requestFocus()
                true
            }
        }
        addView(filter, LinearLayout.LayoutParams(0, -2, 1f))
        val secondaryCount = items.count { it.secondary && it.entityId !in pinned }
        if (secondaryCount > 0) {
            val toggle = TextView(context)
            fun label() {
                toggle.text = if (showSecondary) "Hide extras" else "Show hidden & config ($secondaryCount)"
            }
            toggle.apply {
                label()
                textSize = 15f
                setTextColor(TEXT_MUTED)
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = focusable(ROW, radius = 40)
                setOnClickListener {
                    showSecondary = !showSecondary
                    label()
                    render()
                }
            }
            addView(toggle, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
    }

    private fun footer(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        setPadding(dp(10), 0, dp(10), 0)
        addView(TextView(context).apply {
            text = "Press → from any row to jump to Save"
            textSize = 14f
            setTextColor(TEXT_FAINT)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(button("Cancel", View.NO_ID) { dialog.dismiss() })
        addView(button("Save", saveId) {
            dialog.dismiss()
            onDone(selected.toList())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
    }

    private fun button(label: String, viewId: Int, action: () -> Unit) = TextView(context).apply {
        id = viewId
        text = label
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(TEXT_PRIMARY)
        gravity = Gravity.CENTER
        minWidth = dp(120)
        isFocusable = true
        isClickable = true
        setPadding(dp(24), dp(8), dp(24), dp(8))
        background = focusable(ROW, radius = 40)
        setOnClickListener { action() }
    }

    /** Rebuilds the rows for the current filter and returns the row that should take focus. */
    private fun render(): View? {
        list.removeAllViews()
        indicators.clear()
        var firstRow: View? = null
        var currentRow: View? = null
        var suggestedRow: View? = null
        if (noneLabel != null && query.isBlank()) firstRow = row(null, showsArea = true)
        val sections = HaPickerModel.sections(
            items = items,
            domains = domains,
            pinned = pinned,
            pinnedTitle = if (single) HaPickerModel.SECTION_CURRENT else HaPickerModel.SECTION_SELECTED,
            score = score,
            showSecondary = showSecondary,
            query = query,
        )
        for (section in sections) {
            sectionHeader(section)
            for (item in section.items) {
                val view = row(item, section.showsArea)
                if (firstRow == null) firstRow = view
                if (single && currentRow == null && item.entityId in selected) currentRow = view
                if (suggestedRow == null && section.title == HaPickerModel.SECTION_SUGGESTED) suggestedRow = view
            }
        }
        if (sections.isEmpty()) {
            list.addView(TextView(context).apply {
                text = if (query.isBlank()) "Nothing to choose from yet" else "No matches for “${query.trim()}”"
                textSize = 18f
                setTextColor(TEXT_MUTED)
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        }
        // Single choice lands on the current pick, else the best suggestion; multi starts at the top.
        return if (single) currentRow ?: suggestedRow ?: firstRow else firstRow
    }

    private fun firstRow(): View? = (0 until list.childCount).map(list::getChildAt).firstOrNull { it.isFocusable }

    private fun sectionHeader(section: HaPickerSection) {
        val count = if (section.title == HaPickerModel.SECTION_SELECTED) "" else "  ·  ${section.items.size}"
        list.addView(TextView(context).apply {
            text = section.title.uppercase() + count
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(if (section.title == HaPickerModel.SECTION_SUGGESTED) ACCENT else TEXT_MUTED)
            setPadding(dp(6), dp(12), 0, dp(6))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** One entity row, or the "none" row when [item] is null. */
    private fun row(item: HaPickerItem?, showsArea: Boolean): View {
        val id = item?.entityId
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = focusable(ROW, radius = 14)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            minimumHeight = dp(54)
            if (!single) nextFocusRightId = saveId
            setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.012f else 1f).scaleY(if (focused) 1.012f else 1f).setDuration(120).start()
            }
        }
        val indicator = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }
        indicators[id] = indicator
        paintIndicator(indicator, isChosen(id))
        view.addView(indicator, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(16) })

        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(context).apply {
            this.text = item?.title ?: noneLabel
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (item?.missing == true) TEXT_MUTED else TEXT_PRIMARY)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        })
        val subtitle = item?.context(showsArea).orEmpty()
            .let { if (item?.secondary == true) "$it  ·  hidden by default" else it }
        if (subtitle.isNotEmpty()) {
            text.addView(TextView(context).apply {
                this.text = subtitle
                textSize = 14f
                setTextColor(TEXT_MUTED)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(1), 0, 0)
            })
        }
        view.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        // A chip that would just repeat the subtitle ("Scene" / "Scene") is left out.
        if (item != null && item.state.isNotEmpty() && item.state != subtitle) {
            val (fill, ink) = when {
                item.missing || item.state == "Unavailable" -> CHIP_BAD
                item.stateActive -> CHIP_ON
                else -> CHIP
            }
            view.addView(TextView(context).apply {
                this.text = item.state
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
                setSingleLine(true)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(fill)
                    cornerRadius = dp(40).toFloat()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        }
        view.contentDescription = listOfNotNull(item?.title ?: noneLabel, subtitle.ifEmpty { null }, item?.state?.ifEmpty { null })
            .joinToString(", ")
        view.setOnClickListener { onRowClicked(id, indicator) }
        list.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        return view
    }

    private fun isChosen(id: String?) = if (id == null) selected.isEmpty() else id in selected

    private fun onRowClicked(id: String?, indicator: TextView) {
        if (single) {
            dialog.dismiss()
            onDone(listOfNotNull(id))
            return
        }
        id ?: return
        val limit = max ?: return
        if (id in selected) {
            selected -= id
        } else if (selected.size >= limit) {
            flashLimit()
            return
        } else {
            selected += id
        }
        paintIndicator(indicator, id in selected)
        updateCounter()
    }

    private fun paintIndicator(view: TextView, on: Boolean) {
        view.text = when {
            !on -> ""
            single -> "●"
            else -> "✓"
        }
        view.setTextColor(if (single) ACCENT else BG)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (on && !single) setColor(ACCENT) else setColor(Color.TRANSPARENT)
            setStroke(dp(2), if (on) ACCENT else TEXT_FAINT)
        }
    }

    private fun updateCounter(message: String? = null) {
        val view = counter ?: return
        val limit = max ?: return
        val full = selected.size >= limit
        view.text = message ?: "${selected.size} of $limit selected"
        val (fill, ink) = if (message != null || full) COUNTER_FULL else CHIP
        view.setTextColor(ink)
        view.background = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(40).toFloat()
        }
    }

    private fun flashLimit() {
        val view = counter ?: return
        updateCounter("Limit reached — unselect one first")
        view.removeCallbacks(restoreCounter)
        view.postDelayed(restoreCounter, 1800)
    }

    private val restoreCounter = Runnable { updateCounter() }

    private fun focusable(fill: Int, radius: Int) = StateListDrawable().apply {
        fun shape(color: Int, stroke: Int?) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(3), it) }
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(FOCUSED_FILL, FOCUS_RING))
        addState(intArrayOf(android.R.attr.state_pressed), shape(FOCUSED_FILL, FOCUS_RING))
        addState(intArrayOf(), shape(fill, null))
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
