import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.util.CronExpressionBuilder;
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
 */
@Slf4j
public class DailyNews implements YuniPlugin {

    /** 早报接口地址。 */
    private static final String API_ZAOBAO = "https://api.2xb.cn/zaob";

    /** 日期格式，与接口返回的 {@code datatime} 对齐。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");


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
            response = ctx.getHttp().post(API_ZAOBAO).execute(DailyNewsResponse.class);
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

        List<BotGroupInfo> groups = ctx.getBot().getGroupList().orElse(List.of());
        ScopeQuery scope = ctx.getScope();
        int sent = 0;
        for (BotGroupInfo group : groups) {
            long groupId = group.getGroupId();
            if (!scope.isEnabledIn(groupId)) {
                continue;
            }
            ctx.getSender().group(groupId).image(response.getImageUrl()).send();
            sent++;
        }
        log.info("[每日早报] 已向 {} 个群发送 {}", sent, today);
    }
}
