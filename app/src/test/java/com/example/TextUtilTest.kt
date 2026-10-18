package com.example

import com.example.data.model.Channel
import com.example.util.TextUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilTest {

    @Test
    fun searchIgnoresTurkishCharacters() {
        assertTrue(TextUtil.matchesQuery("sozcu", "Sözcü TV"))
        assertTrue(TextUtil.matchesQuery("SÖZCÜ", "sozcu tv"))
        assertTrue(TextUtil.matchesQuery("cocuk", "TRT Çocuk"))
        assertTrue(TextUtil.matchesQuery("istanbul", "İSTANBUL TV"))
    }

    @Test
    fun searchMatchesAllWordsInAnyOrder() {
        assertTrue(TextUtil.matchesQuery("spor trt", "TRT Spor Yıldız"))
        assertFalse(TextUtil.matchesQuery("trt haber", "TRT Spor"))
    }

    @Test
    fun searchAlsoLooksAtGroups() {
        assertTrue(TextUtil.matchesQuery("haber", "NTV", "Haber"))
    }

    @Test
    fun groupsAreSplitAndTranslated() {
        assertEquals(listOf("Haber", "Genel"), TextUtil.splitGroups("News;General"))
        assertEquals(listOf("Genel"), TextUtil.splitGroups(null))
        assertEquals(listOf("Yerel Kanallar"), TextUtil.splitGroups("Yerel Kanallar"))
    }

    @Test
    fun channelStreamListIncludesAlternatesWithoutDuplicates() {
        val channel = Channel(
            id = "x",
            name = "X",
            streamUrl = "http://a",
            alternateUrls = "http://b\n\nhttp://a\nhttp://c"
        )
        assertEquals(listOf("http://a", "http://b", "http://c"), channel.allStreamUrls())
    }
}
