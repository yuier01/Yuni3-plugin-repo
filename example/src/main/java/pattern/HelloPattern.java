package pattern;

import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 模式插件示例——群聊消息中出现「你好」时回复问候。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本类是「模式插件」的迁移样板，也是设计文档 §5「探测条件与执行入口形式统一」
 * 的直接实证。对照关系：</p>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends PatternPlugin}</td><td>{@code implements YuniPlugin}</td></tr>
 *   <tr><td>{@code getDetector()} 返回 {@code new PatternDetector(chain -> ...)}<br>
 *       ——<b>探测条件在这一个方法里</b></td>
 *       <td rowspan="2">{@code ctx.onMessage(条件, 处理器)}<br>
 *       ——<b>两者在同一个表达式里</b></td></tr>
 *   <tr><td>{@code execute(YuniMessageEvent)} 是另一个方法<br>
 *       ——<b>执行入口在几十行之外</b></td></tr>
 *   <tr><td>{@code PluginUtils.getBotNickName()}</td>
 *       <td>{@code ctx.service(Bot.class).getNickName()}</td></tr>
 * </table>
 *
 * <p>旧实现读代码时必须在 {@code getDetector()} 与 {@code execute()} 之间来回跳，
 * 才能拼出「什么条件触发什么行为」。迁移后这一点在一行里读完：</p>
 * <pre>{@code
 * ctx.onMessage(ev -> ev.isGroup() && ev.getChain().containsText("你好"),
 *               (ev, reply) -> reply.text("你好，我是 " + botName + " !").send());
 * }</pre>
 *
 * <h2>关于群聊限定</h2>
 * <p>旧 {@code PatternDetector.listenAt()} 硬编码返回 {@code MessageType.GROUP}，
 * 因此模式插件只对群聊生效。迁移后该限制由条件里的 {@code ev.isGroup()} 显式表达
 * ——若要改成私聊也生效，删掉这个条件即可，不再是框架里改不到的硬编码。</p>
 */
public class HelloPattern implements YuniPlugin {

    /** 触发词。 */
    private static final String TRIGGER = "你好";

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("规则插件示例")
                .version("0.0.1")
                .description("验证模式匹配可用性")
                .author("Yuier me@yuier.com")
                .tip("在群聊内发送带有“你好”的消息，bot 会响应 “你好，我是 {bot 昵称} !”")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // bot 昵称随插件生命周期不变，因此在 apply 时取一次即可
        String botName = ctx.service(com.yuier.yuni.core.model.bot.Bot.class).getNickName();

        ctx.onMessage(
                // 探测条件：群聊 且 消息含触发词
                ev -> ev.isGroup() && ev.getChain().containsText(TRIGGER),
                // 执行入口
                (ev, reply) -> reply.text("你好，我是 " + botName + " !").send());
    }
}
