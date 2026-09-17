package steamstatus;

import com.yuier.yuni.core.util.CronExpressionBuilder;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;
import steamstatus.db.UserSteamId;
import steamstatus.model.SinglePlayerSummary;
import steamstatus.utils.SteamStatusService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 群友 steam 游戏状态监控。
 *
 * <h2>迁移说明（P5）：本类是 {@code ScopeQuery} 的验证样板</h2>
 * <p>它取代设计文档 §8.6 里唯一一个「主动插件需要按群粒度启停」的场景。
 * 旧实现是这样判定的：</p>
 * <pre>{@code
 * // MemberSteamStatus.java:113-115 旧
 * PluginEnableProcessor processor = PluginUtils.getBean(PluginEnableProcessor.class);
 * if (processor.isPluginEnabled(groupId, this.getClass())) { ... }
 * }</pre>
 * <p>三个问题：依赖框架内部类、需要把自己的 {@code Class} 当身份传进去、
 * 且必须从 Spring 容器取 Bean。新实现改为 {@code ctx.scope().isEnabledIn(groupId)}
 * ——稳定契约，无需暴露身份（见 {@link SteamStatusService#enabledGroupsOfUser}）。</p>
 *
 * <table border="1">
 *   <caption>其余差异</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends ScheduledPlugin} + {@code cronExpression()} + {@code getAction()}</td>
 *       <td>{@code ctx.schedule(cron, task)}——任务随插件卸载自动取消</td></tr>
 *   <tr><td>{@code PluginUtils.simpleGet(url, ...)}（手工拼 baseUrl 三段字符串）</td>
 *       <td>{@code ctx.http().get(...).query(...)}，见
 *           {@link SteamStatusService#queryPlayers(List)}</td></tr>
 *   <tr><td>{@code PluginUtils.findUserGroups(userId)} 取用户所在全部群</td>
 *       <td>仍取群列表，但只保留「用户确实在群里且启用了本插件」的群</td></tr>
 *   <tr><td>{@code EventLogUtil.getGroupMemberName(gid, uid)}</td>
 *       <td>{@code ctx.bot().getGroupMemberInfo(...)}，见
 *           {@link SteamStatusService#memberDisplayName(long, long)}</td></tr>
 * </table>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>分页步长写错导致漏查</b>：旧 {@code getAction()} 与
 *       {@code SteamStatusUtil.initPlayerStatus()} 都是
 *       {@code for (int i = 0; i <= idDataCount / 100; i += 20)}——
 *       循环变量 {@code i} 既当页码又当 {@code findByPage} 的 offset 用，
 *       而步长 20 与页大小 100 不一致。绑定数超过 100 时，
 *       {@code i = 0, 20, 40 ...} 取到的是第 0/20/40 页，
 *       <b>第 1~19 页的群友永远不会被监控</b>。新实现按
 *       {@code 0, 1, 2, ...} 遍历页码。</li>
 *   <li><b>「取用户所在群」包含了用户并不在的群</b>：旧实现只按
 *       {@code findUserGroups} 的返回群播报，而该方法反映的是「bot 在哪些群里」，
 *       并未校验目标用户是否属于该群。新实现用
 *       {@code getGroupMemberInfo} 确认成员身份。</li>
 *   <li><b>嵌套的 {@code CompletableFuture.runAsync} 没有意义</b>：旧实现
 *       在 {@code for} 循环里对每一页提交一个异步任务——但循环本身已经跑在
 *       调度线程上，异步只是把工作丢到公共池，失败也不会被记录。
 *       现在串行执行，异常由 {@code ctx.schedule} 的调度器捕获并记录。</li>
 *   <li><b>接口异常会中断整轮监控</b>：旧 {@code PluginUtils.simpleGet} 失败抛
 *       {@code RuntimeException}，异常穿透 {@code forEach}，导致后续群友全部不处理。
 *       新实现由 {@link SteamStatusService#queryPlayers(List)} 统一降级为空列表。</li>
 * </ol>
 */
@Slf4j
public class MemberSteamStatus implements YuniPlugin {

    /** 轮询周期：每分钟一次。 */
    private static final String CRON_EVERY_MINUTE = CronExpressionBuilder.create().everyMinutes(1).build();

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("群友 steam 游戏状态监控")
                .version("0.0.1")
                .description("监控群友的 steam 游戏状态")
                .author("Yuier me@yuier.com")
                .tip("群友绑定 steam id 后，其开始/停止/更换游戏时 bot 会在群内播报")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        SteamStatusService steam = new SteamStatusService(ctx);
        steam.initDatabase();
        // 把数据库里的绑定关系灌进缓存，作为差分的初始状态
        steam.syncDbToCache();

        // 取代 ScheduledPlugin：任务随插件卸载自动取消，不需要手动 cancel
        ctx.schedule(CRON_EVERY_MINUTE, () -> poll(steam));
    }

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
        // 页码从 0 连续递增——旧实现把页码与 offset 混用且步长写错，会漏掉中间的页
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
     * <p>旧方法名与参数名都用中文（{@code checkAndReportStatusChange}、
     * {@code 之前的状态}、{@code 新的状态}）。args 名保留中文以维持可读性，
     * 但方法名改为英文，与代码库其余部分一致。</p>
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
}
