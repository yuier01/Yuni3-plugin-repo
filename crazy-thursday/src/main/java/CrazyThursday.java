import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 疯狂星期四——每周四在随机时间点向群聊发送疯四文案。
 *
 * <h2>迁移说明（P5）：本类是该批次里最有价值的一个</h2>
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
 *       {@code RedisCache.REDIS_KEY} 的引用里，没有任何类型或接口表达它。</li>
 * </ol>
 *
 * <h2>迁移后的结构</h2>
 * <p>把 {@code StartAtThursday} 变成<b>子插件</b>——「周四重启后补发」在语义上
 * 就是疯狂星期四的一个组成部分，而不是一个独立的插件：</p>
 *
 * <pre>
 * CrazyThursday（唯一入口）
 * └── ctx.plugin(new StartAtThursday())    ← 子插件，随父卸载
 * </pre>
 *
 * <p>由此：</p>
 * <ul>
 *   <li><b>双向耦合消失</b>：父插件挂载子插件，生命周期单向；</li>
 *   <li><b>启停回调消失</b>：不需要 {@code enable}/{@code disable} 里互相调用——
 *       群粒度启停由 {@code ScopeFilter} 表达，对父子一视同仁；</li>
 *   <li><b>数据契约显式化</b>：两者共享的 {@link ScheduleCache} 成为一个真实类型。</li>
 * </ul>
 *
 * <h2>关于群粒度启停</h2>
 * <p>旧 {@code CrazyThursdayUtil} 在广播前逐个群查
 * {@code PluginEnableProcessor.isPluginEnabled(groupId, CrazyThursday.class)}——
 * 直接依赖框架内部 API。新实现改用
 * {@link com.yuier.yuni.plugin.api.ScopeQuery}（{@code ctx.scope()}），
 * 这是稳定的插件侧契约。</p>
 *
 * <h2>与 {@link StartAtThursday} 的分工</h2>
 * <p>本插件负责「正常启动」：算出今天的 4 个投放时间点，落 Redis，
 * 然后按时逐个等待并发送。若进程在周四当天<b>重启</b>，本插件的定时任务
 * 会等到下一个周四才触发——那正是 {@link StartAtThursday} 要补的缺口。</p>
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
    public PluginMetadata metadata() {
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
        // 挂载子插件：周四当天重启时的补发逻辑。随本插件一同卸载。
        ctx.plugin(new StartAtThursday());

        // 每周四 00:00 生成当天的时间点并落缓存，供补发插件复用
        ctx.schedule("0 0 0 ? * THU", () -> scheduleToday(ctx));

        // 每天 10:00 开始等待并发送（非周四时下面的判断会直接返回）
        ctx.schedule("0 0 10 * * ?", () -> runToday(ctx));
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
        ctx.cache().set(ScheduleCache.REDIS_KEY,
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
        ScheduleCache cache = ctx.cache()
                .get(ScheduleCache.REDIS_KEY, ScheduleCache.class)
                .orElse(null);
        List<Long> points;
        if (cache == null || !LocalDate.now().toString().equals(cache.getDate())) {
            // 缓存缺失或不属于今天：即时生成并写入
            points = chooseTimePoints(startOfTodayMillis());
            ctx.cache().set(ScheduleCache.REDIS_KEY,
                    new ScheduleCache(LocalDate.now().toString(), points));
        } else {
            points = cache.getTimePoints();
        }

        List<Long> waits = calculateWaits(points, System.currentTimeMillis());
        for (Long wait : waits) {
            // 等待前检查插件是否已被卸载：否则卸载后仍会继续发送
            if (ctx.disposed()) {
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
     * 向所有启用了本插件的群投放一条文案。
     *
     * <p>作为包的可见静态方法暴露，供子插件 {@link StartAtThursday} 在补发时复用
     * ——两个插件的投放语义（时间点、概率、群粒度过滤、文案选择）必须完全一致，
     * 复制一份实现是 drift 的温床。</p>
     *
     * @param ctx 插件上下文（由调用方所属的 Fiber 提供）
     */
    public static void broadcastOnce(YuniContext ctx) {
        // 概率控制：并非每个时间点都真的发送
        if (ThreadLocalRandom.current().nextFloat() >= SEND_PROBABILITY) {
            log.info("[疯狂星期四] 未命中概率，本次不发送");
            return;
        }

        String[] sentences = ctx.config().load(SENTENCE_FILE, String[].class);
        if (sentences == null || sentences.length == 0) {
            log.warn("[疯狂星期四] 文案配置为空，跳过本次投放");
            return;
        }
        String sentence = sentences[ThreadLocalRandom.current().nextInt(sentences.length)];

        // 只向启用了本插件的群发送——取代旧实现对 PluginEnableProcessor 的直接依赖
        com.yuier.yuni.plugin.api.ScopeQuery scope = ctx.scope();
        for (BotGroupInfo group : ctx.bot().getGroupList().orElse(List.of())) {
            Long groupId = group.getGroupId();
            if (!scope.isEnabledIn(groupId)) {
                continue;
            }
            ctx.sender().group(groupId).text(sentence).send();
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
        List<Long> points = new java.util.ArrayList<>();
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
        java.util.Set<Long> distinct = new java.util.HashSet<>();
        while (distinct.size() < count) {
            distinct.add(ThreadLocalRandom.current().nextLong(begin, end));
        }
        List<Long> result = new java.util.ArrayList<>(distinct);
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
        List<Long> waits = new java.util.ArrayList<>();
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
     * {@code PluginUtils.deserialize((String) RedisUtil.get(REDIS_KEY).orElse(null), RedisCache.class)}
     * ——两个插件各自持有一段对同一个键的反序列化代码，契约只存在于约定里。
     * 迁移后它是一个真实类型，由本类写入、由 {@link StartAtThursday} 读取。</p>
     */
    public static class ScheduleCache {

        /** Redis 键。 */
        public static final String REDIS_KEY = "plugin:crazy:thursday:key";

        /** 生成日期（{@code yyyy-MM-dd}），用于判断缓存是否属于今天。 */
        private String date;

        /** 当天的时间点。 */
        private List<Long> timePoints;

        /** 供 Jackson 反序列化使用。 */
        public ScheduleCache() {
            // 由 Jackson 填充字段
        }

        /**
         * 构造缓存对象。
         *
         * @param date       日期字符串
         * @param timePoints 时间点列表
         */
        public ScheduleCache(String date, List<Long> timePoints) {
            this.date = date;
            this.timePoints = timePoints;
        }

        /**
         * 取生成日期。
         *
         * @return 日期字符串
         */
        public String getDate() {
            return date;
        }

        /**
         * 设置生成日期。
         *
         * @param date 日期字符串
         */
        public void setDate(String date) {
            this.date = date;
        }

        /**
         * 取时间点列表。
         *
         * @return 时间点列表
         */
        public List<Long> getTimePoints() {
            return timePoints;
        }

        /**
         * 设置时间点列表。
         *
         * @param timePoints 时间点列表
         */
        public void setTimePoints(List<Long> timePoints) {
            this.timePoints = timePoints;
        }
    }
}
