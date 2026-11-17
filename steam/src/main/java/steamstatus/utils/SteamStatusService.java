package steamstatus.utils;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Steam 状态服务的<b>实例化</b>工具。
 *
 * <h2>两个缓存键</h2>
 * <ul>
 *   <li>{@link #KEY_STEAM_ID_TO_USER_ID}：steamid → QQ 号（绑定关系）；</li>
 *   <li>{@link #KEY_STEAM_ID_TO_STATUS}：steamid → 上一次看到的玩家状态（差分基准）。</li>
 * </ul>
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
        return ctx.getCache().hashGet(KEY_STEAM_ID_TO_USER_ID, steamId, Long.class)
                .orElse(null);
    }

    /**
     * 读取某玩家上一次的状态快照。
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
            if (isGroupMember(groupId, userId)) {
                result.add(groupId);
            }
        }
        return result;
    }

    /**
     * 判断用户是否真的在指定群中。
     *
     * <p>不能只看 {@code getGroupMemberInfo(...)} 是否返回 Optional：部分
     * OneBot 实现（如 NapCat）对非群成员也返回 {@code retcode=0}，data 中只有
     * 请求参数回填的 {@code group_id}/{@code user_id}，昵称和入群时间都是空的。
     * 这种占位对象会被误判为真实成员，导致消息广播到所有群。</p>
     *
     * @param groupId 群号
     * @param userId  用户 QQ 号
     * @return 用户确实在群中时返回 {@code true}
     */
    private boolean isGroupMember(long groupId, long userId) {
        Optional<BotGroupMemberInfo> info;
        try {
            info = ctx.getBot().getGroupMemberInfo(
                    String.valueOf(groupId), String.valueOf(userId));
        } catch (RuntimeException e) {
            log.warn("[Steam] 查询群成员信息失败，跳过群 {}: {}", groupId, e.getMessage());
            return false;
        }

        if (info.isEmpty()) {
            return false;
        }
        if (isRealMember(info.get(), userId)) {
            return true;
        }
        // 占位对象无法作为成员依据，回退到群成员列表确认
        return isMemberInList(groupId, userId);
    }

    /**
     * 判断成员对象是否包含真实成员数据。
     *
     * @param member 成员对象
     * @param userId 目标 QQ 号
     * @return 是真实成员时返回 {@code true}
     */
    private static boolean isRealMember(BotGroupMemberInfo member, long userId) {
        return member != null
                && Objects.equals(member.getUserId(), userId)
                && member.getNickname() != null
                && !member.getNickname().isBlank();
    }

    /**
     * 通过群成员列表确认成员身份。
     *
     * @param groupId 群号
     * @param userId  用户 QQ 号
     * @return 在成员列表中时返回 {@code true}
     */
    private boolean isMemberInList(long groupId, long userId) {
        try {
            return ctx.getBot().getGroupMemberList(String.valueOf(groupId))
                    .map(members -> members.stream()
                            .anyMatch(member -> member != null
                                    && Objects.equals(member.getUserId(), userId)))
                    .orElse(false);
        } catch (RuntimeException e) {
            log.warn("[Steam] 查询群成员列表失败，跳过群 {}: {}", groupId, e.getMessage());
            return false;
        }
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
