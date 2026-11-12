package schedule;

import com.yuier.yuni.core.util.CronExpressionBuilder;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 定时插件示例——每天早上向 bot 主人问好。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本类是「主动插件」两分法之一（定时型）的迁移样板。对照关系：</p>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends ScheduledPlugin}</td><td>{@code implements YuniPlugin}</td></tr>
 *   <tr><td>{@code cronExpression()} 单独一个方法</td>
 *       <td>cron 表达式写在 {@code ctx.schedule(...)} 的第一个参数里——
 *           调度声明与任务体在同一个表达式</td></tr>
 *   <tr><td>{@code getAction()} 返回 {@code Action}</td>
 *       <td>直接传 lambda，不需要 {@code Action} 这层包装</td></tr>
 *   <tr><td>{@code PluginUtils.getBotMasterId()}</td><td>{@code ctx.requireService(Bot.class).getMasterId()}</td></tr>
 *   <tr><td>{@code SpringContextUtil.getBean(YuniBot.class)}</td><td>{@code ctx.getSender()} 或 {@code ctx.getBot()}</td></tr>
 *   <tr><td>{@code enable()} / {@code disable()} 空实现</td>
 *       <td><b>方法消失</b>——启停由 {@code ScopeFilter} 表达，不涉及插件代码</td></tr>
 * </table>
 *
 * <p>迁移前代码末尾有一条作者留下的 WARNING：</p>
 * <blockquote>
 *   「由于作者没琢磨明白怎么控制主动插件的行为，因此无法对主动插件实行类似被动插件的
 *   以群组为粒度的开启/关闭的管理」
 * </blockquote>
 * <p>新系统中这个限制不再存在：主动插件同样受 {@code ScopeFilter} 约束，
 * 且可用 {@link com.yuier.yuni.plugin.api.ScopeQuery}（{@code ctx.getScope()}）
 * 主动查询「本插件在某群是否生效」。本插件是私聊播报，因此无需查询；
 * 需要按群广播的主动插件（如疯狂星期四）请参考
 * {@code crazy-thursday} 模块的写法。</p>
 */
public class OhaYo implements YuniPlugin {

    /** 问候语。 */
    private static final String GREETING = "偶哈哟，欧尼酱！";

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("定时插件示例")
                .version("0.0.1")
                .description("定时任务插件示例")
                .author("Yuier me@yuier.com")
                .tip("每天早上会向 bot master 问好。")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // 每天 10:00 向 bot 主人发私聊问候
        ctx.schedule(
                CronExpressionBuilder.create().dailyAt(10, 0).build(),
                () -> ctx.getSender()
                        .privateChat(ctx.requireService(com.yuier.yuni.core.model.bot.Bot.class).getMasterId())
                        .text(GREETING)
                        .send());
    }
}
