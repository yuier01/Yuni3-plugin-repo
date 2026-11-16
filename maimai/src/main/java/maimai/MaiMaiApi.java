package maimai;

import com.yuier.yuni.core.bot.YuniBot;

import java.util.concurrent.ScheduledExecutorService;

/**
 * maimai 适配器服务契约。
 *
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
