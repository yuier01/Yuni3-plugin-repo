import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.util.CronExpressionBuilder;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.ScopeQuery;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每日早报——每天 08:30 把当天的早报图片发到已启用本插件的群。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends ScheduledPlugin} +
 *       {@code cronExpression()} + {@code getAction()}</td>
 *       <td>{@code ctx.schedule(cron, task)}</td></tr>
 *   <tr><td>{@code PluginUtils.simplePost(url, null, C.class)}</td>
 *       <td>{@code ctx.http().post(url).execute(C.class)}</td></tr>
 *   <tr><td>{@code PluginUtils.loadJsonConfigFromPlugin(..., this.getClass())}</td>
 *       <td>{@code ctx.config().loadOrDefault(...)}</td></tr>
 *   <tr><td>{@code DBHelper.findBlackLists() / findWhiteLists()}
 *       ——插件自建的 {@code daily_news_group_enable} 表</td>
 *       <td>{@code ctx.scope().isEnabledIn(groupId)}——框架的群粒度启停</td></tr>
 *   <tr><td>{@code PluginUtils.sendGroupMessage(groupId, MessageChain)}</td>
 *       <td>{@code ctx.sender().group(groupId).image(url).send()}</td></tr>
 * </table>
 *
 * <h2>本批次中唯一一处「语义合并」（重要）</h2>
 * <p>旧插件自己维护了一张 {@code daily_news_group_enable} 表（{@code black} /
 * {@code white} 两列，配合 {@code daily_news_strategy.json} 里的
 * {@code "strategy": "blacklist"｜"whitelist"} 决定生效群）。</p>
 *
 * <p>这与框架的 <b>群粒度插件启停</b>（设计文档 §8）是<b>同一件事的两种实现</b>：
 * 前者是插件私有的、只有本插件认识的表；后者是框架级、管理端可见、对所有插件
 * 一致的能力。新系统统一到后者，因此本插件不再读写该表。</p>
 *
 * <h3>升级影响</h3>
 * <p>已部署环境中若在旧表里配置过例外群，需要在新系统的群粒度启停里重新配置一次
 * （或调用 {@code PluginRuntime} 的 scope 接口写入等价配置）。旧表不会被自动读取，
 * 也不会被删除——它不再被任何代码引用，需要时可手工清理。</p>
 * <p>向后兼容性上这是<b>有意的破坏</b>：保留两套群启停机制会让「为什么这个群收到
 * 早报但没收到疯狂星期四」变成一个需要读插件源码才能回答的问题。</p>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>接口返回 null 会 NPE</b>：旧实现直接 {@code dailyNewsResponse.getDatatime()}，
 *       请求失败或响应体为空时抛 {@code NullPointerException}。
 *       新实现先判空并降级为「本轮不发送」。</li>
 *   <li><b>图片地址为空也照发</b>：旧实现不校验 {@code imageUrl}。
 *       新实现检查后跳过。</li>
 * </ol>
 */
@Slf4j
public class DailyNews implements YuniPlugin {

    /** 早报接口地址。 */
    private static final String API_ZAOBAO = "https://api.2xb.cn/zaob";

    /** 日期格式，与接口返回的 {@code datatime} 对齐。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("每日早报")
                .version("0.0.1")
                .description("每天定时发送早报")
                .author("Yuier me@yuier.com")
                .tip("每天 08:30 向已启用本插件的群发送当日早报图片")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        String cron = CronExpressionBuilder.create().dailyAt(8, 30).build();
        ctx.schedule(cron, () -> sendTodayNews(ctx));
    }

    /**
     * 抓取并广播当日早报。
     *
     * @param ctx 插件上下文
     */
    private void sendTodayNews(YuniContext ctx) {
        DailyNewsResponse response;
        try {
            response = ctx.http().post(API_ZAOBAO).execute(DailyNewsResponse.class);
        } catch (RuntimeException e) {
            // 外部接口失败不应打挂定时任务——下一轮（明天）再试
            log.warn("[每日早报] 接口调用失败: {}", e.getMessage());
            return;
        }
        if (response == null) {
            log.warn("[每日早报] 接口返回空响应");
            return;
        }
        if (response.getImageUrl() == null || response.getImageUrl().isBlank()) {
            log.warn("[每日早报] 接口未返回图片地址, code={}, msg={}", response.getCode(), response.getMsg());
            return;
        }

        // 接口在当天日报尚未生成时会返回前一天的内容，此时不发送
        String today = LocalDate.now().format(DATE_FORMAT);
        if (!today.equals(response.getDatatime())) {
            log.info("[每日早报] 接口响应日期为 {}, 非本日日报，不发送", response.getDatatime());
            return;
        }

        List<BotGroupInfo> groups = ctx.bot().getGroupList().orElse(List.of());
        ScopeQuery scope = ctx.scope();
        int sent = 0;
        for (BotGroupInfo group : groups) {
            long groupId = group.getGroupId();
            // 群粒度启停：取代旧的 daily_news_group_enable 表 + strategy 配置
            if (!scope.isEnabledIn(groupId)) {
                continue;
            }
            ctx.sender().group(groupId).image(response.getImageUrl()).send();
            sent++;
        }
        log.info("[每日早报] 已向 {} 个群发送 {}", sent, today);
    }
}
