import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 反 GBF —— 检测到消息中含有 granbluefantasy.jp 链接时发出警告。
 *
 */
public class AntiGbf implements YuniPlugin {

    /** 触发警告的文本特征。 */
    private static final String TRAP_MARKER = "granbluefantasy.jp";

    /** 警告文案。 */
    private static final String WARNING = "大家不要信，这是骑空士的陷阱！";


    @Override
    public void apply(YuniContext ctx) {
        ctx.onMessage(
                // 探测条件：群聊 且 消息文本含有陷阱特征
                ev -> ev.isGroup() && ev.getChain().containsText(TRAP_MARKER),
                // 执行入口：引用触发消息并回复警告
                (ev, reply) -> reply.quote().text(WARNING).send());
    }
}
