package bilibili.format;

import bilibili.api.model.BilibiliVideoInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliVideoInfoFormatterTest {

    @Test
    void formatsVideoInfoForMessage() {
        BilibiliVideoInfo info = new BilibiliVideoInfo(
                "BV1xx411c7mD",
                "字幕君交流场所",
                "https://i0.hdslb.com/bfs/archive/transparent.png",
                "碧诗",
                2055,
                "https://www.bilibili.com/video/BV1xx411c7mD",
                new BilibiliVideoInfo.Stats(5_624_548, 278_816, 129_042, 89_738));

        String text = BilibiliVideoInfoFormatter.format(info);

        assertTrue(text.contains("【B站视频】"));
        assertTrue(text.contains("标题：字幕君交流场所"));
        assertTrue(text.contains("UP主：碧诗"));
        assertTrue(text.contains("时长：00:34:15"));
        assertTrue(text.contains("播放：562.5万　点赞：27.9万"));
        assertTrue(text.contains("弹幕：12.9万　评论：9万"));
        assertTrue(text.contains("链接：https://www.bilibili.com/video/BV1xx411c7mD"));
    }

    @Test
    void formatsDurationAsHoursMinutesSeconds() {
        assertEquals("00:00:00", BilibiliVideoInfoFormatter.formatDuration(0));
        assertEquals("00:34:15", BilibiliVideoInfoFormatter.formatDuration(2055));
        assertEquals("01:01:01", BilibiliVideoInfoFormatter.formatDuration(3661));
        assertEquals("00:00:00", BilibiliVideoInfoFormatter.formatDuration(-1));
    }

    @Test
    void formatsCountWithChineseUnits() {
        assertEquals("9999", BilibiliVideoInfoFormatter.formatCount(9_999));
        assertEquals("1万", BilibiliVideoInfoFormatter.formatCount(10_000));
        assertEquals("562.5万", BilibiliVideoInfoFormatter.formatCount(5_624_548));
        assertEquals("1亿", BilibiliVideoInfoFormatter.formatCount(100_000_000));
    }

    @Test
    void usesUnknownPlaceholdersForBlankFields() {
        BilibiliVideoInfo info = new BilibiliVideoInfo(
                "BV1xx411c7mD",
                " ",
                "",
                null,
                0,
                null,
                null);

        String text = BilibiliVideoInfoFormatter.format(info);

        assertTrue(text.contains("标题：未知"));
        assertTrue(text.contains("UP主：未知"));
        assertTrue(text.contains("链接：未知"));
    }
}
