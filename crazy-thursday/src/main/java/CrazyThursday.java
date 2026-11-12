import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import com.yuier.yuni.plugin.api.ScopeQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * 疯狂星期四——每周四在随机时间点向群聊发送疯四文案。
 *
 * <h2>历史：旧实现为什么是两个插件</h2>
 * <p>旧实现由两个插件类与两个工具类组成，且它们之间有一条
 * <b>双向耦合</b>——这是设计文档 §1.5 记录的病灶：</p>
 *
 * <pre>
 * 旧 CrazyThursday（定时型）                     旧 StartAtThursday（即时型）
 * ├─ getAction() 生成时间点、循环 sleep 发送      ├─ getAction() 读回时间点、循环 sleep 发送
 * ├─ enable()  -> PluginEnableProcessor          ├─ enable()  -> PluginEnableProcessor
 * │              .enablePlugin(event,             │              .enablePlugin(event,
 * │                  StartAtThursday.class)      │                  CrazyThursday.class)
 * └─ disable() -> .disablePlugin(event,           └─ disable() -> .disablePlugin(event,
 *                 CrazyThursday.class)  ← 写的是自己！              StartAtThursday.class)
 * </pre>
 *
 * <p>两个问题：</p>
 * <ol>
 *   <li><b>双向耦合</b>：两个插件在启停回调里互相操纵对方；
 *       {@code CrazyThursday.disable} 里写的还是 {@code CrazyThursday.class}（自己），
 *       与 {@code enable} 不对称——这是没有明确依赖概念时的必然产物。</li>
 *   <li><b>隐式耦合</b>：两者通过 Redis 键交换数据，但这一契约只存在于两处
 *       {@code RedisYuniCache.REDIS_KEY} 的引用里，没有任何类型或接口表达它。</li>
 * </ol>
 *
 * <h2>现在：一个插件就够了</h2>
 * <p>旧系统把「周四 00:00 建定时任务」与「周四当天启动时按缓存补发」拆成
 * 定时型与即时型两个插件，因为单个插件无法同时表达这两件事，只能靠互相
 * {@code enable}/{@code disable} 衔接。</p>
 *
 * <p>新系统里两者都是 {@code ctx} 的普通能力：{@code ctx.schedule(...)} 注册
 * 定时任务，{@code ctx.runNow(...)} 在加载时立即执行一次。因此补发逻辑就是
 * 本类的一个私有方法 {@link #recover(YuniContext)}——不需要第二个插件，
 * 也不需要子插件或启停回调。两者共享的 {@link ScheduleCache} 仍是真实类型。</p>
 *
 * <h2>关于群粒度启停</h2>
 * <p>旧 {@code CrazyThursdayUtil} 在广播前逐个群查
 * {@code PluginEnableProcessor.isPluginEnabled(groupId, CrazyThursday.class)}——
 * 直接依赖框架内部 API。新实现改用
 * {@link com.yuier.yuni.plugin.api.ScopeQuery}（{@code ctx.getScope()}），
 * 这是稳定的插件侧契约。</p>
 *
 * <h2>两条执行路径</h2>
 * <ul>
 *   <li><b>正常路径</b>：周四 00:00 生成当天时间点落缓存；10:00 起按时间点
 *       逐个等待并发送（{@link #runToday(YuniContext)}）；</li>
 *   <li><b>重启补发</b>：进程在周四当天启动时，读缓存里尚未过期的时间点，
 *       继续完成剩余投放（{@link #recover(YuniContext)}）。</li>
 * </ul>
 */
@Slf4j
public class CrazyThursday implements YuniPlugin {

    /** 命中概率：并非每个时间点都真的发送。 */
    private static final float SEND_PROBABILITY = 0.85f;

    /** 每个投放窗口内生成的时间点数量。 */
    private static final int POINTS_PER_WINDOW = 2;

    /** 上午投放窗口的起始小时（含）。 */
    private static final int MORNING_START_HOUR = 10;

    /** 上午投放窗口的结束小时（不含）。 */
    private static final int MORNING_END_HOUR = 13;

    /** 下午投放窗口的起始小时（含）。 */
    private static final int AFTERNOON_START_HOUR = 15;

    /** 下午投放窗口的结束小时（不含）。 */
    private static final int AFTERNOON_END_HOUR = 18;

    /** 文案配置文件名（位于插件资源目录）。 */
    private static final String SENTENCE_FILE = "sentences.json";

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("疯狂星期四")
                .version("0.0.1")
                .description("每周四随机发送疯四文案")
                .author("Yuier me@yuier.com")
                .tip("每周四在 10:00~13:00 与 15:00~18:00 两段时间内各随机投放两次")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // 每周四 00:00 生成当天的时间点并落缓存
        ctx.schedule("0 0 0 ? * THU", () -> scheduleToday(ctx));

        // 每天 10:00 开始等待并发送（非周四时下面的判断会直接返回）
        ctx.schedule("0 0 10 * * ?", () -> runToday(ctx));

        // 周四当天启动（含重启）时，按缓存补发尚未过期的时间点。
        // 加载时立即执行一次；非周四或无缓存时直接返回。
        ctx.runNow(() -> recover(ctx));
    }

    /**
     * 生成今天的时间点并写入缓存。
     *
     * @param ctx 插件上下文
     */
    private void scheduleToday(YuniContext ctx) {
        if (!LocalDate.now().getDayOfWeek().equals(java.time.DayOfWeek.THURSDAY)) {
            return;
        }
        List<Long> points = chooseTimePoints(startOfTodayMillis());
        ctx.getCache().set(ScheduleCache.REDIS_KEY,
                new ScheduleCache(LocalDate.now().toString(), points));
        log.info("[疯狂星期四] 今日投放时间点已生成: {}", points);
    }

    /**
     * 等待并发送今天的文案。
     *
     * <p>时间点由 {@link #scheduleToday} 预先生成；若缓存缺失（例如插件刚启用
     * 而当天已过 00:00），这里即时生成一份，避免整周空转。</p>
     *
     * @param ctx 插件上下文
     */
    private void runToday(YuniContext ctx) {
        if (!LocalDate.now().getDayOfWeek().equals(java.time.DayOfWeek.THURSDAY)) {
            return;
        }
        ScheduleCache cache = ctx.getCache()
                .get(ScheduleCache.REDIS_KEY, ScheduleCache.class)
                .orElse(null);
        List<Long> points;
        if (cache == null || !LocalDate.now().toString().equals(cache.getDate())) {
            // 缓存缺失或不属于今天：即时生成并写入
            points = chooseTimePoints(startOfTodayMillis());
            ctx.getCache().set(ScheduleCache.REDIS_KEY,
                    new ScheduleCache(LocalDate.now().toString(), points));
        } else {
            points = cache.getTimePoints();
        }

        List<Long> waits = calculateWaits(points, System.currentTimeMillis());
        for (Long wait : waits) {
            // 等待前检查插件是否已被卸载：否则卸载后仍会继续发送
            if (ctx.isDisposed()) {
                log.info("[疯狂星期四] 插件已卸载，中止本次投放");
                return;
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("[疯狂星期四] 等待被中断，中止本次投放");
                    return;
                }
            }
            broadcast(ctx);
        }
    }

    /**
     * 周四当天启动（含重启）时，按缓存补发尚未过期的时间点。
     *
     * <p>与 {@link #runToday(YuniContext)} 的区别：本方法<b>不生成</b>时间点，
     * 只消费当天 00:00 已经落下的缓存。缓存不存在说明当天没有生成过
     * （例如 00:00 的定时任务未执行），此时无事可补。</p>
     *
     * @param ctx 插件上下文
     */
    private void recover(YuniContext ctx) {
        if (!LocalDate.now().getDayOfWeek().equals(java.time.DayOfWeek.THURSDAY)) {
            return;
        }

        ScheduleCache cache = ctx.getCache()
                .get(ScheduleCache.REDIS_KEY, ScheduleCache.class)
                .orElse(null);
        if (cache == null) {
            log.info("[疯狂星期四] 未找到当天的时间点缓存，跳过补发");
            return;
        }

        List<Long> waits = calculateWaits(cache.getTimePoints(), System.currentTimeMillis());
        if (waits.isEmpty()) {
            log.info("[疯狂星期四] 今天的时间点均已过期，无需补发");
            return;
        }

        log.info("[疯狂星期四] 周四启动，继续完成 {} 个时间点的投放", waits.size());
        for (Long wait : waits) {
            if (ctx.isDisposed()) {
                log.info("[疯狂星期四] 插件已卸载，中止补发");
                return;
            }
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("[疯狂星期四] 等待被中断，中止补发");
                    return;
                }
            }
            broadcastOnce(ctx);
        }
    }

    /**
     * 向所有启用了本插件的群投放一条文案。
     *
     * <p>正常路径与重启补发共用这一份实现——投放语义（时间点、概率、
     * 群粒度过滤、文案选择）必须完全一致，复制一份实现是 drift 的温床。</p>
     *
     * @param ctx 插件上下文（由调用方所属的 Fiber 提供）
     */
    public static void broadcastOnce(YuniContext ctx) {
        // 概率控制：并非每个时间点都真的发送
        if (ThreadLocalRandom.current().nextFloat() >= SEND_PROBABILITY) {
            log.info("[疯狂星期四] 未命中概率，本次不发送");
            return;
        }

        String[] sentences = ctx.getConfig().load(SENTENCE_FILE, String[].class);
        if (sentences == null || sentences.length == 0) {
            log.warn("[疯狂星期四] 文案配置为空，跳过本次投放");
            return;
        }
        String sentence = sentences[ThreadLocalRandom.current().nextInt(sentences.length)];

        // 只向启用了本插件的群发送——取代旧实现对 PluginEnableProcessor 的直接依赖
        ScopeQuery scope = ctx.getScope();
        for (BotGroupInfo group : ctx.getBot().getGroupList().orElse(List.of())) {
            Long groupId = group.getGroupId();
            if (!scope.isEnabledIn(groupId)) {
                continue;
            }
            ctx.getSender().group(groupId).text(sentence).send();
        }
    }

    /**
     * 投放一条文案（{@link #broadcastOnce} 的实例内别名）。
     *
     * @param ctx 插件上下文
     */
    private void broadcast(YuniContext ctx) {
        broadcastOnce(ctx);
    }

    /**
     * 生成今天的投放时间点。
     *
     * @param startOfTodayMillis 今天 00:00 的毫秒时间戳
     * @return 升序排列的时间点
     */
    static List<Long> chooseTimePoints(long startOfTodayMillis) {
        List<Long> points = new ArrayList<>();
        points.addAll(randomPointsInWindow(
                startOfTodayMillis, MORNING_START_HOUR, MORNING_END_HOUR, POINTS_PER_WINDOW));
        points.addAll(randomPointsInWindow(
                startOfTodayMillis, AFTERNOON_START_HOUR, AFTERNOON_END_HOUR, POINTS_PER_WINDOW));
        points.sort(Long::compareTo);
        return points;
    }

    /**
     * 在指定小时区间内生成互不重复的随机时间点。
     *
     * @param startOfTodayMillis 今天 00:00 的毫秒时间戳
     * @param startHour          起始小时（含）
     * @param endHour            结束小时（不含）
     * @param count              需要的数量
     * @return 升序排列的时间点
     */
    private static List<Long> randomPointsInWindow(long startOfTodayMillis,
                                                   int startHour,
                                                   int endHour,
                                                   int count) {
        final long oneHour = 3_600_000L;
        long begin = startOfTodayMillis + startHour * oneHour;
        long end = startOfTodayMillis + endHour * oneHour;
        if (end <= begin) {
            throw new IllegalArgumentException("投放窗口的结束时间必须晚于开始时间");
        }
        if (end - begin < count) {
            throw new IllegalArgumentException("投放窗口长度必须不小于需要的时间点数量");
        }

        // 用 Set 去重：随机取值可能重复，需保证实际数量
        Set<Long> distinct = new HashSet<>();
        while (distinct.size() < count) {
            distinct.add(ThreadLocalRandom.current().nextLong(begin, end));
        }
        List<Long> result = new ArrayList<>(distinct);
        result.sort(Long::compareTo);
        return result;
    }

    /**
     * 计算从「开始时间」到各时间点的等待间隔。
     *
     * @param points    时间点列表（升序）
     * @param startTime 开始时间（毫秒）
     * @return 第一个元素是「等到首个未过期时间点」的间隔，其后依次是两个时间点之间的间隔；
     *         若所有时间点都已过期则返回空列表
     */
    static List<Long> calculateWaits(List<Long> points, long startTime) {
        List<Long> waits = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return waits;
        }
        // 找到第一个尚未过期的时间点
        int startIndex = -1;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i) >= startTime) {
                startIndex = i;
                break;
            }
        }
        if (startIndex < 0) {
            // 今天的时间点都已过期（例如周四傍晚才启动）
            return waits;
        }
        waits.add(points.get(startIndex) - startTime);
        for (int i = startIndex + 1; i < points.size(); i++) {
            waits.add(points.get(i) - points.get(i - 1));
        }
        return waits;
    }

    /**
     * 取今天 00:00 的毫秒时间戳（系统默认时区）。
     *
     * @return 毫秒时间戳
     */
    static long startOfTodayMillis() {
        return LocalDate.now()
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    /**
     * 两个插件共享的调度缓存对象。
     *
     * <p>旧实现里这份契约只是一句
     * {@code PluginUtils.deserialize((String) RedisUtil.get(REDIS_KEY).orElse(null), RedisYuniCache.class)}
     * ——两个插件各自持有一段对同一个键的反序列化代码，契约只存在于约定里。
     * 迁移后它是一个真实类型，由本类写入、由补发路径读取。</p>
     */
    /*
     * 两个构造器分别服务 Jackson（无参）与代码内构建（全参），
     * 读写器由 @Data 生成。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScheduleCache {

        /** Redis 键。 */
        public static final String REDIS_KEY = "plugin:crazy:thursday:key";

        /** 生成日期（{@code yyyy-MM-dd}），用于判断缓存是否属于今天。 */
        private String date;

        /** 当天的时间点。 */
        private List<Long> timePoints;
    }
}
