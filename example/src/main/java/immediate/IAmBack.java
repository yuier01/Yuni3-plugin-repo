package immediate;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 即时插件示例——启动后向配置中列出的群各发一条「我是后背」。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本类是「主动插件」两分法之二（即时型）的迁移样板。对照关系：</p>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends ImmediatePlugin}（一种<b>独立插件类型</b>）</td>
 *       <td>{@code implements YuniPlugin}——类型消失</td></tr>
 *   <tr><td>{@code getAction()} 返回动作，由 {@code PluginRegisterProcessor}
 *       在注册后 {@code CompletableFuture.runAsync} 执行一次</td>
 *       <td>{@code ctx.runNow(...)}——主动能力的表达就是这一行调用</td></tr>
 *   <tr><td>{@code PluginUtils.loadJsonConfigFromPlugin(name, C.class, getClass())}</td>
 *       <td>{@code ctx.getConfigLoader().load(name, C.class)}——不再需要传自己的 Class</td></tr>
 * </table>
 *
 * <h2>关于「即时插件」这一类型被取消的理由</h2>
 * <p>旧系统为它单独定义了一个类型与一条注册路径，但全部语义只是
 * 「注册完成后执行一次」——即下面 {@code apply} 里的一行 {@code ctx.runNow}。
 * 把它做成一个类型，等于让「注册器的实现细节」泄漏成「用户必须做的类型选择」。
 * 迁移后本插件与 {@code OhaYo}（定时型）的区别，仅在于调用了
 * {@code runNow} 还是 {@code schedule}——不再有类型层面的差异。</p>
 *
 * <h2>顺带修掉的一处低效</h2>
 * <p>旧实现在内层循环里对每个目标群遍历一次完整群列表（O(n×m)）。
 * 迁移后先把群列表收成 {@link HashSet}，再做 O(1) 查找。</p>
 */
@Slf4j
public class IAmBack implements YuniPlugin {

    /** 播报内容。 */
    private static final String MESSAGE = "我是后背";

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("即时插件示例")
                .version("0.0.1")
                .description("即时任务插件实例")
                .author("Yuier me@yuier.com")
                .tip("插件中定义的动作会且仅会在注册后立即执行一次，启动后会向特定群聊发送一条消息“我是后背”")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // 对应旧 ImmediatePlugin：注册后异步执行一次
        ctx.runNow(() -> broadcast(ctx));
    }

    /**
     * 向配置中列出的、当前确实存在的群发送播报。
     *
     * @param ctx 插件上下文
     */
    private void broadcast(YuniContext ctx) {
        TargetGroupConfig config = ctx.getConfigLoader().load("i_m_back.json", TargetGroupConfig.class);
        List<Long> targets = config.getTargetGroups();
        if (targets == null || targets.isEmpty()) {
            log.info("未配置目标群，跳过播报");
            return;
        }

        // 先把现有群收成集合：旧实现对每个目标群遍历一遍完整群列表，是 O(n×m)
        Set<Long> existing = new HashSet<>();
        for (BotGroupInfo group : ctx.getBot().getGroupList().orElse(List.of())) {
            existing.add(group.getGroupId());
        }

        for (Long groupId : targets) {
            if (groupId == null || !existing.contains(groupId)) {
                // 目标群不存在（已被移除或配置有误）：跳过而不是发出去失败
                log.debug("目标群 {} 不存在或机器人未加入，跳过", groupId);
                continue;
            }
            // MessageSender 内部已捕获发送异常并返回失败结果，无需 try/catch
            ctx.getSender().group(groupId).text(MESSAGE).send();
        }
    }

    /**
     * 供 {@code ConfigLoader} 反序列化的配置模型。
     *
     * <p>保持与旧实现一致的字段名（{@code target_groups}），
     * 因此 {@code i_m_back.json} 资源文件<b>无需改动</b>。</p>
     */
    @Data
    public static class TargetGroupConfig {

        /** 目标群号列表（JSON 键 {@code target_groups}，由全局 SNAKE_CASE 策略映射）。 */
        private List<Long> targetGroups;

        /* 读写器由 @Data 生成。 */
    }
}
