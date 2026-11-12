package maimai;

import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * QQ 消息 → maimai 转发器。
 *
 * <p>匹配所有群聊和私聊的<b>非命令</b>消息，把原始 OneBot JSON 推送给已连接的
 * MaiBot-Napcat-Adapter 客户端。</p>
 *
 * <p>不转发命令消息是自动的：命令命中后会短路模式匹配（设计文档 §2.2 不变量 1），
 * 因此本插件的处理器根本不会被调用——不需要在这里判断「这是不是命令」。
 * 这样 maimai 专注于群聊自然对话，不干扰 Yuni 的命令处理。</p>
 *
 * <h2>迁移说明（P5）：本类验证 §7.2「插件间依赖通过 inject 表达」</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends PatternPlugin} + 匿名 {@code PatternDetector} 子类
 *       覆写 {@code match()} 恒返回 {@code true}</td>
 *       <td>{@code ctx.onMessage(ev -> true, ...)}——「无条件」写成显式的谓词</td></tr>
 *   <tr><td>{@code PluginUtils.getBean("maiMaiWsServer")} 按字符串名取 Spring Bean，
 *       取不到时 {@code instanceof} 判断失败并<b>静默什么都不做</b></td>
 *       <td>{@code declareRequiredServices()} 声明依赖 {@link MaiMaiApi}，由框架在依赖就绪后才加载；
 *       服务缺失时插件停在 PENDING 并在启动结束时报错</td></tr>
 *   <tr><td>{@code event.getRawJson()}（core 事件对象）</td>
 *       <td>{@code ev.getRawJson()}（插件侧事件视图的逃生舱，语义相同）</td></tr>
 * </table>
 *
 * <h2>为什么这里用 {@code rawJson()}，是不是违背了「插件不该依赖 rawJson」</h2>
 * <p>{@code Event.getRawJson()} 的文档说它「仅用于诊断、日志、以及管理端推送」，
 * 并建议插件业务逻辑不要依赖它。本插件正是那个例外，而且是有理由的例外：</p>
 * <ul>
 *   <li>它的职责就是<b>透传</b>——把 Yuni 收到的 OneBot 事件原封不动交给另一个
 *       OneBot 客户端。任务本身要求的就是「协议原文」；</li>
 *   <li>若改用结构化字段重建 JSON，任何字段顺序、类型、以及 NapCat 私有扩展字段
 *       都会在重建过程中丢失或漂移，这会破坏下游解析。</li>
 * </ul>
 * <p>反例对照：旧 {@code PokeNotice} 依赖 rawJson 是因为<b>事件模型缺字段</b>，
 * 那是设计缺陷（已修）。本插件的依赖是<b>需求本身决定</b>的。</p>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <p>旧实现在拿不到 WS 服务器 Bean 时静默返回——调试时表现为「消息转发没反应」
 * 而没有任何日志。新实现由框架保证加载顺序，并在这里补一条 debug 日志用于
 * 「服务存在但无客户端连接」这一正常场景的排查。</p>
 */
@Slf4j
public class OneBotMessageForward implements YuniPlugin {

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("maimai-消息转发")
                .version("0.0.1")
                .description("将 QQ 群聊/私聊消息（非命令）转发给 maimai")
                .author("Yuier me@yuier.com")
                .tip("不转发命令消息，maimai 专注于群聊自然对话")
                .defaultEnable(true)
                .build();
    }

    /**
     * 声明依赖 maimai 适配器服务。
     *
     * <p>框架在调用 {@code apply} 之前会检查该服务是否已注册；未就绪时本插件停在
     * {@code PENDING} 并<b>不</b>调用 {@code apply}，加载流程结束时统一报出
     * 「谁因为缺什么而没加载」。这取代了旧实现「取不到 Bean 就静默不干活」。</p>
     *
     * @return 依赖的服务类型
     */
    @Override
    public List<Class<?>> declareRequiredServices() {
        return List.of(MaiMaiApi.class);
    }

    @Override
    public void apply(YuniContext ctx) {
        MaiMaiApi api = ctx.findService(MaiMaiApi.class);

        // 「无条件」显式写出：取代旧实现里那个覆写 match() 恒返回 true 的匿名子类
        ctx.onMessage(ev -> true, (ev, reply) -> {
            String rawJson = ev.getRawJson();
            if (rawJson == null || rawJson.isBlank()) {
                // 某些适配器不上报原始 JSON；此时无法透传
                log.debug("[MaiMai] 事件缺少原始 JSON，跳过转发: {}", ev.toLogString());
                return;
            }
            if (api.clientCount() == 0) {
                // 没有 maimai 客户端连接：这是正常状态，用 debug 级别避免刷日志
                log.debug("[MaiMai] 无已连接的 maimai 客户端，跳过转发");
                return;
            }
            api.broadcastEvent(rawJson);
        });
    }
}
