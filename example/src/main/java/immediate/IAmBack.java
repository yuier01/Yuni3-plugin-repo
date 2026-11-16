package immediate;

import com.yuier.yuni.core.bot.BotGroupInfo;
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
 */
@Slf4j
public class IAmBack implements YuniPlugin {

    /** 播报内容。 */
    private static final String MESSAGE = "我是后背";


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
 */
    @Data
    public static class TargetGroupConfig {

        /** 目标群号列表（JSON 键 {@code target_groups}，由全局 SNAKE_CASE 策略映射）。 */
        private List<Long> targetGroups;

        /* 读写器由 @Data 生成。 */
    }
}
