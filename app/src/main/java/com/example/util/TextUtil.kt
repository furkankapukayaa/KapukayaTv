package com.example.util

import java.util.Locale

object TextUtil {

    val TR_LOCALE: Locale = Locale.forLanguageTag("tr-TR")

    /**
     * Türkçe karakterleri sadeleştirip küçük harfe çevirir.
     * "Sözcü TV" -> "sozcu tv". Arama ve eşleştirme için kullanılır.
     */
    fun fold(text: String): String {
        return text.lowercase(TR_LOCALE)
            .replace('ç', 'c')
            .replace('ğ', 'g')
            .replace('ı', 'i')
            .replace("i\u0307", "i") // İ küçültülünce oluşan birleşik nokta
            .replace('ö', 'o')
            .replace('ş', 's')
            .replace('ü', 'u')
            .replace('â', 'a')
            .replace('î', 'i')
            .replace('û', 'u')
    }

    /** Sadece harf ve rakamları bırakır: "TRT 1 HD" -> "trt1hd" */
    fun compact(text: String): String = fold(text).replace(NON_ALNUM, "")

    /** Aramadaki her kelime metinde geçiyorsa eşleşir ("trt spor" -> "TRT Spor Yıldız"). */
    fun matchesQuery(query: String, vararg fields: String?): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val haystack = fields.filterNotNull().joinToString(" ") { fold(it) }
        return words.all { haystack.contains(it) }
    }

    private val NON_ALNUM = Regex("[^a-z0-9]")

    // iptv-org ve benzeri listelerdeki İngilizce grup adlarının Türkçe karşılıkları
    private val GROUP_TRANSLATIONS = mapOf(
        "general" to "Genel",
        "news" to "Haber",
        "kids" to "Çocuk",
        "children" to "Çocuk",
        "sports" to "Spor",
        "sport" to "Spor",
        "music" to "Müzik",
        "documentary" to "Belgesel",
        "entertainment" to "Eğlence",
        "religious" to "Dini",
        "movies" to "Film",
        "movie" to "Film",
        "series" to "Dizi",
        "business" to "Ekonomi",
        "culture" to "Kültür",
        "education" to "Eğitim",
        "lifestyle" to "Yaşam",
        "cooking" to "Yemek",
        "travel" to "Seyahat",
        "legislative" to "Meclis",
        "shop" to "Alışveriş",
        "weather" to "Hava Durumu",
        "science" to "Bilim",
        "comedy" to "Komedi",
        "family" to "Aile",
        "classic" to "Klasik",
        "animation" to "Animasyon",
        "outdoor" to "Doğa",
        "auto" to "Otomobil",
        "relax" to "Rahatlama",
        "public" to "Kamu",
        "local" to "Yerel",
        "undefined" to "Diğer",
        "other" to "Diğer"
    )

    /** "News;General" -> ["Haber", "Genel"] */
    fun splitGroups(groupTitle: String?): List<String> {
        if (groupTitle.isNullOrBlank()) return listOf("Genel")
        return groupTitle.split(';', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { GROUP_TRANSLATIONS[it.lowercase(Locale.ROOT)] ?: it }
            .distinct()
            .ifEmpty { listOf("Genel") }
    }

    fun displayGroups(groupTitle: String?): String = splitGroups(groupTitle).joinToString(" · ")
}
