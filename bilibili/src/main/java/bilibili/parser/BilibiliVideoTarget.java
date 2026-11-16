package bilibili.parser;

import java.util.Objects;

/**
 * 从聊天消息中提取到的 B 站视频入口。
 *
 * <p>消息里可能直接给出了 BV 号，也可能只给出了 b23.tv 短链接。本对象只描述
 * 「提取到了什么」，不负责短链接跳转，也不负责查询视频详情。</p>
 *
 * @param bvId     BV 号（形如 {@code BV1xx411c7mD}）；短链接入口时为 {@code null}
 * @param shortUrl 完整的 b23.tv 短链接；BV 号入口时为 {@code null}
 * @param page     分 P 序号，从 1 开始
 */
public record BilibiliVideoTarget(String bvId, String shortUrl, int page) {

    /**
     * 校验并规范化视频入口。
     *
     * @throws IllegalArgumentException 当 BV 号与短链接同时为空或同时存在时
     */
    public BilibiliVideoTarget {
        if ((bvId == null) == (shortUrl == null)) {
            throw new IllegalArgumentException("bvId 与 shortUrl 必须且只能有一个非空");
        }
        if (page < 1) {
            page = 1;
        }
    }

    /**
     * 创建一个 BV 号入口。
     *
     * @param bvId BV 号
     * @param page 分 P 序号
     * @return 视频入口
     */
    public static BilibiliVideoTarget ofBvId(String bvId, int page) {
        return new BilibiliVideoTarget(Objects.requireNonNull(bvId, "bvId"), null, page);
    }

    /**
     * 创建一个短链接入口。
     *
     * @param shortUrl 带 http(s) 协议的 b23.tv 短链接
     * @param page     分 P 序号
     * @return 视频入口
     */
    public static BilibiliVideoTarget ofShortUrl(String shortUrl, int page) {
        return new BilibiliVideoTarget(null, Objects.requireNonNull(shortUrl, "shortUrl"), page);
    }

    /**
     * 是否已经直接拿到了 BV 号。
     *
     * @return 直接包含 BV 号时返回 {@code true}
     */
    public boolean hasBvId() {
        return bvId != null;
    }

    /**
     * 返回可访问的视频地址。
     *
     * <p>BV 号入口会拼接为 B 站标准视频链接；短链接入口原样返回。</p>
     *
     * @return 视频地址
     */
    public String videoUrl() {
        if (bvId == null) {
            return shortUrl;
        }
        return "https://www.bilibili.com/video/" + bvId + (page > 1 ? "?p=" + page : "");
    }
}
