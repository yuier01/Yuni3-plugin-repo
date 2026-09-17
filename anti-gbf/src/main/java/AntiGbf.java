import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 反 GBF —— 检测到消息中含有骑空士陷阱链接时发出警告。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本类由旧插件系统迁移而来。对照关系：</p>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends PatternPlugin}</td><td>{@code implements YuniPlugin}</td></tr>
 *   <tr><td>{@code getDetector()} 返回 {@code PatternDetector}</td>
 *       <td>{@code ctx.onMessage(条件, 处理器)}——探测条件与执行入口同一个表达式</td></tr>
 *   <tr><td>{@code execute(YuniMessageEvent)} 用 {@code getChatSession().reply()}</td>
 *       <td>处理器第二参数 {@code reply} 直接可用</td></tr>
 *   <tr><td>{@code metadata.json} 描述插件</td><td>{@code metadata()} 方法描述，代码是唯一真相</td></tr>
 * </table>
 *
 * <p>行为完全一致：群聊消息中出现 {@code granbluefantasy.jp} 时引用回复一句警告。
 * 注意原实现的两个隐含语义在新系统中是<b>显式</b>的：</p>
 * <ul>
 *   <li>模式探测器只监听群聊（旧 {@code PatternDetector.listenAt()} 硬编码 GROUP）——
 *       新实现用 {@code ev.isGroup()} 条件表达；</li>
 *   <li>引用回复（旧 {@code ChatSession.reply}）——新实现用 {@code reply.quote()}。</li>
 * </ul>
 */
public class AntiGbf implements YuniPlugin {

    /** 触发警告的文本特征。 */
    private static final String TRAP_MARKER = "granbluefantasy.jp";

    /** 警告文案。 */
    private static final String WARNING = "大家不要信，这是骑空士的陷阱！";

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("骑空士陷阱粉碎机")
                .version("0.0.1")
                .description("警惕骑空士的陷阱")
                .author("Yuier me@yuier.com")
                .tip("检测到消息中含有 `granbluefantasy.jp` 文本，自动警告骑空士的陷阱")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        ctx.onMessage(
                // 探测条件：群聊 且 消息文本含有陷阱特征
                ev -> ev.isGroup() && ev.chain().containsText(TRAP_MARKER),
                // 执行入口：引用触发消息并回复警告
                (ev, reply) -> reply.quote().text(WARNING).send());
    }
}
