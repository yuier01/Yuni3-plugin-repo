import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * 疯狂星期四的补发器——周四当天重启后继续完成剩余投放。
 *
 * <h2>为什么它现在是一个子插件而不是独立插件</h2>
 * <p>本类的职责是「周四重启后把还没发的时间点补上」。这在语义上是
 * {@link CrazyThursday} 的一个组成部分，而不是一项独立能力——
 * 脱离疯狂星期四它没有任何意义。</p>
 *
 * <p>旧实现把它做成<b>独立插件</b>，于是产生了两个后果（设计文档 §1.5）：</p>
 * <ol>
 *   <li>必须在两个插件的 {@code enable}/{@code disable} 回调里互相启停对方，
 *       形成双向耦合，且 {@code CrazyThursday.disable} 里误写成操作自己；</li>
 *   <li>两者通过 Redis 键交换数据，但契约没有任何类型表达。</li>
 * </ol>
 *
 * <p>迁移后它由父插件用 {@code ctx.plugin(new StartAtThursday())} 挂载：
 * 生命周期单向（父挂子、父卸载子亦卸载），启停回调整体消失，
 * 共享的数据契约成为真实类型 {@link CrazyThursday.ScheduleCache}。</p>
 *
 * <h2>不变的语义</h2>
 * <ul>
 *   <li>只在周四执行，非周四直接返回；</li>
 *   <li>缓存不存在（说明当天没生成过时间点）时直接返回；</li>
 *   <li>只处理「尚未过期」的时间点，已过期的跳过。</li>
 * </ul>
 *
 * <h2>顺带修掉的一处 NPE 风险</h2>
 * <p>旧实现读缓存后直接 {@code assert redisCache != null}——断言在默认 JVM 配置下
 * 是关闭的，因此缓存反序列化失败时会继续执行并在下一行抛 NPE。
 * 迁移后改为显式的空值判断 + 日志。</p>
 */
@Slf4j
public class StartAtThursday implements YuniPlugin {

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("疯狂星期四-重启适配")
                .version("0.0.1")
                .description("适配在周四重启时的情况")
                .author("Yuier me@yuier.com")
                .tip("周四当天重启后，继续完成当天剩余的投放时间点")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // 对应旧 ImmediatePlugin：注册后立即执行一次。
        // 作为子插件挂载，因此父插件启用时它也跟着生效。
        ctx.runNow(() -> recover(ctx));
    }

    /**
     * 补发当天尚未到期的投放。
     *
     * @param ctx 插件上下文
     */
    private void recover(YuniContext ctx) {
        if (!LocalDate.now().getDayOfWeek().equals(DayOfWeek.THURSDAY)) {
            return;
        }

        CrazyThursday.ScheduleCache cache = ctx.cache()
                .get(CrazyThursday.ScheduleCache.REDIS_KEY, CrazyThursday.ScheduleCache.class)
                .orElse(null);
        if (cache == null) {
            // 当天没有生成过时间点（例如 00:00 的定时任务未执行）：无可补发
            log.info("[疯四补发] 未找到当天的时间点缓存，跳过补发");
            return;
        }

        List<Long> waits = CrazyThursday.calculateWaits(
                cache.getTimePoints(), System.currentTimeMillis());
        if (waits.isEmpty()) {
            log.info("[疯四补发] 今天的时间点均已过期，无需补发");
            return;
        }

        log.info("[疯四补发] 周四重启，继续完成 {} 个时间点的投放", waits.size());
        for (Long wait : waits) {
            if (ctx.disposed()) {
                log.info("[疯四补发] 插件已卸载，中止补发");
                return;
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("[疯四补发] 等待被中断，中止补发");
                    return;
                }
            }
            // 复用父插件的投放逻辑：时间点、概率、群粒度过滤、文案选择全部一致
            CrazyThursday.broadcastOnce(ctx);
        }
    }
}
