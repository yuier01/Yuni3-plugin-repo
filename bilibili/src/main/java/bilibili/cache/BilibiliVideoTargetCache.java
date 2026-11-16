package bilibili.cache;

import bilibili.parser.BilibiliVideoTarget;
import com.yuier.yuni.core.event.YuniMessageEvent;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 消息事件与提取结果之间的短期内存缓存。
 *
 * <p>消息插件框架把「是否命中」和「命中后做什么」拆成两个回调，二者之间没有
 * 参数传递通道。本缓存用消息 ID 把 {@code messageContainsBv} 提取到的结果交给
 * {@code parseBilibiliVideo}，避免在处理器里重复解析整条消息。</p>
 *
 * <p>缓存有三重约束：</p>
 * <ul>
 *   <li>处理器取用后立即删除，正常路径不会长期占用内存；</li>
 *   <li>超过 TTL 的条目视为未命中；</li>
 *   <li>容量上限通过 {@link LinkedHashMap#removeEldestEntry} 兜底，防止
 *       条件命中但处理器未执行时无限增长。</li>
 * </ul>
 */
public final class BilibiliVideoTargetCache {

    /** 缓存条目存活时间。消息处理器通常紧随条件判断执行，无需太长。 */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 最大缓存条目数。 */
    private static final int MAX_ENTRIES = 256;

    /** 线程安全的有界 LRU 缓存。 */
    private final Map<Key, Entry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    /**
     * 写入一条提取结果。
     *
     * <p>消息 ID 缺失时无法建立稳定的关联键，此时不缓存；处理器会退回自行解析。</p>
     *
     * @param event  消息事件
     * @param target 提取结果
     */
    public void put(YuniMessageEvent event, BilibiliVideoTarget target) {
        if (event == null || target == null) {
            return;
        }
        Key key = Key.from(event);
        if (key == null) {
            return;
        }
        cache.put(key, new Entry(target, System.nanoTime() + TTL.toNanos()));
    }

    /**
     * 取出并删除一条提取结果。
     *
     * @param event 消息事件
     * @return 未过期且存在的提取结果；否则为空
     */
    public Optional<BilibiliVideoTarget> take(YuniMessageEvent event) {
        if (event == null) {
            return Optional.empty();
        }
        Key key = Key.from(event);
        if (key == null) {
            return Optional.empty();
        }

        Entry entry = cache.remove(key);
        if (entry == null || entry.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(entry.target());
    }

    /**
     * 缓存键：消息 ID + 会话信息。
     *
     * @param messageId 消息 ID
     * @param groupId   群号；私聊为 {@code null}
     * @param userId    发送者 QQ 号
     */
    private record Key(long messageId, Long groupId, Long userId) {

        /**
         * 从事件构造缓存键。
         *
         * @param event 消息事件
         * @return 缓存键；消息 ID 缺失时返回 {@code null}
         */
        private static Key from(YuniMessageEvent event) {
            Long messageId = event.getMessageId();
            if (messageId == null) {
                return null;
            }
            return new Key(messageId, event.getGroupId(), event.getUserId());
        }
    }

    /**
     * 缓存值及其过期时间。
     *
     * @param target        提取结果
     * @param expiresAtNanos 过期时刻（{@link System#nanoTime()} 基准）
     */
    private record Entry(BilibiliVideoTarget target, long expiresAtNanos) {

        /**
         * 是否已过期。
         *
         * @return 已过期返回 {@code true}
         */
        private boolean isExpired() {
            return System.nanoTime() > expiresAtNanos;
        }
    }
}
