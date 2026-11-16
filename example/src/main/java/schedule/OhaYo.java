package schedule;

import com.yuier.yuni.core.util.CronExpressionBuilder;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 定时插件示例——每天早上向 bot 主人问好。
 *
 */
public class OhaYo implements YuniPlugin {

    /** 问候语。 */
    private static final String GREETING = "偶哈哟，欧尼酱！";


    @Override
    public void apply(YuniContext ctx) {
        // 每天 10:00 向 bot 主人发私聊问候
        ctx.schedule(
                CronExpressionBuilder.create().dailyAt(10, 0).build(),
                () -> ctx.getSender()
                        .privateChat(ctx.findService(com.yuier.yuni.core.model.bot.Bot.class).getMasterId())
                        .text(GREETING)
                        .send());
    }
}
