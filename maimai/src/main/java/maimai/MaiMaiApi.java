package maimai;

import com.yuier.yuni.core.bot.YuniBot;

import java.util.concurrent.ScheduledExecutorService;

/**
 * maimai 适配器服务契约。
 *
 * <h2>为什么需要这个接口（P5 迁移要点）</h2>
 * <p>旧实现在 {@code MaiMaiAdapterBooter} 里用 Spring 内部 API 把
 * {@code MaiMaiWsServer} 注册成一个 Spring Bean，{@code OneBotMessageForward}
 * 再用 {@code PluginUtils.getBean("maiMaiWsServer")} 按<b>字符串名字</b>取回来：</p>
 * <pre>{@code
 * // MaiMaiAdapterBooter.java:41-45 旧
 * ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) SpringContextUtil.getApplicationContext();
 * DefaultSingletonBeanRegistry registry = (DefaultSingletonBeanRegistry) ctx.getBeanFactory();
 * registry.registerSingleton("maiMaiWsServer", wsServer);
 *
 * // OneBotMessageForward.java:26 旧
 * Object bean = PluginUtils.getBean("maiMaiWsServer");
 * if (bean instanceof MaiMaiWsServer server) { ... }
 * }</pre>
 *
 * <p>这条路有四个问题：</p>
 * <ol>
 *   <li><b>用 Spring 内部类</b>（{@code DefaultSingletonBeanRegistry}），
 *       是框架实现细节，跨版本可能失效；</li>
 *   <li><b>按字符串名取 Bean</b>，编译期无任何检查——Bean 没注册时
 *       得到 {@code null}，而 {@code instanceof} 判断让它<b>静默什么都不做</b>，
 *       表现为「消息转发没反应」却没有任何报错；</li>
 *   <li><b>插件污染 Spring 容器</b>：插件的对象混进框架的 Bean 空间，
 *       卸载时必须手动 {@code destroySingleton}，漏了就是泄漏；</li>
 *   <li><b>两个插件通过全局容器耦合</b>，依赖关系不可见——看不出
 *       {@code OneBotMessageForward} 依赖 {@code MaiMaiAdapterBooter}。</li>
 * </ol>
 *
 * <p>新实现用插件系统的服务机制：{@code MaiMaiAdapterBooter} 在
 * {@code apply} 里 {@code ctx.provide(MaiMaiApi.class, server)}，
 * {@code OneBotMessageForward} 通过 {@code ctx.serviceIfPresent(MaiMaiApi.class)}
 * 按<b>类型</b>解析。卸载时随 PluginEffect 自动注销，无需手动清理。</p>
 *
 * <p>本接口只暴露 {@code OneBotMessageForward} 真正需要的能力。
 * 旧实现把整个 {@code MaiMaiWsServer} 暴露出去，于是消费方可以调用它的任何
 * public 方法；收窄到接口后，「消息转发插件只负责转发事件」这条约束由类型系统保证。</p>
 */
public interface MaiMaiApi {

    /**
     * 向所有已连接的 maimai 客户端推送一条 OneBot 事件 JSON。
     *
     * <p>参数是<b>原始 JSON 字符串</b>而不是结构化对象——本方法的语义是「透传」，
     * 即把 Yuni 收到的 OneBot 事件原封不动转给 maimai。若在此处反序列化再
     * 序列化，任何字段顺序变化或类型漂移都会改变转发内容。</p>
     *
     * @param rawJson 原始 OneBot 事件 JSON
     */
    void broadcastEvent(String rawJson);

    /**
     * 当前连接的 maimai 客户端数量。
     *
     * <p>供诊断与日志使用；消费方不应据此做业务判定（数量随时可变）。</p>
     *
     * @return 已连接的客户端数
     */
    int clientCount();
}
