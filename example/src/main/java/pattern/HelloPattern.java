package pattern;

import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 模式插件示例——群聊消息中出现「你好」时回复问候。
 *
 * <h2>关于群聊限定</h2>
 * <p>本示例只处理群聊，该限制由条件里的 {@code ev.isGroup()} 显式表达；
 * 若要改成私聊也生效，删掉这个条件即可。</p>
 */
public class HelloPattern implements YuniPlugin {

    /** 触发词。 */
    private static final String TRIGGER = "你好";


    @Override
    public void apply(YuniContext ctx) {
        // bot 昵称随插件生命周期不变，因此在 apply 时取一次即可
        String botName = ctx.findService(com.yuier.yuni.core.model.bot.Bot.class).getNickName();

        ctx.onMessage(
                // 探测条件：群聊 且 消息含触发词
                ev -> ev.isGroup() && ev.getChain().containsText(TRIGGER),
                // 执行入口
                (ev, reply) -> reply.text("你好，我是 " + botName + " !").send());
    }
}
