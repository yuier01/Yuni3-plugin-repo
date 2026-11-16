package bilibili.api;

import bilibili.api.model.BilibiliVideoInfo;
import bilibili.api.model.GetVideoInfoRequest;
import bilibili.api.model.ParseUrlRequest;
import bilibili.api.model.ParseUrlResponse;
import bilibili.api.model.VideoInfoResponse;
import bilibili.parser.BilibiliVideoTarget;
import com.yuier.yuni.plugin.api.service.YuniHttp;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * B 站第三方接口客户端。
 *
 * <p>接口服务说明见 <a href="https://biliapi.yuier.com/docs">biliapi.yuier.com</a>。
 * 短链接先通过 {@code /v1/common/parse_url} 解析出 BV 号，再通过
 * {@code /v1/video/get_info} 获取视频信息；直接给出 BV 号时跳过第一步。</p>
 *
 * <p>本类只负责协议调用与字段映射，不负责消息排版。</p>
 */
public final class BilibiliApiClient {

    /** 第三方接口服务地址。 */
    private static final String BASE_URL = "https://biliapi.yuier.com";

    /** 分享链接解析接口。 */
    private static final String PATH_PARSE_URL = "/v1/common/parse_url";

    /** 视频信息接口。 */
    private static final String PATH_VIDEO_INFO = "/v1/video/get_info";

    /** B 站视频长链接前缀。 */
    private static final String VIDEO_URL_PREFIX = "https://www.bilibili.com/video/";

    /** HTTP 客户端。 */
    private final YuniHttp http;

    /** 插件日志。 */
    private final Logger logger;

    /**
     * 构造客户端。
     *
     * @param http   HTTP 客户端
     * @param logger 插件日志
     */
    public BilibiliApiClient(YuniHttp http, Logger logger) {
        this.http = Objects.requireNonNull(http, "http");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * 获取视频基本信息。
     *
     * @param target 消息中提取到的视频入口；BV 号或短链接
     * @return 视频信息
     * @throws BilibiliApiException 接口失败、返回异常或链接不是视频时抛出
     */
    public BilibiliVideoInfo fetchVideoInfo(BilibiliVideoTarget target) {
        Objects.requireNonNull(target, "target");

        try {
            String bvId = target.hasBvId() ? target.bvId() : resolveBvId(target.shortUrl());
            VideoInfoResponse response = http.post(BASE_URL + PATH_VIDEO_INFO)
                    .jsonBody(new GetVideoInfoRequest(bvId))
                    .execute(VideoInfoResponse.class);

            ensureSuccess(response, "获取视频信息");
            if (response.data() == null) {
                throw new BilibiliApiException("接口未返回视频信息。");
            }
            return toVideoInfo(bvId, target.page(), response.data());
        } catch (YuniHttp.HttpException e) {
            logger.debug("B站接口请求失败: {}", e.getMessage(), e);
            throw new BilibiliApiException("B站接口请求失败，请稍后重试。", e);
        }
    }

    /**
     * 把 b23.tv 短链接解析为 BV 号。
     *
     * @param shortUrl 短链接
     * @return BV 号
     * @throws BilibiliApiException 解析失败或链接不是视频时抛出
     */
    private String resolveBvId(String shortUrl) {
        ParseUrlResponse response = http.post(BASE_URL + PATH_PARSE_URL)
                .jsonBody(new ParseUrlRequest(shortUrl))
                .execute(ParseUrlResponse.class);

        ensureSuccess(response, "解析短链接");
        ParseUrlResponse.ParseUrlData data = response.data();
        if (data == null) {
            throw new BilibiliApiException("短链接解析结果为空。");
        }
        if (data.type() != null && !"video".equalsIgnoreCase(data.type())) {
            throw new BilibiliApiException("该链接不是视频（类型：" + data.type() + "）。");
        }
        if (data.bvid() == null || data.bvid().isBlank()) {
            throw new BilibiliApiException("短链接未解析到视频 BV 号。");
        }
        return data.bvid().trim();
    }

    /**
     * 校验接口响应。
     *
     * @param response 响应
     * @param action   正在执行的操作，用于错误提示
     */
    private static void ensureSuccess(ParseUrlResponse response, String action) {
        if (response == null) {
            throw new BilibiliApiException(action + "失败：接口返回为空。");
        }
        ensureSuccess(response.code(), response.message(), action);
    }

    /**
     * 校验接口响应。
     *
     * @param response 响应
     * @param action   正在执行的操作，用于错误提示
     */
    private static void ensureSuccess(VideoInfoResponse response, String action) {
        if (response == null) {
            throw new BilibiliApiException(action + "失败：接口返回为空。");
        }
        ensureSuccess(response.code(), response.message(), action);
    }

    /**
     * 校验业务状态码。
     *
     * @param code    业务状态码
     * @param message 接口返回的描述
     * @param action  正在执行的操作，用于错误提示
     */
    private static void ensureSuccess(int code, String message, String action) {
        if (code != 0) {
            String detail = message == null || message.isBlank() ? "未知错误" : message;
            throw new BilibiliApiException(action + "失败：" + detail);
        }
    }

    /**
     * 把接口响应映射为展示用视频信息。
     *
     * @param bvId BV 号
     * @param page 分 P 序号
     * @param data 接口返回的视频信息
     * @return 展示用视频信息
     */
    private static BilibiliVideoInfo toVideoInfo(String bvId, int page,
                                                 VideoInfoResponse.VideoInfoData data) {
        long duration = durationOf(data, page);
        String title = textOrDefault(data.title(), bvId);
        String ownerName = data.owner() == null
                ? "未知UP主"
                : textOrDefault(data.owner().name(), "未知UP主");
        String cover = data.cover() == null ? "" : data.cover();

        VideoInfoResponse.Stat stat = data.stat();
        BilibiliVideoInfo.Stats stats = stat == null
                ? new BilibiliVideoInfo.Stats(0, 0, 0, 0)
                : new BilibiliVideoInfo.Stats(
                        stat.view(), stat.like(), stat.danmaku(), stat.reply());

        return new BilibiliVideoInfo(
                bvId,
                title,
                cover,
                ownerName,
                duration,
                videoUrl(bvId, page),
                stats);
    }

    /**
     * 取目标分 P 的时长；找不到时回退到视频总时长。
     *
     * @param data 视频信息
     * @param page 分 P 序号
     * @return 时长（秒）
     */
    private static long durationOf(VideoInfoResponse.VideoInfoData data, int page) {
        long duration = data.duration();
        List<VideoInfoResponse.Page> pages = data.pages();
        if (pages != null) {
            for (VideoInfoResponse.Page item : pages) {
                if (item != null && item.page() == page) {
                    duration = item.duration();
                    break;
                }
            }
        }
        return Math.max(duration, 0);
    }

    /**
     * 拼接 B 站标准视频长链接。
     *
     * @param bvId BV 号
     * @param page 分 P 序号
     * @return 视频长链接
     */
    private static String videoUrl(String bvId, int page) {
        return VIDEO_URL_PREFIX + bvId + (page > 1 ? "?p=" + page : "");
    }

    /**
     * 文本为空时返回默认值。
     *
     * @param value        原文本
     * @param defaultValue 默认值
     * @return 非空文本或默认值
     */
    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
