package bilibili.api.model;

import java.util.List;

/**
 * {@code POST /v1/video/get_info} 的响应。
 *
 * <p>只声明插件真正使用的字段；接口返回的其它字段由 Jackson 忽略。</p>
 *
 * @param code    业务状态码，{@code 0} 表示成功
 * @param message 状态描述
 * @param data    视频信息
 */
public record VideoInfoResponse(int code, String message, VideoInfoData data) {

    /**
     * 视频信息。
     *
     * @param bvid     BV 号
     * @param title    标题
     * @param cover    封面地址
     * @param duration 视频时长（秒）
     * @param owner    作者
     * @param stat     统计数据
     * @param pages    分 P 列表
     */
    public record VideoInfoData(
            String bvid,
            String title,
            String cover,
            long duration,
            Owner owner,
            Stat stat,
            List<Page> pages) {
    }

    /**
     * 视频作者。
     *
     * @param name 作者昵称
     */
    public record Owner(String name) {
    }

    /**
     * 视频统计数据。
     *
     * @param view    播放量
     * @param like    点赞数
     * @param danmaku 弹幕数
     * @param reply   评论数
     */
    public record Stat(long view, long like, long danmaku, long reply) {
    }

    /**
     * 分 P 信息。
     *
     * @param page     分 P 序号，从 1 开始
     * @param part     分 P 标题
     * @param duration 分 P 时长（秒）
     */
    public record Page(int page, String part, long duration) {
    }
}
