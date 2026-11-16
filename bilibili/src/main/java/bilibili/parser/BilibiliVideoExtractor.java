package bilibili.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.constants.MessageSegmentTypes;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.MessageSegment;
import com.yuier.yuni.core.model.message.segment.JsonSegment;
import com.yuier.yuni.core.model.message.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从聊天消息中提取 B 站视频入口。
 * 算法参考:
 * <a href="https://github.com/Ling-LA/ling-video-bot/blob/master/plugin.py">ling-video-bot</a>
 * <a href="https://github.com/BestBcz/BiliURL/blob/master/src/main/kotlin/com/bcz/bilivideoparser/BiliVideoParser.kt">BiliVideoParser</a>
 *
 * <h2>支持的消息形态</h2>
 * <ul>
 *   <li>普通文本中的 BV 号，例如 {@code BV1xx411c7mD}；</li>
 *   <li>文本中的 B 站视频链接，例如 {@code https://www.bilibili.com/video/BV...}；</li>
 *   <li>文本中的 b23.tv 短链接，例如 {@code https://b23.tv/me5NG2c}；</li>
 *   <li>QQ 分享卡片。卡片以 json 消息段上报，真实链接通常藏在
 *       {@code message.data.data} 反序列化后的 {@code meta.detail_1.qqdocurl} 中。</li>
 * </ul>
 *
 * <p>本类只做识别与提取，不会访问网络。短链接会被原样保留，交由后续逻辑决定
 * 是否解析跳转。</p>
 */
public final class BilibiliVideoExtractor {

    /**
     * BV 号使用的 Base58 字符集。
     *
     * <p>BV 号形如 {@code BV1} + 9 位该字符集内的字符。限定字符集可以避免把
     * 普通的英文单词或数字串误判为 BV 号。</p>
     */
    private static final String BV_ID_ALPHABET =
            "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";

    /** 独立出现的 BV 号；前后都不能再连接字母或数字。 */
    private static final Pattern BV_ID_PATTERN = Pattern.compile(
            "(?<![0-9A-Za-z])[Bb][Vv]1[" + BV_ID_ALPHABET + "]{9}(?![0-9A-Za-z])");

    /** 带协议的 B 站视频链接，允许 www、m、t 子域。 */
    private static final Pattern BILIBILI_VIDEO_URL_PATTERN = Pattern.compile(
            "(?i)https?://(?:www\\.|m\\.|t\\.)?bilibili\\.com/video/"
                    + "[Bb][Vv]1[" + BV_ID_ALPHABET + "]{9}(?:[/?#][^\\s\"'<>]*)?");

    /** b23.tv 短链接；允许省略协议，也允许 www 前缀。 */
    private static final Pattern B23_SHORT_URL_PATTERN = Pattern.compile(
            "(?i)(?<![0-9A-Za-z])(?:https?://)?(?:www\\.)?b23\\.tv/"
                    + "[0-9A-Za-z]+(?:[/?#][^\\s\"'<>]*)?");

    /** URL 中的分 P 参数。 */
    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]p=(\\d+)");

    /** 解析分享卡片 JSON 时使用的解析器；只读，可安全复用。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 递归扫描 JSON 的最大深度，避免异常结构导致栈过深。 */
    private static final int MAX_JSON_DEPTH = 12;

    /** 单个分享卡片最多扫描的文本字段数，避免无意义地遍历超大 JSON。 */
    private static final int MAX_JSON_TEXT_VALUES = 256;

    /** 工具类，禁止实例化。 */
    private BilibiliVideoExtractor() {
    }

    /**
     * 从消息事件中提取 B 站视频入口。
     *
     * <p>优先读取已经装配好的消息链；消息链缺失或未命中时，依次回退到
     * {@code message} 列表和 {@code raw_message}；仅当消息段列表完全缺失时，
     * 才扫描整个原始事件 JSON。回退分支主要覆盖测试中手工构造的事件，以及
     * 适配器未能正常解析 json 消息段的场景。</p>
     *
     * @param event 消息事件；可以为 {@code null}
     * @return 提取到的视频入口；未识别时为空
     */
    public static Optional<BilibiliVideoTarget> extract(YuniMessageEvent event) {
        if (event == null) {
            return Optional.empty();
        }

        Optional<BilibiliVideoTarget> target = extract(event.getChain());
        if (target.isPresent()) {
            return target;
        }

        target = extractFromSegments(event.getMessage());
        if (target.isPresent()) {
            return target;
        }

        target = extractFromText(event.getRawMessage());
        if (target.isPresent()) {
            return target;
        }

        // 仅当消息段列表也缺失时才扫描整个事件 JSON，避免把发送者昵称、群名等
        // 与消息内容无关的字段误判为视频入口。
        if (event.getMessage() == null || event.getMessage().isEmpty()) {
            return extractFromText(event.getRawJson());
        }
        return Optional.empty();
    }

    /**
     * 从消息链中提取 B 站视频入口。
     *
     * @param chain 消息链；可以为 {@code null}
     * @return 提取到的视频入口；未识别时为空
     */
    public static Optional<BilibiliVideoTarget> extract(MessageChain chain) {
        if (chain == null) {
            return Optional.empty();
        }
        return extractFromSegments(chain.getContent());
    }

    /**
     * 从一段文本中提取 B 站视频入口。
     *
     * <p>识别顺序为：完整的 B 站视频链接 → 独立 BV 号 → b23.tv 短链接。
     * 完整链接优先是为了拿到链接中可能存在的 {@code p} 参数。</p>
     *
     * @param text 待扫描文本；可以为 {@code null}
     * @return 提取到的视频入口；未识别时为空
     */
    public static Optional<BilibiliVideoTarget> extractFromText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        String normalized = normalize(text);

        Matcher videoUrlMatcher = BILIBILI_VIDEO_URL_PATTERN.matcher(normalized);
        if (videoUrlMatcher.find()) {
            String videoUrl = videoUrlMatcher.group();
            Matcher bvIdMatcher = BV_ID_PATTERN.matcher(videoUrl);
            if (bvIdMatcher.find()) {
                return Optional.of(BilibiliVideoTarget.ofBvId(
                        normalizeBvId(bvIdMatcher.group()), pageOf(videoUrl)));
            }
        }

        Matcher bvIdMatcher = BV_ID_PATTERN.matcher(normalized);
        if (bvIdMatcher.find()) {
            return Optional.of(BilibiliVideoTarget.ofBvId(
                    normalizeBvId(bvIdMatcher.group()), pageOf(normalized)));
        }

        Matcher shortUrlMatcher = B23_SHORT_URL_PATTERN.matcher(normalized);
        if (shortUrlMatcher.find()) {
            String shortUrl = ensureHttpScheme(trimTrailingPunctuation(shortUrlMatcher.group()));
            return Optional.of(BilibiliVideoTarget.ofShortUrl(shortUrl, pageOf(shortUrl)));
        }

        return Optional.empty();
    }

    /**
     * 从 QQ 分享卡片的 JSON 字符串中提取 B 站视频入口。
     *
     * <p>先按结构读取 {@code meta.detail_1.qqdocurl}，再兜底递归扫描所有文本字段。
     * 卡片字段不是严格 JSON 时，直接对原文做正则扫描。</p>
     *
     * @param json json 消息段中的 {@code data} 字符串；可以为 {@code null}
     * @return 提取到的视频入口；未识别时为空
     */
    static Optional<BilibiliVideoTarget> extractFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonNode root = JSON.readTree(json);

            Optional<String> qqDocUrl = qqDocUrl(root);
            if (qqDocUrl.isPresent()) {
                Optional<BilibiliVideoTarget> target = extractFromText(qqDocUrl.get());
                if (target.isPresent()) {
                    return target;
                }
            }

            List<String> textValues = new ArrayList<>();
            collectTextValues(root, textValues, 0);
            for (String value : textValues) {
                Optional<BilibiliVideoTarget> target = extractFromText(value);
                if (target.isPresent()) {
                    return target;
                }
            }
        } catch (JsonProcessingException ignored) {
            // 分享卡片的 data 字段偶尔不是严格 JSON，退回原文扫描即可。
        }

        return extractFromText(json);
    }

    /**
     * 按消息段顺序提取。文本段单独扫描后再拼接扫描一次，以覆盖链接被拆到
     * 多个消息段中的情况。
     *
     * @param segments 消息段列表；可以为 {@code null}
     * @return 提取到的视频入口；未识别时为空
     */
    private static Optional<BilibiliVideoTarget> extractFromSegments(List<MessageSegment> segments) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder joinedText = new StringBuilder();
        for (MessageSegment segment : segments) {
            if (segment == null) {
                continue;
            }

            if (segment.typeOf(MessageSegmentTypes.TEXT) && segment instanceof TextSegment textSegment) {
                String text = textSegment.getText();
                if (text == null || text.isBlank()) {
                    continue;
                }
                Optional<BilibiliVideoTarget> target = extractFromText(text);
                if (target.isPresent()) {
                    return target;
                }
                joinedText.append(text);
            } else if (segment.typeOf(MessageSegmentTypes.JSON)
                    && segment instanceof JsonSegment jsonSegment) {
                Optional<BilibiliVideoTarget> target = extractFromJson(jsonSegment.getJsonData());
                if (target.isPresent()) {
                    return target;
                }
            }
        }

        return extractFromText(joinedText.toString());
    }

    /**
     * 读取分享卡片中的 {@code meta.detail_1.qqdocurl}。
     *
     * @param root 卡片 JSON 根节点
     * @return 字段值；字段缺失或为空时为空
     */
    private static Optional<String> qqDocUrl(JsonNode root) {
        if (root == null) {
            return Optional.empty();
        }
        JsonNode value = root.path("meta").path("detail_1").path("qqdocurl");
        if (value.isTextual() && !value.asText().isBlank()) {
            return Optional.of(value.asText());
        }
        return Optional.empty();
    }

    /**
     * 递归收集 JSON 中的所有非空文本字段。
     *
     * @param node   当前节点
     * @param values 收集结果
     * @param depth  当前深度
     */
    private static void collectTextValues(JsonNode node, List<String> values, int depth) {
        if (node == null || depth > MAX_JSON_DEPTH || values.size() >= MAX_JSON_TEXT_VALUES) {
            return;
        }

        if (node.isTextual()) {
            String value = node.asText();
            if (!value.isBlank()) {
                values.add(value);
            }
            return;
        }

        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                collectTextValues(child, values, depth + 1);
                if (values.size() >= MAX_JSON_TEXT_VALUES) {
                    return;
                }
            }
        }
    }

    /**
     * 还原 JSON / CQ 码中常见的转义，便于同一条正则同时处理文本和卡片。
     *
     * @param value 原始文本
     * @return 规范化后的文本
     */
    private static String normalize(String value) {
        return value.replace("\\\\/", "/")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&");
    }

    /**
     * 把 BV 号前缀统一为大写的 {@code BV}。
     *
     * @param bvId 原始 BV 号
     * @return 规范化后的 BV 号
     */
    private static String normalizeBvId(String bvId) {
        return "BV" + bvId.substring(2);
    }

    /**
     * 从 URL 中读取分 P 序号。
     *
     * @param url URL 或包含 URL 的文本
     * @return 分 P 序号；缺失或非法时返回 1
     */
    private static int pageOf(String url) {
        if (url == null) {
            return 1;
        }
        Matcher matcher = PAGE_PATTERN.matcher(normalize(url));
        if (!matcher.find()) {
            return 1;
        }
        try {
            return Math.max(Integer.parseInt(matcher.group(1)), 1);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    /**
     * 补全短链接的协议。
     *
     * @param url 可能省略协议的短链接
     * @return 带协议的短链接
     */
    private static String ensureHttpScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return url;
        }
        return "https://" + url;
    }

    /**
     * 去掉短链接末尾可能粘连的中英文标点。
     *
     * @param url 原始短链接
     * @return 去掉末尾标点后的短链接
     */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && "。，,.;；!！?？)）]】}》".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
