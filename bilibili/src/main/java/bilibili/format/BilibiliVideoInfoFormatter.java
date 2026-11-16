package bilibili.format;

import bilibili.api.model.BilibiliVideoInfo;

import java.util.Locale;

/**
 * 把视频信息排版成适合 QQ 消息展示的纯文本。
 */
public final class BilibiliVideoInfoFormatter {

    /** 未知字段占位。 */
    private static final String UNKNOWN = "未知";

    /** 工具类，禁止实例化。 */
    private BilibiliVideoInfoFormatter() {
    }

    /**
     * 生成视频信息文本。
     *
     * @param info 视频信息
     * @return 多行文本
     */
    public static String format(BilibiliVideoInfo info) {
        BilibiliVideoInfo.Stats stats = info.stats() == null
                ? new BilibiliVideoInfo.Stats(0, 0, 0, 0)
                : info.stats();

        StringBuilder text = new StringBuilder(256);
        text.append("【B站视频】\n");
        text.append("标题：").append(valueOrDefault(info.title())).append('\n');
        text.append("UP主：").append(valueOrDefault(info.ownerName())).append('\n');
        text.append("时长：").append(formatDuration(info.durationSeconds())).append('\n');
        text.append("播放：").append(formatCount(stats.view()))
                .append("　点赞：").append(formatCount(stats.like())).append('\n');
        text.append("弹幕：").append(formatCount(stats.danmaku()))
                .append("　评论：").append(formatCount(stats.reply())).append('\n');
        text.append("链接：").append(valueOrDefault(info.videoUrl()));
        return text.toString();
    }

    /**
     * 把秒数格式化为 {@code HH:mm:ss}。
     *
     * @param seconds 时长（秒）
     * @return 格式化后的时长
     */
    static String formatDuration(long seconds) {
        long safeSeconds = Math.max(seconds, 0);
        long hours = safeSeconds / 3600;
        long minutes = (safeSeconds % 3600) / 60;
        long remainingSeconds = safeSeconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
                hours, minutes, remainingSeconds);
    }

    /**
     * 把计数格式化为带中文单位的短文本。
     *
     * @param count 原始计数
     * @return 例如 {@code 9999}、{@code 1.2万}、{@code 1亿}
     */
    static String formatCount(long count) {
        long safeCount = Math.max(count, 0);
        if (safeCount >= 100_000_000L) {
            return formatUnit(safeCount / 100_000_000.0, "亿");
        }
        if (safeCount >= 10_000L) {
            return formatUnit(safeCount / 10_000.0, "万");
        }
        return Long.toString(safeCount);
    }

    /**
     * 格式化一个带单位的数值，整数时去掉 {@code .0}。
     *
     * @param value 数值
     * @param unit  单位
     * @return 格式化结果
     */
    private static String formatUnit(double value, String unit) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        if (formatted.endsWith(".0")) {
            formatted = formatted.substring(0, formatted.length() - 2);
        }
        return formatted + unit;
    }

    /**
     * 空文本回退为「未知」。
     *
     * @param value 原文本
     * @return 非空文本或「未知」
     */
    private static String valueOrDefault(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
