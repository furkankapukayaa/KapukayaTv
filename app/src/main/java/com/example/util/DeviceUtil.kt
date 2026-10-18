package com.example.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space

/**
 * Telefon / TV ayrımı ve yalnızca telefonda uygulanan düzen ayarları.
 * TV'de (leanback) bu fonksiyonlar hiçbir şeyi değiştirmez.
 */
object DeviceUtil {

    fun isTv(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /** Telefon ve dar ekran (dikey telefon veya küçük yatay ekran). */
    fun isCompactPhone(context: Context): Boolean =
        !isTv(context) && context.resources.configuration.screenWidthDp < 600

    fun isPhonePortrait(context: Context): Boolean =
        !isTv(context) && context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Kök görünümün kenar boşluklarını telefona uygun hale getirir (yalnızca telefonda). */
    fun applyCompactPadding(root: View, horizontalDp: Int = 16, verticalDp: Int = 16) {
        if (!isCompactPhone(root.context)) return
        val h = dp(root.context, horizontalDp)
        val v = dp(root.context, verticalDp)
        root.setPadding(h, v, h, v)
    }

    /**
     * Yan yana dizilmiş buton satırlarını alt alta dizer (yalnızca dar telefon ekranında).
     * Örn. Ayarlar'daki "Liste Değiştir · Yeni Liste · Yeniden Adlandır · Sil" satırı.
     */
    fun stackButtonRows(root: View) {
        if (!isCompactPhone(root.context)) return
        stackRecursive(root)
    }

    private fun stackRecursive(view: View) {
        if (view !is ViewGroup) return
        if (view is LinearLayout && view.orientation == LinearLayout.HORIZONTAL) {
            val children = (0 until view.childCount).map { view.getChildAt(it) }
            val buttons = children.filterIsInstance<Button>()
            val onlyButtons = children.all { it is Button || it is Space }
            if (buttons.size >= 2 && onlyButtons) {
                view.orientation = LinearLayout.VERTICAL
                val margin = dp(view.context, 8)
                buttons.forEachIndexed { index, button ->
                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, button.layoutParams.height)
                    if (index > 0) lp.topMargin = margin
                    button.layoutParams = lp
                }
                view.layoutParams = view.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
                return
            }
        }
        for (i in 0 until view.childCount) stackRecursive(view.getChildAt(i))
    }

    /** İki kartlı seçim ekranında kartları dikey telefonda alt alta dizer. */
    fun stackCards(container: LinearLayout, spacer: View?) {
        if (!isPhonePortrait(container.context)) return
        container.orientation = LinearLayout.VERTICAL
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child === spacer) {
                child.layoutParams = LinearLayout.LayoutParams(1, dp(container.context, 16))
            } else {
                child.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                child.minimumHeight = dp(container.context, 150)
            }
        }
    }

    // ---- Agresif pil yönetimi olan üreticiler ----

    private val AGGRESSIVE_OEMS = setOf("huawei", "honor", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "meizu", "tecno", "infinix")

    fun hasAggressiveBatteryManager(): Boolean {
        val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase()
        val brand = (android.os.Build.BRAND ?: "").lowercase()
        return AGGRESSIVE_OEMS.any { manufacturer.contains(it) || brand.contains(it) }
    }

    fun manufacturerName(): String =
        (android.os.Build.MANUFACTURER ?: "Telefon").replaceFirstChar { it.uppercase() }
}
