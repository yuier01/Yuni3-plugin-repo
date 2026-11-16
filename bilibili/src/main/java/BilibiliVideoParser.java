import bilibili.api.BilibiliApiClient;
import bilibili.api.BilibiliApiException;
import bilibili.api.model.BilibiliVideoInfo;
import bilibili.cache.BilibiliVideoTargetCache;
import bilibili.format.BilibiliVideoInfoFormatter;
import bilibili.parser.BilibiliVideoExtractor;
import bilibili.parser.BilibiliVideoTarget;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * @Title: BilibiliVideoParser
 * @Author yuier
 * @Package PACKAGE_NAME
 * @Date 2026/11/16 22:44
 * @description: B站视频解析
 */
public class BilibiliVideoParser implements YuniPlugin {

    /** 条件判断与处理器之间的短期结果缓存。 */
    private final BilibiliVideoTargetCache targetCache = new BilibiliVideoTargetCache();

    /** B 站第三方接口客户端。 */
    private BilibiliApiClient apiClient;

    /** 插件日志。 */
    private Logger logger;

    @Override
    public void apply(YuniContext ctx) {
        this.logger = ctx.getLogger();
        this.apiClient = new BilibiliApiClient(ctx.getHttp(), logger);
        ctx.onMessage(this::messageContainsBv, this::parseBilibiliVideo);
    }

    /**
     * 消息中是否包含可识别的 B 站视频入口。
     *
     * <p>覆盖文本消息中的 BV 号、B 站视频链接、b23.tv 短链接，以及 QQ 分享卡片
     * {@code meta.detail_1.qqdocurl} 中的短链接。提取结果会写入内存缓存，供紧随
     * 其后的 {@link #parseBilibiliVideo(YuniMessageEvent, Reply)} 使用。</p>
     *
     * @param event 消息事件
     * @return 包含可识别的 B 站视频入口时返回 {@code true}
     */
    private boolean messageContainsBv(YuniMessageEvent event) {
        Optional<BilibiliVideoTarget> target = BilibiliVideoExtractor.extract(event);
        target.ifPresent(value -> targetCache.put(event, value));
        return target.isPresent();
    }

    /**
     * 查询并回复视频信息。
     *
     * @param event 消息事件
     * @param reply 回复构建器
     */
    private void parseBilibiliVideo(YuniMessageEvent event, Reply reply) {
        BilibiliVideoTarget target = targetCache.take(event)
                .or(() -> BilibiliVideoExtractor.extract(event))
                .orElse(null);
        if (target == null) {
            return;
        }

        try {
            BilibiliVideoInfo info = apiClient.fetchVideoInfo(target);
            String text = BilibiliVideoInfoFormatter.format(info);
            if (info.hasCover()) {
                reply.quote().image(info.cover()).text(text).send();
            } else {
                reply.quote().text(text).send();
            }
        } catch (BilibiliApiException e) {
            logger.warn("B站视频解析失败: target={}, reason={}", target, e.getMessage());
            reply.quote().text("B站视频解析失败：" + e.getMessage()).send();
        } catch (RuntimeException e) {
            logger.error("B站视频解析异常: target={}", target, e);
            reply.quote().text("B站视频解析失败，请稍后重试。").send();
        }
    }
}
