package steamstatus;

import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;
import steamstatus.db.UserSteamId;
import steamstatus.model.SinglePlayerSummary;
import steamstatus.utils.SteamStatusService;

import java.util.List;

/**
 * 绑定 / 解绑 steam id。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin}；{@code execute()} 里
 *       {@code if (result.hasChild(解绑ID)) ... if (result.hasChild(绑定ID)) ...}
 *       手工分派</td>
 *       <td>两个子节点各自 {@code .on(...)}，分派由命令分发器完成</td></tr>
 *   <tr><td>{@code DBHelper.findByUserId / insert / deleteByUserId}（静态方法，
 *       每次调用都新建 Jdbi 连接）</td>
 *       <td>{@code ctx.getDb().getRepository(UserSteamId.class)}</td></tr>
 *   <tr><td>{@code PluginUtils.serialize/deserialize} + {@code RedisHelper}
 *       手写缓存读写</td><td>见 {@link SteamStatusService}</td></tr>
 * </table>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>解绑时缓存只删了一半</b>：旧 {@code unbindSteamId} 调
 *       {@code RedisHelper.deleteId} 删掉 steamid→QQ 映射，却没有删掉
 *       {@code STEAM_ID_TO_PLAYER_STATUS_MAPPING} 里的状态快照。
 *       于是「解绑 → 重新绑定」后，{@code MemberSteamStatus} 会把上一次遗留的
 *       状态当作「之前的状态」做差分，可能播报一次并不存在的状态变化。
 *       新实现调用 {@link SteamStatusService#removeCachedStatus(String)} 一并清理。</li>
 *   <li><b>两个平行 {@code if} 而非互斥分支</b>：旧 {@code execute} 里
 *       {@code if (hasChild(解绑)) ... if (hasChild(绑定)) ...} 没有 {@code else}。
 *       命令匹配只可能命中一个子节点，所以不是实际 bug，但拆成两个独立处理器后
 *       这类写法从结构上消失。</li>
 *   <li><b>绑定后的状态基准不完整</b>：旧实现只把接口返回的原始状态存进缓存，
 *       没有补齐 {@code gameChineseName}，于是该用户首次状态变化播报里会出现
 *       英文游戏名。新实现在绑定时就用
 *       {@link SteamStatusService#fillGameChineseName} 补齐。</li>
 * </ol>
 */
@Slf4j
public class UserRegisterSteamId implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD_STEAM = "steam";

    /** 子命令：绑定。 */
    private static final String CHILD_BIND = "绑定id";

    /** 子命令：解绑。 */
    private static final String CHILD_UNBIND = "解绑id";

    /** 参数名：steam id。 */
    private static final String ARG_STEAM_ID = "ID参数";

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("绑定/解绑 steam id")
                .version("0.0.1")
                .description("群友绑定/解绑 steam id")
                .author("Yuier me@yuier.com")
                .tip("向群聊发送 `/steam 绑定id {steam id}` 命令绑定 steam id")
                .tip("向群聊发送 `/steam 解绑id` 命令解绑 steam id")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        SteamStatusService steam = new SteamStatusService(ctx);
        steam.initDatabase();
        ctx.command(buildTree(steam));
    }

    /**
     * 构造命令树。
     *
     * <p>命令树在 {@code apply} 内构造而不是作为 {@code static} 常量，
     * 因为节点处理器需要捕获持有 {@code ctx} 的 {@link SteamStatusService}，
     * 而 {@code static} 常量在类加载时就要求一切依赖已就绪——新系统里插件被
     * 实例化时还没有 {@code ctx}。命令树的<b>结构</b>没有变化，
     * 只是构造时机从类加载挪到了装配期。</p>
     *
     * <p>本方法拆出「绑定」「解绑」两个私有方法，避免三层嵌套的
     * {@code builder().child(builder().build()).build()} 写成难以核对括号的
     * 一坨表达式。</p>
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
                .build(), (event, reply) -> {
            long userId = event.getUserId();
            String steamId = event.getCommandResult().getArg(ARG_STEAM_ID).asText().trim();

            List<UserSteamId> existing = steam.findBindingsByUserId(userId);
            if (!existing.isEmpty()) {
                reply.quote().text("你已绑定如下 steam id: " + existing.getFirst().getSteamId()
                        + "; 若要绑定新 id, 请使用 `/steam 解绑id` 命令解除绑定当前 ID").send();
                return;
            }
            if (steamId.isEmpty()) {
                // 旧实现直接把它写进数据库，之后每一轮监控都会拿一个空 id 去请求接口
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
                .build(), (event, reply) -> {
            long userId = event.getUserId();
            List<UserSteamId> existing = steam.findBindingsByUserId(userId);
            if (existing.isEmpty()) {
                reply.quote().text("你未绑定任何 steam id.").send();
                return;
            }
            String steamId = existing.getFirst().getSteamId();
            steam.deleteBinding(userId);
            steam.removeIdMapping(steamId);
            // 一并清理状态快照：旧实现漏了这一步，导致重新绑定后会误播报状态变化
            steam.removeCachedStatus(steamId);
            reply.quote().text("你已解除绑定 steam id: " + steamId).send();
        });
    }
}
