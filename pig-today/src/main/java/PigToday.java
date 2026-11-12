import com.yuier.yuni.core.enums.CommandArgRequireType;
import com.yuier.yuni.core.model.message.segment.AtSegment;
import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.service.YuniHttp;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 今日猪猪——查询自己或群友「今天是什么猪」。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin}</td><td>{@code implements YuniPlugin}</td></tr>
 *   <tr><td>{@code getDetector()} + {@code execute()} 两段式</td>
 *       <td>{@code Commands.on(ROOT, handler)} 就地绑定</td></tr>
 *   <tr><td>{@code execute()} 里 {@code if (hasArg) ... else ...} 分派</td>
 *       <td>同一个处理器内分支（单命令单参数，无需拆成子命令）</td></tr>
 *   <tr><td>{@code RedisUtil.exists + get + 手写 Map 序列化}</td>
 *       <td>{@code ctx.getCache().hashHasKey / hashGet / hashSet}</td></tr>
 *   <tr><td>{@code PluginUtils.simpleGet}</td><td>{@code ctx.getHttp().get(...).execute(...)}</td></tr>
 *   <tr><td>{@code PluginUtils.getGroupMemberName(gid, qq)}
 *       ——额外调一次 {@code get_group_member_info}</td>
 *       <td>仍用 {@code ctx.getBot()} 查询（命令事件未携带目标成员的群名片）</td></tr>
 * </table>
 *
 * <h2>顺带修掉的两处缺陷</h2>
 * <ol>
 *   <li><b>「先建空 Map 再写入」的竞态与 NPE</b>：旧 {@code getPigToday} 在缓存键
 *       不存在时先 {@code set(key, new HashMap<>())}，随后再次 {@code get(key)}
 *       并直接 {@code pigTodayMap.put(...)}——若两次调用之间键被清理，
 *       第二次 get 返回 null，put 抛 NPE。改用哈希字段后不再需要这套两步操作。</li>
 *   <li><b>缓存键未做日期隔离</b>：旧实现把「日期」存在每个 value 里，
 *       于是同一哈希会随日期增长且需要逐个比对；现在日期直接参与哈希字段名。</li>
 * </ol>
 *
 * <h2>关于「一天内结果不变」</h2>
 * <p>这是本插件的核心语义：同一个人在同一天内重复查询得到同一只猪。
 * 实现方式是把结果按「日期 + QQ号」作为哈希字段缓存一整天。</p>
 */
@Slf4j
public class PigToday implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD_PIG_TODAY = "今日猪猪";

    /** 可选参数名：目标群友。 */
    private static final String ARG_TARGET_MEMBER = "目标成员";

    /** 猪图列表接口。 */
    private static final String API_ALL_IMAGES = "https://pighub.top/api/images?sort=0";

    /** 猪图地址前缀（接口返回的是相对路径）。 */
    private static final String IMAGE_BASE_URL = "https://pighub.top";

    /** 缓存哈希键。 */
    private static final String CACHE_KEY = "plugin:pigtoday:key";

    /** 命令树：单个可选 AT 参数。 */
    private static final CommandNode ROOT = CommandNode.builder(CMD_PIG_TODAY)
            .description("今日猪猪")
            .arg(ArgDef.optional(ARG_TARGET_MEMBER, "猪猪名称", CommandArgRequireType.AT))
            .build();


    @Override
    public void apply(YuniContext ctx) {
        ctx.command(Commands.on(ROOT, (event, reply) -> {
            var result = event.getCommandResult();
            if (!result.hasArg(ARG_TARGET_MEMBER)) {
                // 未指定目标：查自己
                reply.quote()
                        .text("你今天是: \n" + resolve(ctx, event.getUserId()))
                        .send();
                return;
            }

            AtSegment at = result.getArg(ARG_TARGET_MEMBER).asAt();
            String targetQq = at.getQq();
            if ("all".equals(targetQq)) {
                // @全体成员 不是一个具体的人
                reply.quote().text("请指定具体群友").send();
                return;
            }

            long targetId = Long.parseLong(targetQq);
            String targetName = memberDisplayName(ctx, event.getGroupId(), targetId);
            reply.quote()
                    .text(targetName + " 今天是: \n" + resolve(ctx, targetId))
                    .send();
        }));
    }

    /**
     * 取某个人的今日猪猪结果（文本 + 图片）。
     *
     * <p>结果按「日期 + QQ号」缓存一整天——这是本插件「一天内猪设不变」的实现。</p>
     *
     * @param ctx    插件上下文
     * @param userId 目标 QQ 号
     * @return 形如 {@code "标题\n<图片地址>"} 的文本拼接片段（图片由调用方追加）
     */
    private String resolve(YuniContext ctx, long userId) {
        String field = cacheField(userId);

        // 先查缓存
        PigTodayResult cached = ctx.getCache().hashGet(CACHE_KEY, field, PigTodayResult.class).orElse(null);
        if (cached != null && LocalDate.now().toString().equals(cached.getDate())) {
            return cached.getImageTitle() + "\n" + IMAGE_BASE_URL + cached.getImageUrl();
        }

        // 缓存未命中：请求接口
        PigTodayResult fresh = fetchRandom(ctx);
        if (fresh == null) {
            return "猪猪图库暂时取不到，请稍后再试。";
        }
        fresh.setDate(LocalDate.now().toString());
        // 写入哈希字段：取代旧实现的「先建空 Map 再二次读取后写入」
        ctx.getCache().hashSet(CACHE_KEY, field, fresh);
        return fresh.getImageTitle() + "\n" + IMAGE_BASE_URL + fresh.getImageUrl();
    }

    /**
     * 从猪图接口随机取一张。
     *
     * @param ctx 插件上下文
     * @return 结果；接口异常或数据为空时返回 {@code null}
     */
    private PigTodayResult fetchRandom(YuniContext ctx) {
        PigImageList response;
        try {
            response = ctx.getHttp().get(API_ALL_IMAGES).execute(PigImageList.class);
        } catch (YuniHttp.HttpException e) {
            // 外部接口失败不应打挂命令：记录并降级为一条提示
            log.warn("[今日猪猪] 猪图接口调用失败: {}", e.getMessage());
            return null;
        }
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            log.warn("[今日猪猪] 猪图接口返回空数据");
            return null;
        }
        List<PigImageElement> data = response.getData();
        PigImageElement picked = data.get(ThreadLocalRandom.current().nextInt(data.size()));
        return new PigTodayResult(picked.getTitle(), picked.getFilename(), null, picked.getImageUrl());
    }

    /**
     * 取群成员的显示名（群名片优先，回退昵称，最后回退 QQ 号）。
     *
     * <p>命令事件里没有目标成员的资料（目标由 AT 参数给出），因此仍需一次接口查询。
     * 这与 {@code PokeNotice} 那类「事件本身已带发送者资料」的场景不同。</p>
     *
     * @param ctx     插件上下文
     * @param groupId 群号
     * @param userId  目标 QQ 号
     * @return 显示名，永不为 {@code null}
     */
    private String memberDisplayName(YuniContext ctx, Long groupId, long userId) {
        if (groupId == null) {
            return String.valueOf(userId);
        }
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
     * 组装缓存字段名。
     *
     * <p>把日期放进字段名，使「今天的缓存」与历史日期天然隔离，
     * 不需要在读取后比对日期再决定是否复用。</p>
     *
     * @param userId 目标 QQ 号
     * @return 形如 {@code "2026-09-16:10001"} 的字段名
     */
    private static String cacheField(long userId) {
        return LocalDate.now() + ":" + userId;
    }

    /**
     * 猪图接口的响应模型。
     */
    @Data
    public static class PigImageList {

        /** 业务状态码。 */
        private Integer code;

        /** 提示信息。 */
        private String message;

        /** 猪图列表。 */
        private List<PigImageElement> data;
    }

    /**
     * 单张猪图的模型。
     */
    @Data
    public static class PigImageElement {

        /** 标题（猪名）。 */
        private String title;

        /** 文件名。 */
        private String filename;

        /** 图片相对地址。 */
        private String imageUrl;
    }

    /**
     * 今日猪猪的缓存对象。
     *
     * <p>取代旧的 {@code entity.PigTodayCache}。字段与旧类保持一致，
     * 因此历史缓存仍可反序列化。</p>
     *
     * <p>无参构造供 Jackson 反序列化，全参构造供代码内构建。</p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PigTodayResult {

        /** 猪的标题。 */
        private String imageTitle;

        /** 猪图文件名。 */
        private String imageName;

        /** 生成日期；用于「一天内不变」的判定。 */
        private String date;

        /** 图片相对地址。 */
        private String imageUrl;
    }
}
