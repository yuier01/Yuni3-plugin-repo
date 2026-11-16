package steamstatus;

import com.yuier.yuni.core.util.CronExpressionBuilder;
import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;
import steamstatus.db.UserSteamId;
import steamstatus.model.SinglePlayerSummary;
import steamstatus.utils.SteamStatusService;

import java.util.ArrayList;
import java.util.List;

/**
 * Steam 相关功能——状态监控与 id 绑定/解绑。
 *
 * <h2>群粒度启停</h2>
 * <p>监控只向 {@link SteamStatusService#enabledGroupsOfUser(long)} 返回的群播报——
 * 这些群同时满足「用户在该群」「bot 在该群」「本插件在该群生效」。</p>
 */
@Slf4j
public class Steam implements YuniPlugin {

    /** 轮询周期：每分钟一次。 */
    private static final String CRON_EVERY_MINUTE = CronExpressionBuilder.create().everyMinutes(1).build();

    /** 命令名。 */
    private static final String CMD_STEAM = "steam";

    /** 子命令：绑定。 */
    private static final String CHILD_BIND = "绑定id";

    /** 子命令：解绑。 */
    private static final String CHILD_UNBIND = "解绑id";

    /** 参数名：steam id。 */
    private static final String ARG_STEAM_ID = "ID参数";


    @Override
    public void apply(YuniContext ctx) {
        SteamStatusService steam = new SteamStatusService(ctx);
        steam.initDatabase();
        // 把数据库里的绑定关系灌进缓存，作为差分的初始状态
        steam.syncDbToCache();

        // 任务随插件卸载自动取消，不需要手动 cancel
        ctx.schedule(CRON_EVERY_MINUTE, () -> poll(steam));
        ctx.command(buildTree(steam));
    }

    // ==================== 状态监控 ====================

    /**
     * 执行一轮监控。
     *
     * @param steam Steam 状态服务
     */
    private void poll(SteamStatusService steam) {
        long total = steam.countBindings();
        if (total == 0) {
            return;
        }
        int pageCount = (int) ((total + SteamStatusService.BATCH_SIZE - 1) / SteamStatusService.BATCH_SIZE);
        for (int page = 0; page < pageCount; page++) {
            List<UserSteamId> bindings = steam.findBindingsByPage(page, SteamStatusService.BATCH_SIZE);
            if (bindings.isEmpty()) {
                continue;
            }
            List<String> steamIds = new ArrayList<>(bindings.size());
            for (UserSteamId binding : bindings) {
                String id = binding.getSteamId();
                if (id != null && !id.isEmpty()) {
                    steamIds.add(id);
                }
            }
            // 查询失败时返回空列表，本轮跳过——不会中断后续页
            for (SinglePlayerSummary current : steam.queryPlayers(steamIds)) {
                SinglePlayerSummary previous = steam.getCachedStatus(current.getSteamid());
                reportChange(steam, previous, current);
                // 刷新缓存，作为下一轮的差分基准
                steam.putCachedStatus(current);
            }
        }
    }

    /**
     * 对比前后两次状态并播报变化。
     *
     * @param steam    Steam 状态服务
     * @param previous 上一轮的状态；首次见到该玩家时为 {@code null}
     * @param current  本轮的状态
     */
    private void reportChange(SteamStatusService steam, SinglePlayerSummary previous, SinglePlayerSummary current) {
        steam.fillGameChineseName(previous);
        steam.fillGameChineseName(current);

        if (previous == null) {
            // 首次见到：只在「正在游戏」时播报，避免刚绑定就刷一条「不在游戏」
            if (current.getGameid() != null) {
                reportPlaying(steam, current);
            }
            return;
        }
        if (previous.getGameid() == null) {
            // 不在游戏 -> 在游戏
            if (current.getGameid() != null) {
                reportPlaying(steam, current);
            }
            return;
        }
        // 之前在游戏
        if (current.getGameid() == null) {
            // 在游戏 -> 不在游戏
            reportStopPlaying(steam, previous);
            return;
        }
        if (!previous.getGameid().equals(current.getGameid())) {
            // 在游戏 -> 换了个游戏
            reportChangePlaying(steam, previous, current);
        }
    }

    /**
     * 播报「换了游戏」。
     *
     * @param steam    Steam 状态服务
     * @param previous 之前的状态
     * @param current  当前的状态
     */
    private void reportChangePlaying(SteamStatusService steam,
                                     SinglePlayerSummary previous,
                                     SinglePlayerSummary current) {
        Long userId = steam.getUserIdBySteamId(previous.getSteamid());
        if (userId == null) {
            // 绑定关系已被解绑但缓存状态尚未清理，跳过
            return;
        }
        for (long groupId : steam.enabledGroupsOfUser(userId)) {
            steam.broadcast(groupId, "群友 " + steam.memberDisplayName(groupId, userId)
                    + "(Steam id: " + previous.getPersonaname() + ") 不再游玩: " + previous.getGameChineseName()
                    + " ; 正在游玩: " + current.getGameChineseName());
        }
    }

    /**
     * 播报「停止游戏」。
     *
     * @param steam    Steam 状态服务
     * @param previous 之前的状态
     */
    private void reportStopPlaying(SteamStatusService steam, SinglePlayerSummary previous) {
        Long userId = steam.getUserIdBySteamId(previous.getSteamid());
        if (userId == null) {
            return;
        }
        for (long groupId : steam.enabledGroupsOfUser(userId)) {
            steam.broadcast(groupId, "群友 " + steam.memberDisplayName(groupId, userId)
                    + "(Steam id: " + previous.getPersonaname() + ") 不再游玩: " + previous.getGameChineseName());
        }
    }

    /**
     * 播报「开始游戏」。
     *
     * @param steam   Steam 状态服务
     * @param current 当前的状态
     */
    private void reportPlaying(SteamStatusService steam, SinglePlayerSummary current) {
        Long userId = steam.getUserIdBySteamId(current.getSteamid());
        if (userId == null) {
            return;
        }
        for (long groupId : steam.enabledGroupsOfUser(userId)) {
            steam.broadcast(groupId, "群友 " + steam.memberDisplayName(groupId, userId)
                    + "(Steam id: " + current.getPersonaname() + ") 正在游玩: " + current.getGameChineseName());
        }
    }

    // ==================== 绑定 / 解绑命令 ====================

    /**
     * 构造命令树。
     *
     * <p>命令树在 {@code apply} 内构造而不是作为 {@code static} 常量，
     * 因为节点处理器需要捕获持有 {@code ctx} 的 {@link SteamStatusService}。
     * 命令树的<b>结构</b>没有变化，只是构造时机从类加载挪到了装配期。</p>
     *
     * @param steam Steam 状态服务
     * @return 命令树根节点
     */
    private CommandNode buildTree(SteamStatusService steam) {
        return CommandNode.builder(CMD_STEAM)
                .description("Steam ID 绑定/解绑")
                .child(bindNode(steam))
                .child(unbindNode(steam))
                .build();
    }

    /**
     * 构造「绑定」子命令节点。
     *
     * @param steam Steam 状态服务
     * @return 子节点
     */
    private CommandNode bindNode(SteamStatusService steam) {
        return Commands.on(CommandNode.builder(CHILD_BIND)
                .description("绑定 steam id")
                .arg(ArgDef.required(ARG_STEAM_ID, "steam id"))
                .build(), (event, result, reply) -> {
            long userId = event.getUserId();
            String steamId = result.getArg(ARG_STEAM_ID).asText().trim();

            List<UserSteamId> existing = steam.findBindingsByUserId(userId);
            if (!existing.isEmpty()) {
                reply.quote().text("你已绑定如下 steam id: " + existing.getFirst().getSteamId()
                        + "; 若要绑定新 id, 请使用 `/steam 解绑id` 命令解除绑定当前 ID").send();
                return;
            }
            if (steamId.isEmpty()) {
                reply.quote().text("steam id 不能为空。").send();
                return;
            }

            steam.insertBinding(userId, steamId);
            steam.putIdMapping(steamId, userId);
            // 立即建立状态基准，避免下一轮监控把「首次见到」误判为状态变化
            SinglePlayerSummary baseline = steam.queryPlayer(steamId);
            steam.fillGameChineseName(baseline);
            steam.putCachedStatus(baseline);
            reply.quote().text("你已绑定 steam id: " + steamId).send();
        });
    }

    /**
     * 构造「解绑」子命令节点。
     *
     * @param steam Steam 状态服务
     * @return 子节点
     */
    private CommandNode unbindNode(SteamStatusService steam) {
        return Commands.on(CommandNode.builder(CHILD_UNBIND)
                .description("解绑 steam id")
                .build(), (event, result, reply) -> {
            long userId = event.getUserId();
            List<UserSteamId> existing = steam.findBindingsByUserId(userId);
            if (existing.isEmpty()) {
                reply.quote().text("你未绑定任何 steam id.").send();
                return;
            }
            String steamId = existing.getFirst().getSteamId();
            steam.deleteBinding(userId);
            steam.removeIdMapping(steamId);
            steam.removeCachedStatus(steamId);
            reply.quote().text("你已解除绑定 steam id: " + steamId).send();
        });
    }
}
