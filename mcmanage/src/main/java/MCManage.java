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
 * <h2>关于中文标识符</h2>
 * <p>本模块（以及 {@code utils.Constant}）大量使用中文变量名与常量名，如
 * {@code 服务器名称}。这些名字直接对应命令参数名
 * （参数名必须是中文，用户才能写 {@code /mc 查询 生存服}）。</p>
 */
@Slf4j
public class MCManage implements YuniPlugin {

    /** 无子命令、无参数时查询本群唯一服务器所需的「根节点」处理器。 */
    private static final String CMD_ROOT = 安慕希;


    @Override
    public void apply(YuniContext ctx) {
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
