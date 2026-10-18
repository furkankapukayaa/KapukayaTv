package com.example

import com.example.data.parser.M3uParser
import com.example.data.parser.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U x-tvg-url="https://example.com/epg.xml.gz"
        #EXTINF:-1 tvg-id="TRT1.tr@HD" tvg-logo="https://logo/trt1.png" group-title="General",TRT 1 (1080p)
        https://example.com/trt1/index.m3u8
        #EXTINF:-1 tvg-id="" group-title="News;General",Haber, Kanalı
        #EXTVLCOPT:http-user-agent=TestAgent/1.0
        #EXTVLCOPT:http-referrer=https://example.com/
        http://example.com/haber.m3u8
        #EXTINF:-1,İsimsiz Grup
        #EXTGRP:Spor
        http://example.com/spor.ts
    """.trimIndent()

    @Test
    fun parsesHeaderEpgUrl() {
        val parsed = M3uParser.parse(sample)
        assertEquals("https://example.com/epg.xml.gz", parsed.epgUrl)
    }

    @Test
    fun parsesChannelsAndAttributes() {
        val channels = M3uParser.parse(sample).channels
        assertEquals(3, channels.size)

        val trt = channels[0]
        assertEquals("TRT1.tr@HD", trt.id)
        assertEquals("TRT 1 (1080p)", trt.name)
        assertEquals("https://logo/trt1.png", trt.logoUrl)
        assertEquals("General", trt.groupTitle)
        assertEquals("https://example.com/trt1/index.m3u8", trt.streamUrl)
    }

    @Test
    fun titleMayContainCommas() {
        val haber = M3uParser.parse(sample).channels[1]
        assertEquals("Haber, Kanalı", haber.name)
        assertNull(haber.tvgId)
    }

    @Test
    fun extGrpIsUsedWhenGroupTitleMissing() {
        val spor = M3uParser.parse(sample).channels[2]
        assertEquals("Spor", spor.groupTitle)
    }

    @Test
    fun vlcOptionsBecomeStreamHeaders() {
        val haber = M3uParser.parse(sample).channels[1]
        val source = StreamSource.parse(haber.streamUrl)
        assertEquals("http://example.com/haber.m3u8", source.url)
        assertEquals("TestAgent/1.0", source.userAgent)
        assertEquals("https://example.com/", source.headers["Referer"])
    }

    @Test
    fun kodiStylePipeHeadersAreParsed() {
        val source = StreamSource.parse("http://a.com/live.m3u8|User-Agent=Foo%20Bar&Referer=http%3A%2F%2Fb.com")
        assertEquals("http://a.com/live.m3u8", source.url)
        assertEquals("Foo Bar", source.userAgent)
        assertEquals("http://b.com", source.headers["Referer"])
    }

    @Test
    fun plainUrlHasNoHeaders() {
        val source = StreamSource.parse("http://a.com/live.m3u8")
        assertEquals("http://a.com/live.m3u8", source.url)
        assertTrue(source.headers.isEmpty())
    }
}
