package command;

import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 命令插件示例——群聊发送 {@code /测试} 时回复 {@code Hello Command!}。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本类是「命令插件」的迁移样板，也是设计文档 §6 那套方案的实证。
 * 对照关系：</p>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin}</td><td>{@code implements YuniPlugin}</td></tr>
 *   <tr><td>{@code getDetector()} 返回 {@code new CommandDetector(ROOT)}</td>
 *       <td>{@code ctx.command(...)} 直接注册命令树</td></tr>
 *   <tr><td>{@code execute(YuniMessageEvent)} 是另一个方法</td>
 *       <td>处理器经 {@code Commands.on(node, handler)} 绑在节点上</td></tr>
 *   <tr><td>{@code eventContext.getChatSession().response(...)}</td>
 *       <td>{@code reply.text(...).send()}</td></tr>
 * </table>
 *
 * <h2>命令树与处理器就地绑定</h2>
 * <p>因为本命令只有一个节点、没有参数，处理器直接绑在根节点上
 * （见 {@link Commands#on}）。多子命令的复杂命令树请参考
 * {@code plugin-manage} 模块的写法——那里每个子命令的处理器都紧贴它自己的
 * 参数声明，而旧实现需要在一段 {@code if/else if} 分派链里来回跳。</p>
 */
public class HelloCommand implements YuniPlugin {

    /** 命令树：单节点，无参数、无子命令。 */
    private static final CommandNode ROOT = CommandNode.builder("测试")
            .description("验证指令系统可用性")
            .build();

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("指令插件示例")
                .version("0.0.1")
                .description("验证指令系统可用性")
                .author("Yuier me@yuier.com")
                .tip("在群聊内发送“/测试”，bot 会响应 “Hello Command!”")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // 处理器绑在节点上：声明与实现在同一处
        ctx.command(Commands.on(ROOT, (event, reply) -> reply.text("Hello Command!").send()));
    }
}
