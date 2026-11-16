package bilibili.api.model;

/**
 * 供展示层使用的 B 站视频信息。
 *
 * @param bvId            BV 号
 * @param title           标题
 * @param cover           封面地址
 * @param ownerName       作者昵称
 * @param durationSeconds 时长（秒）
 * @param videoUrl        B 站标准长链接
 * @param stats           播放、点赞、弹幕、评论数据
 */
public record BilibiliVideoInfo(
        String bvId,
        String title,
        String cover,
        String ownerName,
        long durationSeconds,
        String videoUrl,
        Stats stats) {

    /**
     * 统计数据。
     *
     * @param view    播放量
     * @param like    点赞数
     * @param danmaku 弹幕数
     * @param reply   评论数
     */
    public record Stats(long view, long like, long danmaku, long reply) {
    }

    /**
     * 是否有可用的封面地址。
     *
     * @return 封面非空时返回 {@code true}
     */
    public boolean hasCover() {
        return cover != null && !cover.isBlank();
    }
}
