package maimai;

import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * 麦麦适配器启动器——启动 WebSocket 服务器并把 {@link MaiMaiApi} 注册为插件服务。
 *
 * <h2>架构</h2>
 * <pre>
 *   NapCat ←→ Yuni OneBot适配器 ←→ 本插件 (WS Server) ←→ maimai
 * </pre>
 *
 * <h2>迁移说明（P5）：本类验证 §7.3「插件提供服务」</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends ImmediatePlugin} + {@code getAction()} 返回动作，
 *       由注册器异步执行一次</td>
 *       <td>在 {@code apply(ctx)} 里直接启动——「即时插件」这一类型消失</td></tr>
 *   <tr><td>用 {@code DefaultSingletonBeanRegistry} 把 WS 服务器塞进 Spring 容器，
 *       名字 {@code "maiMaiWsServer"}</td>
 *       <td>{@code ctx.registerService(MaiMaiApi.class, server)}——插件服务命名空间，按类型解析</td></tr>
 *   <tr><td>{@code destroy()} 里手动 {@code destroySingleton} + {@code shutdown()}</td>
 *       <td>{@code ctx.effect(() -> server::shutdown)}——卸载时框架逆序执行</td></tr>
 *   <tr><td>{@code PluginUtils.loadJsonConfigFromPlugin(..., getClass())}</td>
 *       <td>{@code ctx.getConfigLoader().loadOrDefault(...)}——不再需要传自己的 Class</td></tr>
 * </table>
 *
 * <h2>顺带修掉的两处缺陷</h2>
 * <ol>
 *   <li><b>配置缺失时的兜底没有日志级别区分</b>：旧 {@code loadConfig} 捕获
 *       {@code Exception} 后 {@code log.warn} 并返回默认配置——但「文件不存在」
 *       （可接受，用默认端口）与「文件存在但 JSON 语法错误」（需要管理员处理）
 *       是两种性质的问题。新实现用 {@code loadOrDefault} 区分：文件不可读时
 *       由 {@code ConfigLoader} 统一处理，日志中能看出是哪一种。</li>
 *   <li><b>启动失败没有传递出去</b>：旧 {@code getAction()} 体里
 *       {@code wsServer.start()} 失败（端口被占用）会抛异常，但因为运行在
 *       {@code CompletableFuture.runAsync} 里，异常只进日志，插件仍显示为
 *       ACTIVE——管理端看不出它其实没在工作。新实现里 {@code start()} 直接执行，
 *       抛出的异常会让插件进入 {@code FAILED} 状态，管理端可见
 *       （设计文档 §9.3 的「失败必须显式」）。</li>
 * </ol>
 */
@Slf4j
public class MaiMaiAdapterBooter implements YuniPlugin {

    /** 配置文件名（相对插件资源目录）。 */
    private static final String CONFIG_FILE = "maimai_config.json";

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("maimai-连接器")
                .version("0.0.1")
                .description("启动 WebSocket 服务器，接受 MaiBot-Napcat-Adapter 连接，实现 OneBot 协议核心动作")
                .author("Yuier me@yuier.com")
                .tip("NapCat ←→ Yuni ←→ maimai 双向中转")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        MaiMaiConfig config = ctx.getConfigLoader().loadOrDefault(CONFIG_FILE, MaiMaiConfig.class, new MaiMaiConfig());
        int port = config.getPort();
        log.info("[MaiMai] 启动服务器，端口: {}", port);

        MaiMaiWsServer server = new MaiMaiWsServer(port, ctx.getBot(), ctx.findService(JsonCodec.class));
        server.setHeartbeatIntervalMs(config.getHeartbeatIntervalMs());

        // 声明框架不认识的资源：WS 服务器 + 心跳线程池。
        // 撤销时先停止心跳与连接池，再关闭监听——顺序由框架按注册逆序保证
        ctx.effect(() -> server::shutdown);

        // 注册为插件服务：取代旧实现往 Spring 容器里塞 Bean 的做法。
        // 消费方 OneBotMessageForward 通过 declareRequiredServices() 声明依赖，卸载时自动注销
        ctx.registerService(MaiMaiApi.class, server);

        server.start();
    }
}
