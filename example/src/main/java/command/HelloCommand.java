package command;

import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 命令插件示例——群聊发送 {@code /测试} 时回复 {@code Hello Command!}。
 *
 * <h2>命令树与处理器就地绑定</h2>
 * <p>因为本命令只有一个节点、没有参数，处理器直接绑在根节点上
 * （见 {@link Commands#on}）。多子命令的复杂命令树请参考
 * {@code plugin-manage} 模块的写法——那里每个子命令的处理器都紧贴它自己的
 * 参数声明。</p>
 */
public class HelloCommand implements YuniPlugin {

    /** 命令树：单节点，无参数、无子命令。 */
    private static final CommandNode ROOT = CommandNode.builder("测试")
            .description("验证指令系统可用性")
            .build();


    @Override
    public void apply(YuniContext ctx) {
        // 处理器绑在节点上：声明与实现在同一处
        ctx.command(Commands.on(ROOT, (event, result, reply) -> reply.text("Hello Command!").send()));
    }
}
