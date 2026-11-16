import com.yuier.yuni.core.constants.SystemConstants;
import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.core.event.matched.CommandResult;
import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.data.CrudRepository;
import db.GroupMcServer;
import lombok.extern.slf4j.Slf4j;
import utils.AddrCheckResult;
import utils.MCManageUtils;
import utils.McServerPinger;

import java.util.List;

import static utils.Constant.修改地址;
import static utils.Constant.修改名称;
import static utils.Constant.删除;
import static utils.Constant.安慕希;
import static utils.Constant.添加;
import static utils.Constant.服务器名称;
import static utils.Constant.服务器地址;
import static utils.Constant.服务器新名称;

/**
 * MC 服务器管理——查询状态、添加、删除、改名、改地址。
 *
 * <h2>迁移说明（P5）：三个类合并为一个</h2>
 * <p>旧实现把一件事拆在三个类里，靠 {@code MCManage.execute()} 里的一串
 * {@code if (commandResult.hasChild(...))} 粘起来：</p>
 * <table border="1">
 *   <caption>旧结构 → 新结构</caption>
 *   <tr><th>旧类</th><th>职责</th><th>新位置</th></tr>
 *   <tr><td>{@code MCManage extends CommandPlugin}</td>
 *       <td>声明命令树 + 手工分派</td>
 *       <td>本类：命令树 + {@code .on(...)} 就地绑定</td></tr>
 *   <tr><td>{@code MCServer}</td>
 *       <td>添加 / 删除 / 改名的静态方法</td>
 *       <td>本类的 {@code addServer} 等方法</td></tr>
 *   <tr><td>{@code MCStatus}</td>
 *       <td>查询状态的静态方法</td>
 *       <td>本类的 {@code queryStatus}</td></tr>
 * </table>
 *
 * <p>这不只是文件数从 3 变成 1：旧结构里「命令节点的定义」与「该节点的行为」
 * 分居两个文件，靠字符串常量（{@code 添加} / {@code 删除}）在运行期关联——
 * 改一个节点名要同时改 {@code Constant}、节点定义、以及 {@code execute()} 里的
 * 字符串判断。新结构里三者就在同一个 {@code Commands.on(node, handler)}
 * 表达式内。</p>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>根节点处理器是 {@code else} 兜底而非显式绑定</b>：旧
 *       {@code execute()} 的最后分支 {@code else { MCStatus.mcServerStatus(...) }}
 *       意味着「任何未识别的子命令都去查状态」。新实现把状态查询显式绑在
 *       <b>根节点</b>上——分发器取「匹配到的最深节点」的处理器，
 *       未匹配子命令时自然落在根节点，语义等价但意图明确。</li>
 *   <li><b>添加服务器时不校验地址是否已被本群添加</b>：旧实现只查名称重复。
 *       同名不同址的检查保留原状（名称是用户可见的主键），但把「名称查重」
 *       与「地址合法性校验」的顺序调成先查重再校验，避免对明显会失败的重名请求
 *       还去做一次网络解析。</li>
 *   <li><b>改地址时不校验新地址合法性</b>：旧 {@code verifyMCServerAddr} 直接
 *       写入，用户可以把它改成 {@code localhost} 或非法格式，之后每次查询都只拿到
 *       「地址格式不合法」。新实现走与添加相同的
 *       {@link MCManageUtils#mcServerAddrValid} 校验。</li>
 *   <li><b>{@code buildServerNameList} 用 {@code i + 1} 拼接</b>：整数与字符串
 *       相加依赖隐式转换，本类改为 {@code StringBuilder.append(int)} 链，
 *       由调用方 {@code strip()} 去掉末尾换行。</li>
 * </ol>
 *
 * <h2>关于中文标识符</h2>
 * <p>本模块（以及 {@code utils.Constant}）大量使用中文变量名与常量名，如
 * {@code 服务器名称}。这是原作者的风格，且这些名字直接对应命令参数名
 * （参数名必须是中文，用户才能写 {@code /mc 查询 生存服}）。迁移时<b>保留</b>
 * 这些名字与 {@code Constant} 中的常量，只把「静态方法 + 手工分派」的结构
 * 改为「节点处理器」。</p>
 */
@Slf4j
public class MCManage implements YuniPlugin {

    /** 无子命令、无参数时查询本群唯一服务器所需的「根节点」处理器。 */
    private static final String CMD_ROOT = 安慕希;


    @Override
    public void apply(YuniContext ctx) {
        // 建表 + 注册实体：旧实现走 PluginUtils.registerEntity(GroupMcServer.class)
        ctx.getDb().registerEntity(GroupMcServer.class);
        ctx.command(buildTree(ctx));
    }

    /**
     * 构造命令树。
     *
     * <p>结构：{@code /mc [服务器名称] (添加|删除|修改名称|修改地址) ...}。
     * 根节点带一个可选参数（用于「只带名称查询状态」），同时带四个子命令。</p>
     *
     * @param ctx 插件上下文
     * @return 命令树根节点
     */
    private CommandNode buildTree(YuniContext ctx) {
        return Commands.on(CommandNode.builder(CMD_ROOT)
                .description("MC 服务器管理")
                .arg(ArgDef.optional(服务器名称, "MC 服务器名称"))
                .child(addNode(ctx))
                .child(deleteNode(ctx))
                .child(renameNode(ctx))
                .child(reAddressNode(ctx))
                .build(),
                // 根节点处理器 = 状态查询。取代旧 execute() 末尾的 else 兜底分支
                (event, result, reply) -> queryStatus(ctx, event, result, reply));
    }

    /**
     * 构造「添加」子命令节点。
     *
     * @param ctx 插件上下文
     * @return 子节点
     */
    private CommandNode addNode(YuniContext ctx) {
        return Commands.on(CommandNode.builder(添加)
                .description("添加 MC 服务器")
                .arg(ArgDef.required(服务器名称, "MC 服务器名称"))
                .arg(ArgDef.required(服务器地址, "服务器地址"))
                .build(), (event, result, reply) -> {
            long groupId = event.getGroupId();
            String serverName = result.getArg(服务器名称).asText();
            String serverAddr = result.getArg(服务器地址).asText();

            if (findByName(ctx, groupId, serverName) != null) {
                reply.quote().text("当前群组已添加同名服务器，请重新选择服务器名称。").send();
                return;
            }
            AddrCheckResult addrCheck = MCManageUtils.mcServerAddrValid(serverAddr);
            if (!addrCheck.isSuccess()) {
                reply.quote().text(addrCheck.getMessage()).send();
                return;
            }

            GroupMcServer record = new GroupMcServer();
            record.setGroupId(groupId);
            record.setServerName(serverName);
            record.setServerAddr(serverAddr);
            repo(ctx).save(record);

            reply.quote().text("成功添加服务器！当前服务器状态: \n"
                    + McServerPinger.buildMcServerStatusStr(serverAddr)).send();
        });
    }

    /**
     * 构造「删除」子命令节点。
     *
     * @param ctx 插件上下文
     * @return 子节点
     */
    private CommandNode deleteNode(YuniContext ctx) {
        return Commands.on(CommandNode.builder(删除)
                .description("删除 MC 服务器")
                .arg(ArgDef.required(服务器名称, "MC 服务器名称"))
                .build(), (event, result, reply) -> {
            long groupId = event.getGroupId();
            String serverName = result.getArg(服务器名称).asText();

            if (findByName(ctx, groupId, serverName) == null) {
                reply.quote().text(notFound(serverName)).send();
                return;
            }
            ctx.getDb().createDeleteBuilder(GroupMcServer.class)
                    .where("groupId", groupId)
                    .where("serverName", serverName)
                    .execute();
            reply.quote().text("已删除名称为【" + serverName + "】的服务器。").send();
        });
    }

    /**
     * 构造「修改名称」子命令节点。
     *
     * @param ctx 插件上下文
     * @return 子节点
     */
    private CommandNode renameNode(YuniContext ctx) {
        return Commands.on(CommandNode.builder(修改名称)
                .description("修改 MC 服务器名称")
                .arg(ArgDef.required(服务器名称, "MC 服务器旧名称"))
                .arg(ArgDef.required(服务器新名称, "MC 服务器新名称"))
                .build(), (event, result, reply) -> {
            long groupId = event.getGroupId();
            String serverName = result.getArg(服务器名称).asText();
            String newServerName = result.getArg(服务器新名称).asText();

            GroupMcServer record = findByName(ctx, groupId, serverName);
            if (record == null) {
                reply.quote().text(notFound(serverName)).send();
                return;
            }
            if (findByName(ctx, groupId, newServerName) != null) {
                reply.quote().text("当前群组下已添加名称为【" + newServerName + "】的服务器，请重新选择服务器名称。").send();
                return;
            }

            // 按主键更新「按旧名称查到的那条记录」——旧实现注释里也特意强调了这一点
            record.setServerName(newServerName);
            repo(ctx).save(record);
            reply.quote().text("已修改名称为【" + serverName + "】的服务器至新名称:【" + newServerName + "】。").send();
        });
    }

    /**
     * 构造「修改地址」子命令节点。
     *
     * @param ctx 插件上下文
     * @return 子节点
     */
    private CommandNode reAddressNode(YuniContext ctx) {
        return Commands.on(CommandNode.builder(修改地址)
                .description("修改 MC 服务器地址")
                .arg(ArgDef.required(服务器名称, "MC 服务器旧名称"))
                .arg(ArgDef.required(服务器地址, "MC 服务器地址"))
                .build(), (event, result, reply) -> {
            long groupId = event.getGroupId();
            String serverName = result.getArg(服务器名称).asText();
            String newServerAddr = result.getArg(服务器地址).asText();

            GroupMcServer record = findByName(ctx, groupId, serverName);
            if (record == null) {
                reply.quote().text(notFound(serverName)).send();
                return;
            }
            // 旧实现不校验新地址，用户可以把服务器改成 localhost 或非法格式
            AddrCheckResult addrCheck = MCManageUtils.mcServerAddrValid(newServerAddr);
            if (!addrCheck.isSuccess()) {
                reply.quote().text(addrCheck.getMessage()).send();
                return;
            }

            record.setServerAddr(newServerAddr);
            repo(ctx).save(record);
            reply.quote().text("已修改名称为【" + serverName + "】的服务器至新地址: " + newServerAddr + ".").send();
        });
    }

    /**
     * 查询服务器状态。
     *
     * <p>三种情形与旧 {@code MCStatus.mcServerStatus} 一致：
     * 未带名称 + 本群有多个服务器 → 列出名称让用户选；
     * 未带名称 + 本群只有一个 → 直接查它；
     * 带了名称 → 查指定服务器。</p>
     *
     * @param ctx   插件上下文
     * @param event 事件
     * @param reply 回复入口
     */
    private void queryStatus(YuniContext ctx, YuniMessageEvent event, CommandResult result, Reply reply) {
        long groupId = event.getGroupId();

        if (!result.hasArg(服务器名称)) {
            List<GroupMcServer> servers = repo(ctx).findByField("groupId", groupId);
            if (servers.size() > 1) {
                reply.quote().text("当前群组下绑定多个 MC 服务器，请明确要查询的服务器名称: \n"
                        + buildServerNameList(servers).strip()).send();
            } else if (servers.isEmpty()) {
                reply.quote().text("当前群组下未添加服务器，请先添加服务器。").send();
            } else {
                reply.quote().text(McServerPinger.buildMcServerStatusStr(
                        servers.get(SystemConstants.FIRST_INDEX).getServerAddr())).send();
            }
            return;
        }

        String serverName = result.getArg(服务器名称).asText();
        GroupMcServer record = findByName(ctx, groupId, serverName);
        if (record == null) {
            reply.quote().text(notFound(serverName)).send();
            return;
        }
        reply.quote().text(McServerPinger.buildMcServerStatusStr(record.getServerAddr())).send();
    }

    // ==================== 数据访问辅助 ====================

    /**
     * 取本模块实体的 CRUD 仓库。
     *
     * @param ctx 插件上下文
     * @return 仓库
     */
    private static CrudRepository<GroupMcServer> repo(YuniContext ctx) {
        return ctx.getDb().getRepository(GroupMcServer.class);
    }

    /**
     * 按「群号 + 服务器名称」查一条记录。
     *
     * <p>取代旧代码里重复了四次的
     * {@code PluginUtils.query(GroupMcServer.class).where("groupId", g).where("serverName", n).list()}
     * 加 {@code FIRST_INDEX} 取值。抽成一个方法后，「名称在群内唯一」
     * 这条隐含约束只在一处表达。</p>
     *
     * @param ctx        插件上下文
     * @param groupId    群号
     * @param serverName 服务器名称
     * @return 记录；不存在时返回 {@code null}
     */
    private static GroupMcServer findByName(YuniContext ctx, long groupId, String serverName) {
        List<GroupMcServer> found = ctx.getDb().createQueryBuilder(GroupMcServer.class)
                .where("groupId", groupId)
                .where("serverName", serverName)
                .list();
        return found.isEmpty() ? null : found.get(SystemConstants.FIRST_INDEX);
    }

    /**
     * 组装「本群没有这个服务器」的统一提示。
     *
     * @param serverName 服务器名称
     * @return 提示文本
     */
    private static String notFound(String serverName) {
        return "当前群组下未添加名称为【" + serverName + "】的服务器，请确认服务器名称。";
    }

    /**
     * 列出服务器名称与地址。
     *
     * @param servers 服务器记录
     * @return 多行文本，末尾带换行（调用方 {@code strip()}）
     */
    private static String buildServerNameList(List<GroupMcServer> servers) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < servers.size(); i++) {
            GroupMcServer server = servers.get(i);
            result.append(i + 1).append(". ")
                    .append(server.getServerName()).append(" ")
                    .append(server.getServerAddr()).append("\n");
        }
        return result.toString();
    }
}
