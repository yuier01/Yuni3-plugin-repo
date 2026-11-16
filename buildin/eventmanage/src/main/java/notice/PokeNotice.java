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
 * <h2>状态放哪里</h2>
 * <p>用 {@code ctx.state(PokeState.class)} 取「当前作用域的状态对象」——
 * 状态按作用域分键，未使用的组合不占内存（设计文档 §8.5）。
 * 因本插件声明为群作用域（多实例共享同一实例、按群过滤），
 * 状态需要自己按 {@code groupId} 分桶——这是「单一实例 + 作用域过滤」
 * 方案下持有群内状态的标准写法。</p>
 *
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
     * @param ctx     插件上下文
     * @param groupId 群号
     * @param userId  被戳次数计数的用户（发起戳的人）
     * @return 文案下标
     */
    private int countFor(YuniContext ctx, long groupId, long userId) {
        PokeState state = ctx.state(PokeState.class);
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
