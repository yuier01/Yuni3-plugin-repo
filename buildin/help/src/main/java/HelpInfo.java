import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;

/**
 * 帮助信息——把插件资源目录下的帮助文本原样发回会话。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin} + {@code getDetector()}/{@code execute()} 两段式</td>
 *       <td>{@code Commands.on(ROOT, handler)} 就地绑定</td></tr>
 *   <tr><td>{@code PluginUtils.loadTextFromPluginFolder(file, this.getClass())}</td>
 *       <td>{@code ctx.getConfigLoader().loadText(file)}——不再需要传自己的 Class</td></tr>
 *   <tr><td>{@code eventContext.getChatSession().response(helpInfoStr)}</td>
 *       <td>{@code reply.quote().text(helpInfoStr).send()}</td></tr>
 * </table>
 *
 * <h2>为什么帮助文本仍然放在资源目录而不是元数据里</h2>
 * <p>本插件输出的是<b>面向用户的完整使用手册</b>（多行、含格式排版），
 * 而 {@link PluginMetadata#tips()} 是管理端展示的一句话提示。两者用途不同，
 * 因此 {@code help-info.txt} 继续作为独立资源文件存在——运维可以直接改文本，
 * 不需要重新打包。</p>
 */
public class HelpInfo implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD_HELP = "帮助";

    /** 帮助文本资源文件名。 */
    private static final String HELP_FILE = "help-info.txt";

    /** 命令树：无参数无子命令。 */
    private static final CommandNode ROOT = CommandNode.builder(CMD_HELP)
            .description("输出帮助信息")
            .build();


    @Override
    public PluginDeclaration getDeclaration() {
        // 内置插件：只有 bot 拥有者能启停
        return PluginDeclaration.of("HelpInfo").buildIn(true).build();
    }

    @Override
    public void apply(YuniContext ctx) {
        ctx.command(Commands.on(ROOT, (event, result, reply) ->
                reply.quote().text(ctx.getConfigLoader().loadText(HELP_FILE)).send()));
    }
}
