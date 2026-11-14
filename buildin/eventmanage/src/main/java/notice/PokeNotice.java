package notice;

import com.yuier.yuni.core.event.notice.PokeEvent;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 群内戳一戳——被戳时按次数循环回复不同文案。
 *
 * <h2>迁移说明（P5）：本类是 {@code ctx.getState()} 的验证样板</h2>
 * <p>它替换掉设计文档 §8.5 里唯一一个「真正持有群内可变状态」的插件，
 * 因此这批迁移的重点就是验证 {@code ctx.state(Class)} 能否替代
 * 「每群一份插件实例」。</p>
 *
 * <h2>顺带修掉一个真实竞态</h2>
 * <p>旧实现的状态字段是：</p>
 * <pre>{@code
 * // PokeNotice.java:23 旧
 * Map<Long, Map<Long, Integer>> pokeCountMap = new HashMap<>();
 * }</pre>
 * <p>而插件执行路径是 {@code CompletableFuture.runAsync(...)}（见
 * {@code PassivePluginMatcher}）——即<b>在 ForkJoinPool 的异步线程上并发读写</b>
 * 这个普通 {@code HashMap}。两条消息同时到达同一群时存在数据竞争，
 * 极端情况下 {@code HashMap} 扩容期间并发写会形成环形链表并使 get 死循环。</p>
 *
 * <p>新实现用 {@link ConcurrentHashMap} + {@link AtomicInteger#incrementAndGet()}
 * 做原子自增，竞争被彻底消除。</p>
 *
 * <h2>状态放哪里</h2>
 * <p>用 {@code ctx.state(PokeState.class)} 取「当前作用域的状态对象」——
 * 状态按作用域分键，未使用的组合不占内存（设计文档 §8.5）。
 * 因本插件声明为群作用域（多实例共享同一实例、按群过滤），
 * 状态需要自己按 {@code groupId} 分桶——这是「单一实例 + 作用域过滤」
 * 方案下持有群内状态的标准写法。</p>
 *
 * <h2>不再需要解 rawJson</h2>
 * <p>旧实现必须自己把 {@code rawJson} 反序列化两次才能判断
 * 「这是不是一个戳一戳通知」：</p>
 * <pre>{@code
 * // PokeNotice.java:33-42 旧
 * return new DefaultYuniNoticeDetector(event -> {
 *     if ("notify".equals(event.getNoticeType())) {
 *         PokeEvent e = PluginUtils.deserialize(event.getRawJson(), PokeEvent.class);   // 第一次
 *         if ("poke".equals(e.getSubType())) {
 *             return PluginUtils.deserialize(event.getRawJson(), PokeEvent.class);      // 第二次
 *         }
 *     }
 *     return null;
 * });
 * }</pre>
 * <p>新系统的适配器已在翻译阶段把 {@code sub_type} 与子类型特有字段
 * （{@code target_id}）填好，因此注册时写 {@code ctx.onNotice("poke", ...)}
 * 即可，处理器内直接读字段。</p>
 */
public class PokeNotice implements YuniPlugin {

    /** 被戳次数对应的回复文案，按次数循环。 */
    private static final String[] POKE_MESSAGES = {
            "戳我干嘛？",
            "别戳我！",
            "说了别戳我！",
            "再戳我要生气了！"
    };


    @Override
    public void apply(YuniContext ctx) {
        // 只需声明子类型：戳一戳的判定在注册时完成，处理器拿到的必定是 poke
        ctx.onNotice("poke", (ev, reply) -> {
            // 只响应「戳的是机器人自己」
            if (!(ev instanceof PokeEvent poke)) {
                return;
            }
            Long target = poke.getTargetId();
            if (target == null || !target.equals(poke.getSelfId())) {
                return;
            }

            Long groupId = poke.getGroupId();
            if (groupId == null) {
                // 戳一戳出现在非群会话：当前实现只处理群内
                return;
            }

            int count = countFor(ctx, groupId, poke.getUserId());
            reply.at(poke.getUserId()).text(POKE_MESSAGES[count]).send();
        });
    }

    /**
     * 累加并取「本次应使用第几条文案」。
     *
     * <p>取模前的计数会持续增长，但只在状态对象内——文案按下标循环，
     * 因此对外表现与旧实现一致（第 4 次后回到第 1 条）。</p>
     *
     * @param ctx     插件上下文
     * @param groupId 群号
     * @param userId  被戳次数计数的用户（发起戳的人）
     * @return 文案下标
     */
    private int countFor(YuniContext ctx, long groupId, long userId) {
        PokeState state = ctx.state(PokeState.class);
        // 按群分包，再按用户计数——取代旧实现的双层 Map
        AtomicInteger counter = state.counters
                .computeIfAbsent(groupId, g -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, u -> new AtomicInteger());
        // 原子自增，竞争安全
        return Math.floorMod(counter.getAndIncrement(), POKE_MESSAGES.length);
    }

    /**
     * 本插件的作用域状态。
     *
     * <p>框架按作用域懒创建并按类型缓存（{@link YuniContext#state(Class)}），
     * 因此本类只负责「状态长什么样」，不负责生命周期。</p>
     *
     * <p><b>必须是线程安全的</b>：处理器在 {@code ForkJoinPool} 上并发执行。
     * 这里用两层 {@link ConcurrentHashMap} + {@link AtomicInteger}
     * 取代旧实现的两层 {@code HashMap} + {@code int}。</p>
     */
    public static final class PokeState {

        /**
         * 群号 → （用户 QQ 号 → 累计被戳次数）。
         *
         * <p>两层都是并发容器；计数值用 {@link AtomicInteger} 以支持原子自增。</p>
         */
        final Map<Long, Map<Long, AtomicInteger>> counters = new ConcurrentHashMap<>();
    }
}
