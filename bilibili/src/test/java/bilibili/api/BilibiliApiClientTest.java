package bilibili.api;

import bilibili.api.model.BilibiliVideoInfo;
import bilibili.api.model.GetVideoInfoRequest;
import bilibili.api.model.ParseUrlRequest;
import bilibili.parser.BilibiliVideoTarget;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.yuier.yuni.plugin.api.service.YuniHttp;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliApiClientTest {

    private static final String PARSE_VIDEO_RESPONSE = """
            {"code":0,"message":"ok","data":{"type":"video","bvid":"BV1xx411c7mD",
            "url":"https://www.bilibili.com/video/BV1xx411c7mD"}}
            """;

    private static final String PARSE_ARTICLE_RESPONSE = """
            {"code":0,"message":"ok","data":{"type":"article","url":"https://www.bilibili.com/read/cv1"}}
            """;

    private static final String VIDEO_INFO_RESPONSE = """
            {"code":0,"message":"ok","data":{"bvid":"BV1xx411c7mD","title":"字幕君交流场所",
            "cover":"https://i0.hdslb.com/bfs/archive/transparent.png","duration":2055,
            "owner":{"mid":2,"name":"碧诗"},
            "stat":{"view":5624548,"danmaku":129042,"reply":89738,"like":278816},
            "pages":[{"page":1,"part":"","duration":2055}]}}
            """;

    @Test
    void resolvesShortUrlThenFetchesVideoInfo() {
        StubHttp http = new StubHttp();
        http.enqueue(PARSE_VIDEO_RESPONSE);
        http.enqueue(VIDEO_INFO_RESPONSE);
        BilibiliApiClient client = new BilibiliApiClient(
                http, LoggerFactory.getLogger(BilibiliApiClientTest.class));

        BilibiliVideoInfo info = client.fetchVideoInfo(
                BilibiliVideoTarget.ofShortUrl("https://b23.tv/me5NG2c", 1));

        assertEquals("BV1xx411c7mD", info.bvId());
        assertEquals("字幕君交流场所", info.title());
        assertEquals("碧诗", info.ownerName());
        assertEquals(2055, info.durationSeconds());
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", info.videoUrl());
        assertEquals(5_624_548, info.stats().view());
        assertEquals(278_816, info.stats().like());
        assertEquals(129_042, info.stats().danmaku());
        assertEquals(89_738, info.stats().reply());
        assertEquals(List.of(
                "https://biliapi.yuier.com/v1/common/parse_url",
                "https://biliapi.yuier.com/v1/video/get_info"), http.urls());
        assertEquals(List.of(
                new ParseUrlRequest("https://b23.tv/me5NG2c"),
                new GetVideoInfoRequest("BV1xx411c7mD")), http.bodies());
    }

    @Test
    void skipsUrlParsingForDirectBvId() {
        StubHttp http = new StubHttp();
        http.enqueue(VIDEO_INFO_RESPONSE);
        BilibiliApiClient client = new BilibiliApiClient(
                http, LoggerFactory.getLogger(BilibiliApiClientTest.class));

        BilibiliVideoInfo info = client.fetchVideoInfo(
                BilibiliVideoTarget.ofBvId("BV1xx411c7mD", 1));

        assertEquals("BV1xx411c7mD", info.bvId());
        assertEquals(List.of("https://biliapi.yuier.com/v1/video/get_info"), http.urls());
        assertEquals(List.of(new GetVideoInfoRequest("BV1xx411c7mD")), http.bodies());
    }

    @Test
    void rejectsNonVideoShortUrl() {
        StubHttp http = new StubHttp();
        http.enqueue(PARSE_ARTICLE_RESPONSE);
        BilibiliApiClient client = new BilibiliApiClient(
                http, LoggerFactory.getLogger(BilibiliApiClientTest.class));

        BilibiliApiException error = assertThrows(BilibiliApiException.class,
                () -> client.fetchVideoInfo(
                        BilibiliVideoTarget.ofShortUrl("https://b23.tv/me5NG2c", 1)));

        assertTrue(error.getMessage().contains("不是视频"));
    }

    @Test
    void surfacesApiErrorMessage() {
        StubHttp http = new StubHttp();
        http.enqueue("""
                {"code":-404,"message":"视频不存在","data":null}
                """);
        BilibiliApiClient client = new BilibiliApiClient(
                http, LoggerFactory.getLogger(BilibiliApiClientTest.class));

        BilibiliApiException error = assertThrows(BilibiliApiException.class,
                () -> client.fetchVideoInfo(
                        BilibiliVideoTarget.ofBvId("BV1xx411c7mD", 1)));

        assertTrue(error.getMessage().contains("视频不存在"));
    }

    /**
     * 按顺序返回预设 JSON 的 HTTP 桩。
     */
    private static final class StubHttp implements YuniHttp {

        private final ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        private final Deque<String> responses = new ArrayDeque<>();
        private final List<String> urls = new ArrayList<>();
        private final List<Object> bodies = new ArrayList<>();

        private void enqueue(String json) {
            responses.addLast(json);
        }

        private List<String> urls() {
            return urls;
        }

        private List<Object> bodies() {
            return bodies;
        }

        @Override
        public Request get(String url) {
            throw new UnsupportedOperationException("测试桩不支持 GET");
        }

        @Override
        public Request post(String url) {
            urls.add(url);
            if (responses.isEmpty()) {
                throw new IllegalStateException("缺少预设响应: " + url);
            }
            return new StubRequest(responses.removeFirst());
        }

        private final class StubRequest implements Request {

            private final String json;

            private StubRequest(String json) {
                this.json = json;
            }

            @Override
            public Request query(String name, Object value) {
                return this;
            }

            @Override
            public Request header(String name, String value) {
                return this;
            }

            @Override
            public Request jsonBody(Object body) {
                bodies.add(body);
                return this;
            }

            @Override
            public Request stringBody(String body) {
                return this;
            }

            @Override
            public Request timeout(Duration timeout) {
                return this;
            }

            @Override
            public <T> T execute(Class<T> type) {
                try {
                    return mapper.readValue(json, type);
                } catch (Exception e) {
                    throw new IllegalStateException("测试桩反序列化失败", e);
                }
            }

            @Override
            public String executeAsString() {
                return json;
            }

            @Override
            public boolean executeIgnoringResult() {
                return true;
            }
        }
    }
}
