package com.example.util

import android.app.Activity
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.example.R

/** Ebeveyn kilidi için 4 haneli PIN giriş penceresi. Kumandanın rakam tuşlarıyla doldurulabilir. */
object PinDialog {

    fun show(activity: Activity, title: String, message: String? = null, onPin: (String) -> Unit) {
        val density = activity.resources.displayMetrics.density
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.CENTER
            textSize = 26f
            letterSpacing = 0.5f
            hint = "••••"
            setTextColor(ContextCompat.getColor(activity, R.color.white))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_category_tab)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val container = FrameLayout(activity).apply {
            val h = (48 * density).toInt()
            val v = (12 * density).toInt()
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(h, v, h, v)
            })
        }

        val dialog = AlertDialog.Builder(activity, R.style.DarkDialogTheme)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton("Tamam") { _, _ -> onPin(input.text.toString()) }
            .setNegativeButton("İptal", null)
            .create()

        // 4 hane girilince otomatik onayla
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.length == 4) {
                    dialog.dismiss()
                    onPin(s.toString())
                }
            }
        })
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }
}
