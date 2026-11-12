import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.core.enums.CommandArgRequireType;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import util.TransMap;
import util.TransferUtil;

import java.util.List;

/**
 * 切噜语编码/解码——把明文按 4bit 映射表转成「切噜语」，以及反向还原。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code execute()} 里两个 {@code if (hasChild(...))} 分派</td>
 *       <td>每个子命令的处理器经 {@code Commands.on(child, handler)}
 *           就地绑在它自己的节点上——分派链消失</td></tr>
 *   <tr><td>两个方法各自 {@code loadJsonConfigFromPlugin("trans-map.json", ...)}</td>
 *       <td>映射表在 {@code apply} 时读一次并缓存到局部变量</td></tr>
 *   <tr><td>{@code getChatSession().response(...)}</td><td>{@code reply.text(...).send()}</td></tr>
 * </table>
 *
 * <h2>关于「映射表读一次」</h2>
 * <p>旧实现<b>每次执行命令都重新读一遍 json 文件并反序列化</b>（两个方法各一次）。
 * 映射表是纯静态资源，插件生命周期内不会变化，因此在 {@code apply} 里读一次、
 * 用闭包捕获即可。这既减少 IO，也让「映射表读失败」在插件加载阶段就暴露——
 * 而不是等到用户发命令时才失败。</p>
 */
public class ChieruTrans implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD = "切噜";

    /** 子命令：编码。 */
    private static final String SUB_ENCODE = "转换";

    /** 子命令：解码。 */
    private static final String SUB_DECODE = "还原";

    /** 编码子命令的参数名。 */
    private static final String ARG_ENCODE = "raw_words_of_chieru";

    /** 解码子命令的参数名。 */
    private static final String ARG_DECODE = "chieru_words";

    /** 映射表文件名（位于插件资源目录）。 */
    private static final String TRANS_MAP_FILE = "trans-map.json";


    @Override
    public void apply(YuniContext ctx) {
        // 映射表是静态资源：加载时读一次，失败即让插件进入 FAILED（可观测）
        TransMap transMap = ctx.getConfigLoader().load(TRANS_MAP_FILE, TransMap.class);
        List<String> chieruMap = transMap.getChieru();
        if (chieruMap == null || chieruMap.size() < 16) {
            throw new IllegalStateException(
                    TRANS_MAP_FILE + " 的 chieru 映射表至少需要 16 个字符（0x0~0xF），实际 "
                            + (chieruMap == null ? 0 : chieruMap.size()));
        }

        // 每个子命令的处理器紧贴它自己的参数声明——取代旧的 if/else if 分派链
        CommandNode encode = Commands.on(
                CommandNode.builder(SUB_ENCODE)
                        .description("切噜语编码")
                        .arg(ArgDef.required(ARG_ENCODE, "要编码的文本", CommandArgRequireType.PLAIN))
                        .build(),
                (event, reply) -> {
                    String raw = event.getCommandResult().getArg(ARG_ENCODE).asText();
                    reply.text(chieruMap.get(0) + TransferUtil.encode(raw, chieruMap)).send();
                });

        CommandNode decode = Commands.on(
                CommandNode.builder(SUB_DECODE)
                        .description("切噜语解码")
                        .arg(ArgDef.required(ARG_DECODE, "要还原的切噜语文本", CommandArgRequireType.PLAIN))
                        .build(),
                (event, reply) -> {
                    String encoded = event.getCommandResult().getArg(ARG_DECODE).asText();
                    // 标识符校验保持旧行为：缺少前缀时给出明确提示而不是抛异常
                    String marker = chieruMap.get(0);
                    if (!encoded.startsWith(marker)) {
                        reply.text("非法切噜语文本，开头缺少标识符 " + marker).send();
                        return;
                    }
                    String body = encoded.substring(marker.length());
                    try {
                        reply.text(TransferUtil.decode(body, chieruMap)).send();
                    } catch (IllegalArgumentException e) {
                        // 解码失败是用户输入问题，回一条提示而不是让框架走异常兜底
                        reply.text("非法切噜语文本，含有未定义的字符").send();
                    }
                });

        ctx.command(CommandNode.builder(CMD)
                .description("切噜语编码/解码")
                .child(encode)
                .child(decode)
                .build());
    }
}
