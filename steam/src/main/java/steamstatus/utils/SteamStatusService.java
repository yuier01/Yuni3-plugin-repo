package steamstatus.utils;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.plugin.api.ScopeQuery;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.service.YuniCache;
import com.yuier.yuni.plugin.api.service.YuniHttp;
import lombok.extern.slf4j.Slf4j;
import steamstatus.config.SteamApiKey;
import steamstatus.db.UserSteamId;
import steamstatus.model.AppDetailResponse;
import steamstatus.model.AppDetailsApiResponse;
import steamstatus.model.PlayerSummariesResponse;
import steamstatus.model.SinglePlayerSummary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Steam 状态服务的<b>实例化</b>工具。
 *
 * <h2>迁移说明（P5）：为什么从静态工具类变成实例</h2>
 * <p>旧实现由两个静态工具类组成——{@code SteamStatusUtil}（业务逻辑）与
 * {@code RedisHelper}（缓存读写）。它们全部是 {@code static} 方法，内部靠
 * {@code PluginUtils} 静态门面拿机器人、拿 Bean、读写 Redis、序列化 JSON。
 * 具体问题：</p>
 * <ol>
 *   <li><b>缓存读写散落两处</b>：{@code RedisHelper} 只是把
 *       {@code RedisUtil} 的 8 个方法原样转发一遍，业务代码还得自己
 *       {@code PluginUtils.serialize/deserialize}——序列化与键名约定被拆到了
 *       两个文件里，改一处要同时改另一处。</li>
 *   <li><b>依赖 {@code PluginEnableProcessor} 内部 API</b>：
 *       {@code MemberSteamStatus} 直接 {@code PluginUtils.getBean(PluginEnableProcessor.class)}
 *       查询「本群是否启用本插件」。这是框架内部类，插件本不应该依赖它。</li>
 *   <li><b>静态方法无法测试</b>：任何针对状态差分逻辑的测试都要起整个 Spring 容器。</li>
 * </ol>
 * <p>新实现把这个服务做成<b>持有 {@link YuniContext} 的实例</b>：插件在
 * {@code apply(ctx)} 里 {@code new SteamStatusService(ctx)} 并自己保存引用，
 * 全部依赖都从 {@code ctx} 取——可打桩、依赖显式、键名与序列化集中在一处。</p>
 *
 * <h2>两个缓存键</h2>
 * <ul>
 *   <li>{@link #KEY_STEAM_ID_TO_USER_ID}：steamid → QQ 号（绑定关系）；</li>
 *   <li>{@link #KEY_STEAM_ID_TO_STATUS}：steamid → 上一次看到的玩家状态（差分基准）。</li>
 * </ul>
 * <p>键名沿用旧值，因此历史缓存仍可直接使用。</p>
 */
@Slf4j
public class SteamStatusService {

    /** 缓存哈希键：steamid → QQ 号。 */
    public static final String KEY_STEAM_ID_TO_USER_ID = "plugin:steam:steamid:to:userid:key";

    /** 缓存哈希键：steamid → 上一次看到的玩家状态。 */
    public static final String KEY_STEAM_ID_TO_STATUS = "plugin:steam:steamid:to:playerstatus:key";

    /** API Key 配置文件（相对插件资源目录）。 */
    private static final String CONFIG_API_KEY = "steam_api_key.json";

    /** GetPlayerSummaries 接口地址。 */
    private static final String API_PLAYER_SUMMARIES =
            "https://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/";

    /** 查询游戏中文名接口地址。 */
    private static final String API_APP_DETAILS = "https://store.steampowered.com/api/appdetails";

    /** 单次批量查询的 steamid 数量上限。 */
    public static final int BATCH_SIZE = 100;

    /** 插件上下文。 */
    private final YuniContext ctx;

    /**
     * 构造服务。
     *
     * @param ctx 插件上下文
     */
    public SteamStatusService(YuniContext ctx) {
        this.ctx = ctx;
    }

    // ==================== 数据库 ====================

    /**
     * 注册实体并建表。
     *
     * <p>取代旧 {@code DBHelper.createTableIfNotExists()}——旧实现手写
     * {@code @SqlUpdate("CREATE TABLE IF NOT EXISTS ...")} 并把 DDL 文本与实体类
     * 分开维护，加字段时容易漏改。新系统由 {@code TableSchema} 从实体类推导 DDL。</p>
     */
    public void initDatabase() {
        ctx.getDb().registerEntity(UserSteamId.class);
    }

    /**
     * 取绑定记录总数。
     *
     * @return 记录数
     */
    public long countBindings() {
        return ctx.getDb().getRepository(UserSteamId.class).count();
    }

    /**
     * 分页取绑定记录。
     *
     * @param pageNum  页码，从 0 开始
     * @param pageSize 每页条数
     * @return 记录列表
     */
    public List<UserSteamId> findBindingsByPage(int pageNum, int pageSize) {
        return ctx.getDb().getRepository(UserSteamId.class).findPage(pageNum, pageSize);
    }

    /**
     * 按 QQ 号查绑定记录。
     *
     * @param userId QQ 号
     * @return 记录列表；无记录时为空列表
     */
    public List<UserSteamId> findBindingsByUserId(long userId) {
        return ctx.getDb().getRepository(UserSteamId.class).findByField("userId", userId);
    }

    /**
     * 新增一条绑定记录。
     *
     * @param userId  QQ 号
     * @param steamId steam id
     */
    public void insertBinding(long userId, String steamId) {
        ctx.getDb().getRepository(UserSteamId.class).save(new UserSteamId(userId, steamId));
    }

    /**
     * 按 QQ 号删除绑定记录。
     *
     * @param userId QQ 号
     */
    public void deleteBinding(long userId) {
        ctx.getDb().getRepository(UserSteamId.class).deleteByField("userId", userId);
    }

    // ==================== 缓存 ====================

    /**
     * 把数据库中的全部绑定关系同步到缓存。
     *
     * <p>取代旧 {@code syncDbToRedis()}。旧实现先 {@code delete(key)} 再
     * {@code hSetAll(...)}——两步之间缓存是空的，若此时有查询会读到「没有绑定」。
     * 新实现逐字段写入，不做整体删除：哈希里多出来的历史字段无害
     * （{@link #getUserIdBySteamId} 查不到就返回 {@code null}），
     * 而中间态不会出现「全部消失」。</p>
     */
    public void syncDbToCache() {
        long total = countBindings();
        if (total == 0) {
            return;
        }
        int pageCount = (int) ((total + BATCH_SIZE - 1) / BATCH_SIZE);
        YuniCache yuniCache = ctx.getCache();
        for (int page = 0; page < pageCount; page++) {
            for (UserSteamId binding : findBindingsByPage(page, BATCH_SIZE)) {
                if (binding.getSteamId() != null && !binding.getSteamId().isEmpty()) {
                    yuniCache.hashSet(KEY_STEAM_ID_TO_USER_ID, binding.getSteamId(), binding.getUserId());
                }
            }
        }
    }

    /**
     * 写入一条 steamid → QQ 号映射。
     *
     * @param steamId steam id
     * @param userId  QQ 号
     */
    public void putIdMapping(String steamId, long userId) {
        ctx.getCache().hashSet(KEY_STEAM_ID_TO_USER_ID, steamId, userId);
    }

    /**
     * 删除一条 steamid → QQ 号映射。
     *
     * @param steamId steam id
     */
    public void removeIdMapping(String steamId) {
        ctx.getCache().hashDelete(KEY_STEAM_ID_TO_USER_ID, steamId);
    }

    /**
     * 按 steamid 查 QQ 号。
     *
     * @param steamId steam id
     * @return QQ 号；无映射时返回 {@code null}
     */
    public Long getUserIdBySteamId(String steamId) {
        if (steamId == null) {
            return null;
        }
        // 直接按目标类型读取，取代旧的「hGetAll 拿 Object 再手写 toString 转 Long」
        return ctx.getCache().hashGet(KEY_STEAM_ID_TO_USER_ID, steamId, Long.class)
                .orElse(null);
    }

    /**
     * 读取某玩家上一次的状态快照。
     *
     * <p>取代旧 {@code getPlayerStatusBySteamId}——旧实现先取整个哈希
     * （{@code HGETALL}，n 个绑定就要传 n 条 value），再 {@code PluginUtils.deserialize}。
     * 新实现只取需要的那个字段，并且由 {@link YuniCache} 负责反序列化。</p>
     *
     * @param steamId steam id
     * @return 状态快照；无记录时返回 {@code null}
     */
    public SinglePlayerSummary getCachedStatus(String steamId) {
        if (steamId == null) {
            return null;
        }
        return ctx.getCache().hashGet(KEY_STEAM_ID_TO_STATUS, steamId, SinglePlayerSummary.class)
                .orElse(null);
    }

    /**
     * 写入某玩家的状态快照。
     *
     * @param player 玩家状态
     */
    public void putCachedStatus(SinglePlayerSummary player) {
        if (player == null || player.getSteamid() == null) {
            return;
        }
        ctx.getCache().hashSet(KEY_STEAM_ID_TO_STATUS, player.getSteamid(), player);
    }

    /**
     * 删除某玩家的状态快照。
     *
     * @param steamId steam id
     */
    public void removeCachedStatus(String steamId) {
        ctx.getCache().hashDelete(KEY_STEAM_ID_TO_STATUS, steamId);
    }

    // ==================== Steam 接口 ====================

    /**
     * 批量查询玩家概况。
     *
     * <p>取代旧 {@code PluginUtils.simpleGet(url, ...)}——旧的 {@code baseUrl}
     * 是「地址 + key + steamids=」手工拼出来的三段字符串，而 {@code simpleGet} 的
     * 第二个参数名义上是「参数」实际是 URL 模板变量（审计报告已记录该命名误导）。
     * 新实现用 {@code query(...)} 显式表达查询参数，URL 拼接不再手工完成。</p>
     *
     * @param steamIds steam id 列表
     * @return 玩家概况列表；请求失败或响应为空时返回空列表
     */
    public List<SinglePlayerSummary> queryPlayers(List<String> steamIds) {
        if (steamIds == null || steamIds.isEmpty()) {
            return List.of();
        }
        String apiKey = apiKey();
        if (apiKey == null) {
            return List.of();
        }
        try {
            PlayerSummariesResponse response = ctx.getHttp()
                    .get(API_PLAYER_SUMMARIES)
                    .query("key", apiKey)
                    .query("steamids", String.join(",", steamIds))
                    .execute(PlayerSummariesResponse.class);
            if (response == null || response.getResponse() == null
                    || response.getResponse().getPlayers() == null) {
                return List.of();
            }
            return response.getResponse().getPlayers();
        } catch (YuniHttp.HttpException e) {
            // 外部接口失败不应打挂定时任务：返回空列表，下一轮再试
            log.warn("[Steam] 查询玩家概况失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询单个玩家的概况。
     *
     * @param steamId steam id
     * @return 玩家概况；查不到时返回 {@code null}
     */
    public SinglePlayerSummary queryPlayer(String steamId) {
        List<SinglePlayerSummary> players = queryPlayers(List.of(steamId));
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * 补齐玩家状态里的「游戏中文名」。
     *
     * <p>旧实现把 appdetails 的响应当成裸 {@code Map} 反序列化，然后一路
     * {@code instanceof Map} + 强制转型向下取 {@code data.name}——既无法静态检查，
     * 又要把 {@code @SuppressWarnings} 贴在三个地方。新实现用
     * {@link AppDetailResponse} 描述响应结构，取值变成普通 getter。</p>
     *
     * <p>任何一步拿不到中文名都回退到 {@code gameextrainfo}（Steam 给的英文名），
     * 与旧行为一致。</p>
     *
     * @param player 玩家状态；{@code null} 或不在游戏中时直接返回
     */
    public void fillGameChineseName(SinglePlayerSummary player) {
        if (player == null || player.getGameChineseName() != null || player.getGameid() == null) {
            return;
        }
        try {
            AppDetailsApiResponse response = ctx.getHttp()
                    .get(API_APP_DETAILS)
                    .query("appids", player.getGameid())
                    .query("l", "schinese")
                    .query("filters", "basic")
                    .execute(AppDetailsApiResponse.class);
            String name = response == null ? null : response.appName(player.getGameid());
            player.setGameChineseName(name != null ? name : player.getGameextrainfo());
        } catch (YuniHttp.HttpException e) {
            log.warn("[Steam] 查询游戏中文名失败，回退英文名: {}", e.getMessage());
            player.setGameChineseName(player.getGameextrainfo());
        }
    }

    /**
     * 取 API Key。
     *
     * @return API Key；配置缺失时返回 {@code null}
     */
    private String apiKey() {
        try {
            SteamApiKey key = ctx.getConfigLoader().load(CONFIG_API_KEY, SteamApiKey.class);
            if (key == null || key.getApikey() == null || key.getApikey().isBlank()) {
                log.error("[Steam] {} 中缺少 apikey 字段", CONFIG_API_KEY);
                return null;
            }
            return key.getApikey();
        } catch (RuntimeException e) {
            log.error("[Steam] 读取 API Key 配置失败", e);
            return null;
        }
    }

    // ==================== 广播辅助 ====================

    /**
     * 找出某个用户所在的、且<b>启用了本插件</b>的群。
     *
     * <h3>迁移要点</h3>
     * <p>旧实现是两步：{@code PluginUtils.findUserGroups(userId)} 拿全部群，
     * 再用 {@code PluginUtils.getBean(PluginEnableProcessor.class).isPluginEnabled(groupId, this.getClass())}
     * 逐个判定。后者是框架内部 API，且需要把插件自己的 {@code Class} 传进去
     * 当身份标识。</p>
     * <p>新实现用 {@link ScopeQuery}——由框架提供、插件侧稳定的契约，
     * 不需要暴露自己的 Class，也不需要从 Spring 容器里取 Bean。</p>
     *
     * @param userId 目标 QQ 号
     * @return 群号列表；用户不在任何启用本插件的群时为空列表
     */
    public Set<Long> enabledGroupsOfUser(long userId) {
        Set<Long> result = new LinkedHashSet<>();
        List<BotGroupInfo> groups = ctx.getBot().getGroupList().orElse(List.of());
        ScopeQuery scope = ctx.getScope();
        for (BotGroupInfo group : groups) {
            long groupId = group.getGroupId();
            if (!scope.isEnabledIn(groupId)) {
                continue;
            }
            // 只对「用户确实在这个群里」的群播报，否则会把陌生人的游戏动态发到无关群
            boolean isMember = ctx.getBot()
                    .getGroupMemberInfo(String.valueOf(groupId), String.valueOf(userId))
                    .isPresent();
            if (isMember) {
                result.add(groupId);
            }
        }
        return result;
    }

    /**
     * 取群成员显示名（群名片优先，回退昵称，最后回退 QQ 号）。
     *
     * <p>取代 {@code EventLogUtil.getGroupMemberName(groupId, userId)}。</p>
     *
     * @param groupId 群号
     * @param userId  QQ 号
     * @return 显示名，永不为 {@code null}
     */
    public String memberDisplayName(long groupId, long userId) {
        return ctx.getBot()
                .getGroupMemberInfo(String.valueOf(groupId), String.valueOf(userId))
                .map(info -> {
                    String card = info.getCard();
                    if (card != null && !card.isBlank()) {
                        return card;
                    }
                    String nickname = info.getNickname();
                    return nickname == null || nickname.isBlank() ? String.valueOf(userId) : nickname;
                })
                .orElseGet(() -> String.valueOf(userId));
    }

    /**
     * 向群广播一条文本。
     *
     * @param groupId 群号
     * @param text    文本内容
     */
    public void broadcast(long groupId, String text) {
        ctx.getSender().group(groupId).text(text).send();
    }
}
