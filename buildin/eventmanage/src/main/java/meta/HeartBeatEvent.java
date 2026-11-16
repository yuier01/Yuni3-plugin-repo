package meta;

import com.yuier.yuni.core.event.meta.YuniHeartbeatEvent;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * OneBot 心跳事件处理——收到心跳时打印一行日志。
 *
 * <h2>为什么把日志级别放在 debug</h2>
 * <p>心跳默认 30 秒一次，长期跑下来 info 级别会产生大量噪音。
 * 需要观测心跳时把该类的日志级别调到 debug 即可。</p>
 */
@Slf4j
public class HeartBeatEvent implements YuniPlugin {


    @Override
    public void apply(YuniContext ctx) {
        // 只需声明元事件类型；「是不是心跳」的判定在注册时完成
        ctx.onMeta("heartbeat", (ev, reply) -> {
            if (!(ev instanceof YuniHeartbeatEvent heartbeat)) {
                return;
            }
            log.debug("[心跳] online={}, good={}, interval={}ms",
                    heartbeat.isStatusOnline(),
                    heartbeat.isStatusGood(),
                    heartbeat.getHeartbeatInterval().orElse(-1L));
        });
    }
}
