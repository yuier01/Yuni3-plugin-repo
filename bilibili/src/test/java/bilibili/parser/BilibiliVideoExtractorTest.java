package bilibili.parser;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.data.JsonData;
import com.yuier.yuni.core.model.message.segment.JsonSegment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliVideoExtractorTest {

    @Test
    void extractsBvIdFromText() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("来看看 BV1xx411c7mD 这个视频")
                .orElseThrow();

        assertEquals("BV1xx411c7mD", target.bvId());
        assertNull(target.shortUrl());
        assertEquals(1, target.page());
    }

    @Test
    void normalizesLowercaseBvPrefix() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("bv1xx411c7mD")
                .orElseThrow();

        assertEquals("BV1xx411c7mD", target.bvId());
    }

    @Test
    void extractsBvIdAndPageFromVideoUrl() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("https://www.bilibili.com/video/BV1xx411c7mD?p=3")
                .orElseThrow();

        assertEquals("BV1xx411c7mD", target.bvId());
        assertEquals(3, target.page());
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?p=3", target.videoUrl());
    }

    @Test
    void ignoresBvIdThatIsPartOfALongerToken() {
        assertTrue(BilibiliVideoExtractor.extractFromText("ABV1xx411c7mD").isEmpty());
        assertTrue(BilibiliVideoExtractor.extractFromText("BV1xx411c7mDxyz").isEmpty());
    }

    @Test
    void extractsShortUrlFromText() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("分享 https://b23.tv/me5NG2c 给你")
                .orElseThrow();

        assertFalse(target.hasBvId());
        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void completesSchemeForShortUrl() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("b23.tv/me5NG2c")
                .orElseThrow();

        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void trimsTrailingPunctuationFromShortUrl() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("看这个 https://b23.tv/me5NG2c?p=2。")
                .orElseThrow();

        assertEquals("https://b23.tv/me5NG2c?p=2", target.shortUrl());
        assertEquals(2, target.page());
    }

    @Test
    void extractsShortUrlFromShareCardJson() throws IOException {
        MessageChain chain = new MessageChain(shareCardSegment());

        BilibiliVideoTarget target = BilibiliVideoExtractor.extract(chain).orElseThrow();

        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void extractsShortUrlFromShareCardEvent() throws IOException {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setMessageChain(new MessageChain(shareCardSegment()));

        assertTrue(BilibiliVideoExtractor.extract(event).isPresent());
    }

    @Test
    void fallsBackToMessageSegmentsWhenChainIsMissing() throws IOException {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setMessage(List.of(shareCardSegment()));

        BilibiliVideoTarget target = BilibiliVideoExtractor.extract(event).orElseThrow();

        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void fallsBackToTextScanningForMalformedShareCardJson() {
        String malformed = "{\"qqdocurl\":\"https:\\/\\/b23.tv\\/me5NG2c\"";

        BilibiliVideoTarget target = BilibiliVideoExtractor.extractFromJson(malformed).orElseThrow();

        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void extractsShortUrlFromDoubleEscapedText() {
        BilibiliVideoTarget target = BilibiliVideoExtractor
                .extractFromText("https:\\\\/\\\\/b23.tv\\\\/me5NG2c")
                .orElseThrow();

        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void fallsBackToRawJsonWhenChainAndMessageAreMissing() {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setRawJson("{\"data\":\"https:\\\\/\\\\/b23.tv\\\\/me5NG2c\"}");

        BilibiliVideoTarget target = BilibiliVideoExtractor.extract(event).orElseThrow();

        assertEquals("https://b23.tv/me5NG2c", target.shortUrl());
    }

    @Test
    void ignoresUnrelatedLinks() {
        assertTrue(BilibiliVideoExtractor.extractFromText(
                "https://example.com/video/12345").isEmpty());
    }

    private static JsonSegment shareCardSegment() throws IOException {
        String json = readResource("/bilibili-share-card.json");

        JsonData data = new JsonData();
        data.setData(json);

        JsonSegment segment = new JsonSegment();
        segment.setData(data);
        return segment;
    }

    private static String readResource(String path) throws IOException {
        try (InputStream input = BilibiliVideoExtractorTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("测试资源不存在: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
