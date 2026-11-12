package maimai;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * 麦麦适配器——启动 WebSocket 服务器、注册 {@link MaiMaiApi} 服务，并把 QQ 消息转发给 maimai。
 *
 * <h2>架构</h2>
 * <pre>
 *   NapCat ←→ Yuni OneBot适配器 ←→ 本插件 (WS Server) ←→ maimai
 * </pre>
 *
 * <h2>为什么是一个插件类</h2>
 * <p>旧实现拆成 {@code MaiMaiAdapterBooter}（启动服务器、提供服务）与
 * {@code OneBotMessageForward}（消费服务、转发消息）两个插件。拆分的原因是旧系统
 * 一个插件只能承担一种「类型」；新系统里一个 {@code apply} 可以同时启动资源、
 * 注册服务与处理器，因此合并为一个入口。这样也消除了「转发器先于连接器加载时
 * 停在 PENDING」这一整类顺序问题——两者现在天然同生共死。</p>
 *
 * <h2>设计要点</h2>
 * <ul>
 *   <li>WS 服务器是框架不认识的资源，用 {@code ctx.effect(...)} 声明清理；</li>
 *   <li>{@link MaiMaiApi} 仍注册为插件服务，供其它插件按类型消费；</li>
 *   <li>{@code server.start()} 失败会抛异常，插件进入 {@code FAILED}，管理端可见——
 *       取代旧实现「异步启动、异常只进日志、插件仍显示 ACTIVE」。</li>
 * </ul>
 */
@Slf4j
public class MaiMai implements YuniPlugin {

    /** 配置文件名（相对插件资源目录）。 */
    private static final String CONFIG_FILE = "maimai_config.json";


    @Override
    public void apply(YuniContext ctx) {
        MaiMaiConfig config = ctx.getConfigLoader()
                .loadOrDefault(CONFIG_FILE, MaiMaiConfig.class, new MaiMaiConfig());
        int port = config.getPort();
        log.info("[MaiMai] 启动服务器，端口: {}", port);

        MaiMaiWsServer server = new MaiMaiWsServer(port, ctx.getBot(), ctx.findService(JsonCodec.class));
        server.setHeartbeatIntervalMs(config.getHeartbeatIntervalMs());

        // 声明框架不认识的资源：WS 服务器 + 心跳线程池。
        // 撤销时先停止心跳与连接池，再关闭监听——顺序由框架按注册逆序保证
        ctx.effect(() -> server::shutdown);

        // 注册为插件服务：其它插件可按类型消费，卸载时自动注销
        ctx.registerService(MaiMaiApi.class, server);

        server.start();

        /*
         * QQ 消息 → maimai 转发。
         *
         * 匹配所有群聊和私聊的非命令消息；命令命中后会短路模式匹配，因此这里
         * 不需要判断「这是不是命令」，maimai 专注于群聊自然对话。
         *
         * 用 rawJson 透传是需求决定的：改用结构化字段重建 JSON 会丢失字段顺序、
         * 类型与 NapCat 私有扩展字段，破坏下游解析。
         */
        ctx.onMessage(ev -> true, (ev, reply) -> {
            String rawJson = ev.getRawJson();
            if (rawJson == null || rawJson.isBlank()) {
                // 某些适配器不上报原始 JSON；此时无法透传
                log.debug("[MaiMai] 事件缺少原始 JSON，跳过转发: {}", ev.toLogString());
                return;
            }
            if (server.clientCount() == 0) {
                // 没有 maimai 客户端连接：这是正常状态，用 debug 级别避免刷日志
                log.debug("[MaiMai] 无已连接的 maimai 客户端，跳过转发");
                return;
            }
            server.broadcastEvent(rawJson);
        });
    }
}
