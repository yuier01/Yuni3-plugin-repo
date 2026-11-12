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
 * 齁哦语编码/解码——与 {@link ChieruTrans} 同构，只是换了一张映射表。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>本类与 {@link ChieruTrans} 在旧实现里是两份近乎逐行相同的代码
 * （仅命令名、参数名、映射表字段与提示文案不同）——这是「同一份逻辑复制两份」
 * 的典型。迁移时保持两个插件独立（用户可见的命令不同、可分别启停），
 * 但把共同点收敛：</p>
 * <ul>
 *   <li>编解码算法共用 {@link TransferUtil}（旧实现已经这么做了）；</li>
 *   <li>映射表结构共用 {@link TransMap}（同一 json 文件里的 {@code hoo} 字段）；</li>
 *   <li>结构与校验逻辑各自明写，不强行抽基类——两个类的差异点（命令名、参数名、
 *       映射字段）全部是<b>常量</b>，抽基类只会把常量变成构造参数，
 *       可读性反而下降。</li>
 * </ul>
 *
 * <h2>与 {@link ChieruTrans} 相同的一处修正</h2>
 * <p>映射表在 {@code apply} 时读一次并缓存，取代旧实现「每次执行都重新读 json」。</p>
 */
public class HooTrans implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD = "齁哦";

    /** 子命令：编码。 */
    private static final String SUB_ENCODE = "转换";

    /** 子命令：解码。 */
    private static final String SUB_DECODE = "还原";

    /** 编码子命令的参数名。 */
    private static final String ARG_ENCODE = "raw_words_of_hoo";

    /** 解码子命令的参数名。 */
    private static final String ARG_DECODE = "hoo_words";

    /** 映射表文件名（位于插件资源目录，与切噜语共用同一个文件）。 */
    private static final String TRANS_MAP_FILE = "trans-map.json";


    @Override
    public void apply(YuniContext ctx) {
        TransMap transMap = ctx.getConfigLoader().load(TRANS_MAP_FILE, TransMap.class);
        List<String> hooMap = transMap.getHoo();
        if (hooMap == null || hooMap.size() < 16) {
            throw new IllegalStateException(
                    TRANS_MAP_FILE + " 的 hoo 映射表至少需要 16 个字符（0x0~0xF），实际 "
                            + (hooMap == null ? 0 : hooMap.size()));
        }

        CommandNode encode = Commands.on(
                CommandNode.builder(SUB_ENCODE)
                        .description("齁哦语编码")
                        .arg(ArgDef.required(ARG_ENCODE, "要编码的文本", CommandArgRequireType.PLAIN))
                        .build(),
                (event, reply) -> {
                    String raw = event.getCommandResult().getArg(ARG_ENCODE).asText();
                    reply.text(hooMap.get(0) + TransferUtil.encode(raw, hooMap)).send();
                });

        CommandNode decode = Commands.on(
                CommandNode.builder(SUB_DECODE)
                        .description("齁哦语解码")
                        .arg(ArgDef.required(ARG_DECODE, "要还原的齁哦语文本", CommandArgRequireType.PLAIN))
                        .build(),
                (event, reply) -> {
                    String encoded = event.getCommandResult().getArg(ARG_DECODE).asText();
                    String marker = hooMap.get(0);
                    if (!encoded.startsWith(marker)) {
                        reply.text("非法齁哦语文本，开头缺少标识符 " + marker).send();
                        return;
                    }
                    String body = encoded.substring(marker.length());
                    try {
                        reply.text(TransferUtil.decode(body, hooMap)).send();
                    } catch (IllegalArgumentException e) {
                        reply.text("非法齁哦语文本，含有未定义的字符").send();
                    }
                });

        ctx.command(CommandNode.builder(CMD)
                .description("齁哦语编码/解码")
                .child(encode)
                .child(decode)
                .build());
    }
}
