package id.catatkeuangan

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

object Ui {
    const val PRIMARY = 0xFF00695C.toInt()
    const val TEXT = 0xFF17201E.toInt()
    const val SUB = 0xFF5B6764.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val RED = 0xFFC62828.toInt()
    const val GRAY = 0xFF7A8582.toInt()
    const val ORANGE = 0xFFE65100.toInt()
    const val BG = 0xFFF3F6F5.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val LINE = 0xFFE3E9E7.toInt()
    const val WARN_BG = 0xFFFFF3E0.toInt()
    const val OK_BG = 0xFFE0F2F1.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    fun text(ctx: Context, s: CharSequence, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun title(ctx: Context, s: String): TextView = text(ctx, s, 22f, TEXT, true).apply {
        setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 4))
    }

    fun section(ctx: Context, s: String): TextView = text(ctx, s, 13f, SUB, true).apply {
        setPadding(dp(ctx, 4), dp(ctx, 16), dp(ctx, 4), dp(ctx, 6))
        isAllCaps = true
        letterSpacing = 0.04f
    }

    fun vbox(ctx: Context, padDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        setPadding(p, p, p, p)
    }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    fun card(ctx: Context, content: View, padDp: Int = 14, bg: Int = CARD): MaterialCardView = MaterialCardView(ctx).apply {
        radius = dp(ctx, 14).toFloat()
        cardElevation = 0f
        strokeWidth = if (bg == CARD) dp(ctx, 1) else 0
        strokeColor = LINE
        setCardBackgroundColor(bg)
        val p = dp(ctx, padDp)
        content.setPadding(p, p, p, p)
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layoutParams = matchWrap().apply { bottomMargin = dp(ctx, 10) }
    }

    fun filled(ctx: Context, label: String, onClick: () -> Unit): MaterialButton = MaterialButton(ctx).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    fun outlined(ctx: Context, label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    fun flat(ctx: Context, label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    fun iconButton(ctx: Context, icon: Int, desc: String, onClick: () -> Unit): ImageButton = ImageButton(ctx).apply {
        setImageResource(icon)
        contentDescription = desc
        imageTintList = ColorStateList.valueOf(TEXT)
        val out = android.util.TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        setBackgroundResource(out.resourceId)
        val p = dp(ctx, 10)
        setPadding(p, p, p, p)
        setOnClickListener { onClick() }
    }

    /** Pilihan tombol bersambung (mis. Pribadi | Usaha | Semua). */
    fun toggle(
        ctx: Context,
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): MaterialButtonToggleGroup {
        val group = MaterialButtonToggleGroup(ctx)
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val ids = labels.map { View.generateViewId() }
        labels.forEachIndexed { i, l ->
            val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.id = ids[i]
            b.text = l
            b.isAllCaps = false
            b.minWidth = 0
            b.insetTop = 0
            b.insetBottom = 0
            group.addView(b, LinearLayout.LayoutParams(0, dp(ctx, 40), 1f))
        }
        if (selected in ids.indices) group.check(ids[selected])
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) onSelect(ids.indexOf(checkedId))
        }
        return group
    }

    fun pill(ctx: Context, s: String, fg: Int, bg: Int): TextView = text(ctx, s, 12f, fg, true).apply {
        val p = dp(ctx, 8)
        setPadding(p, dp(ctx, 3), p, dp(ctx, 3))
        background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12).toFloat()
            setColor(bg)
        }
    }

    /** Batang persentase sederhana. */
    fun bar(ctx: Context, fraction: Float, color: Int): View {
        val f = fraction.coerceIn(0f, 1f)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 3).toFloat()
                setColor(LINE)
            }
        }
        val fill = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 3).toFloat()
                setColor(color)
            }
        }
        row.addView(fill, LinearLayout.LayoutParams(0, dp(ctx, 6), f))
        row.addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, 6), 1f - f))
        row.layoutParams = matchWrap().apply { topMargin = dp(ctx, 6) }
        return row
    }

    fun input(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): TextInputLayout {
        val til = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle)
        til.hint = hint
        val et = TextInputEditText(til.context)
        et.setText(value)
        et.inputType = type
        til.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        til.layoutParams = matchWrap().apply { topMargin = dp(ctx, 8) }
        return til
    }

    fun dialog(ctx: Context): MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(ctx)

    fun info(ctx: Context, title: String, msg: CharSequence) {
        dialog(ctx).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    fun confirm(ctx: Context, title: String, msg: String, yes: String, onYes: () -> Unit) {
        dialog(ctx).setTitle(title).setMessage(msg).setNegativeButton("Batal", null)
            .setPositiveButton(yes) { _, _ -> onYes() }.show()
    }

    /** Dialog isian satu baris. */
    fun prompt(ctx: Context, title: String, hint: String, value: String, onOk: (String) -> Unit) {
        val box = vbox(ctx, 20)
        val til = input(ctx, hint, value)
        box.addView(til)
        val d: AlertDialog = dialog(ctx).setTitle(title).setView(box).setNegativeButton("Batal", null)
            .setPositiveButton("Simpan", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = til.value()
                if (v.isEmpty()) til.error = "Wajib diisi" else {
                    d.dismiss()
                    onOk(v)
                }
            }
        }
        d.show()
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    @Suppress("unused")
    fun spacer(ctx: Context, hDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }
}

fun TextInputLayout.value(): String = editText?.text?.toString()?.trim() ?: ""
