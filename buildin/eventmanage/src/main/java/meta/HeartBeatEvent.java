package meta;

import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * OneBot 心跳事件处理——收到心跳时打印一行日志。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本插件很小，但它是「元事件字段丢失」问题的验证点。</p>
 *
 * <p>旧实现：</p>
 * <pre>{@code
 * // HeartBeatEvent.java:21-27 旧
 * return new DefaultYuniMetaDetector(event -> {
 *     if ("heartbeat".equals(event.getMetaEventType())) {
 *         return PluginUtils.deserialize(event.getRawJson(), YuniHeartbeatEvent.class);
 *     }
 *     return null;
 * });
 * }</pre>
 * <p>它同样需要自己反序列化 {@code rawJson}——因为
 * {@code EventTranslator.buildYuniMetaEvent} 只搬运了 {@code metaEventType}
 * 与 {@code subType}，<b>把 {@code status} 与 {@code interval} 丢掉了</b>。
 * 于是这个「心跳处理器」实际上拿不到心跳间隔。</p>
 *
 * <p>新系统的适配器（{@code CoreLifecycleEvents.CoreMetaEvent}）保留了这两个字段，
 * 因此本插件现在能真正输出心跳间隔——这是原先想要但拿不到的信息：</p>
 * <pre>{@code
 * ctx.onMeta("heartbeat", (ev, reply) ->
 *         log.debug("心跳: online={}, interval={}ms",
 *                   ev.isStatusOnline(), ev.getHeartbeatInterval().orElse(0L)));
 * }</pre>
 *
 * <h2>为什么把日志级别放在 debug</h2>
 * <p>心跳默认 30 秒一次，长期跑下来 info 级别会产生大量噪音。
 * 旧实现用的是 {@code log.debug(heartbeatEvent.toLogString())}，
 * 这里保持一致。需要观测心跳时把该类的日志级别调到 debug 即可。</p>
 */
@Slf4j
public class HeartBeatEvent implements YuniPlugin {


    @Override
    public void apply(YuniContext ctx) {
        // 只需声明元事件类型；「是不是心跳」的判定在注册时完成
        ctx.onMeta("heartbeat", (ev, reply) ->
                log.debug("[心跳] online={}, good={}, interval={}ms",
                        ev.isStatusOnline(),
                        ev.isStatusGood(),
                        // 字段由适配器保留——旧实现在此只能拿到 null
                        ev.getHeartbeatInterval().orElse(-1L)));
    }
}
