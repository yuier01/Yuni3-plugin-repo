import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.service.YuniHttp;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 色图——调用 Lolicon API 取一张图并发到群聊。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin} + 两段式</td>
 *       <td>{@code Commands.on(ROOT, handler)}</td></tr>
 *   <tr><td>{@code PluginUtils.simplePost(url, params, C.class)}</td>
 *       <td>{@code ctx.getHttp().post(url).jsonBody(params).execute(C.class)}</td></tr>
 *   <tr><td>失败时 {@code getChatSession().response(...)} 逐个分支返回</td>
 *       <td>仍是逐个分支返回，但改用 {@code reply}</td></tr>
 * </table>
 *
 * <h2>为什么保留「逐个校验再返回」的写法</h2>
 * <p>外部接口的响应有多种失败形态（业务错误、空列表、图片地址缺失），
 * 每种都应给用户不同提示。新设计没有把它们合并成一个统一错误——
 * 那会丢失「到底是哪一步失败」的信息，反而降低可诊断性。
 * 但异常处理简化了：{@code ctx.getHttp()} 会把网络失败与非 2xx 统一抛
 * {@link YuniHttp.HttpException}，因此不需要再区分「请求没发出去」与「响应不是 2xx」。</p>
 */
@Slf4j
public class Setu implements YuniPlugin {

    /** Lolicon API 地址。 */
    private static final String LOLICON_API_V2 = "https://api.lolicon.app/setu/v2";

    /** 命令树：无参数无子命令。 */
    private static final CommandNode ROOT = CommandNode.builder("色图")
            .description("群友喜闻乐见的模块")
            .build();


    @Override
    public void apply(YuniContext ctx) {
        ctx.command(Commands.on(ROOT, (event, reply) -> {
            LoliconApiResponse response;
            try {
                response = ctx.getHttp()
                        .post(LOLICON_API_V2)
                        // r18=0：QQ 群还是要稳一手
                        // size=regular：比最大的 original 小一号
                        .jsonBody(Map.of("r18", 0, "size", "regular"))
                        .execute(LoliconApiResponse.class);
            } catch (YuniHttp.HttpException e) {
                log.warn("[色图] Lolicon API 调用失败: {}", e.getMessage());
                reply.text("Lolicon Api 调用失败，请稍后再试。").send();
                return;
            }

            if (response == null) {
                reply.text("Lolicon Api 返回了空响应。").send();
                return;
            }
            if (response.getError() != null && !response.getError().isEmpty()) {
                reply.text("Lolicon Api 调用失败，报错信息: " + response.getError()).send();
                return;
            }
            List<SetuItem> data = response.getData();
            if (data == null || data.isEmpty()) {
                reply.text("Lolicon Api 返回了空数据。").send();
                return;
            }

            SetuItem item = data.get(0);
            String imgUrl = item.getUrls() == null ? null : item.getUrls().getRegular();
            if (imgUrl == null || imgUrl.isEmpty()) {
                reply.text("Lolicon Api 获取图片地址失败。").send();
                return;
            }

            reply.quote()
                    .text("pid: " + item.getPid() + "\n"
                            + "title: " + item.getTitle() + "\n"
                            + "author: " + item.getAuthor() + "\n")
                    .image(imgUrl)
                    .send();
        }));
    }

    /**
     * Lolicon API 的响应模型。
     */
    @Data
    public static class LoliconApiResponse {

        /** 报错信息；无错误时为空字符串。 */
        private String error;

        /** 色图列表。 */
        private List<SetuItem> data;
    }

    /**
     * 单张色图的模型。
     *
     * <p>只保留本插件实际使用的字段（pid / title / author / urls）。
     * 旧实体还声明了 tags、width、height 等十余个字段但从未读取——
     * 那些字段随接口变化还会带来反序列化风险，因此不迁移。</p>
     */
    @Data
    public static class SetuItem {

        /** pixiv 作品 id。 */
        private long pid;

        /** 标题。 */
        private String title;

        /** 作者。 */
        private String author;

        /** 各尺寸图片地址。 */
        private SetuUrls urls;
    }

    /**
     * 图片地址集合。
     */
    @Data
    public static class SetuUrls {

        /** 常规尺寸地址。 */
        private String regular;
    }
}
