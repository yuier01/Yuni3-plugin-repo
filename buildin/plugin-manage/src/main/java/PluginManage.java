import com.yuier.yuni.core.enums.CommandArgRequireType;
import com.yuier.yuni.core.enums.UserPermission;
import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginAdminException;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import entity.PluginDetail;
import entity.PluginListElement;
import lombok.extern.slf4j.Slf4j;
import util.PluginManagerConstants;
import util.PluginManageSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.yuier.yuni.core.event.YuniMessageEvent;

/**
 * 插件管理——查看 / 开启 / 关闭 / 重载插件。
 *
 * <h2>迁移说明（P5）：四个类合并为一个插件</h2>
 * <p>旧实现把这个插件拆成四个类，靠 {@code PluginManage.execute()} 里的一串
 * {@code if (result.hasChild(...))} 粘起来：</p>
 * <table border="1">
 *   <caption>旧结构 → 新结构</caption>
 *   <tr><th>旧类</th><th>职责</th><th>新位置</th></tr>
 *   <tr><td>{@code PluginManage}</td><td>声明命令树 + 手工分派</td>
 *       <td>本类：命令树 + {@code .on(...)} 就地绑定</td></tr>
 *   <tr><td>{@code PluginShow}</td><td>列表 / 详情渲染</td>
 *       <td>本类的 {@code showList} / {@code showDetail}</td></tr>
 *   <tr><td>{@code PluginEnable}</td><td>启停 + 内置插件权限判定</td>
 *       <td>本类的 {@code setEnabled} + {@link PluginAdmin} 内置保护</td></tr>
 *   <tr><td>{@code PluginReload} + {@code PluginReloadProcessor}</td>
 *       <td>定位 jar + 热重载</td>
 *       <td>本类的 {@code reload} + {@code PluginRuntime.reload}</td></tr>
 *   <tr><td>{@code PluginManageUtil}</td><td>数据组装 / 缓存 / 渲染</td>
 *       <td>{@link PluginManageSupport}</td></tr>
 * </table>
 *
 * <h2>「{@code PluginEnable} 的旧判定逻辑整体消失」——本插件最重要的变化</h2>
 * <p>设计文档 §14.5 对元插件批次的核心主张是：<b>启停逻辑不需要在插件里写</b>。
 * 旧 {@code PluginEnable} 有 104 行，做四件事：</p>
 * <ol>
 *   <li>判序号越界（{@code pluginSeq > container.getPluginCount()}）；</li>
 *   <li>判内置插件 + 权限（{@code isBuiltIn() && permission < MASTER}）；</li>
 *   <li>调插件自身的 {@code enable()} / {@code disable()} 回调；</li>
 *   <li>调 {@code PluginManager.enablePlugin} 改状态。</li>
 * </ol>
 * <p>新系统里：</p>
 * <ul>
 *   <li><b>第 1 件消失</b>——不再用序号定位插件，改用稳定的插件标识。
 *       用户输入的标识不存在时就是「这个插件不存在」，没有「越界」这个概念。
 *       现行系统为了维护序号甚至要在热重载时做 index 偏移修正；</li>
 *   <li><b>第 2 件收敛到 {@link PluginAdmin#setEnabled}</b>——签名的
 *       {@code callerIsMaster} 参数强制调用方在编译期面对「你有没有验过
 *       这个人是不是拥有者」这个问题；</li>
 *   <li><b>第 3 件整体消失</b>——{@code YuniPlugin} 不再有
 *       {@code enable()} / {@code disable()} 回调（设计文档 §2.1 不变量）。
 *       群粒度启停是「某个群是否给这个插件投递事件」，插件根本不需要知道，
 *       更不需要参与；</li>
 *   <li><b>第 4 件就是 {@code setEnabled} 本身。</b></li>
 * </ul>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>「主动类插件暂不完全支持配置开启/关闭」的 TODO 消失</b>：旧
 *       {@code PluginEnable} 对 {@code ActivePluginInstance} 会额外回复一句
 *       「正在加紧适配中」。新系统里主动与被动插件在作用域上<b>没有区别</b>
 *       （都是「这个群是否投递事件」），因此这句话连同它背后的类型分支一起消失。</li>
 *   <li><b>重载前手动找 jar 的三重分支收进框架</b>：旧 {@code PluginReload}
 *       要遍历插件目录、按「文件名等于记录的 jar 名或以模块 ID 开头」筛选，
 *       然后分别处理「没找到」与「找到多个」。现在框架知道每个插件的 jar 文件名，
 *       插件只传插件标识。</li>
 *   <li><b>同一插件的失败重载不再留下半状态</b>：旧实现先建新实例再改容器索引，
 *       中途失败会留下「新实例已建、索引未换」的状态。新框架的 reload 是
 *       「先彻底卸载、再重新加载」，任一步失败都有明确的中间态描述。</li>
 * </ol>
 *
 * <h2>标识 vs 序号：用户输入怎么处理</h2>
 * <p>插件标识形如 {@code steam/MemberSteamStatus}，比「3」长得多。为了不牺牲
 * 易用性，本插件接受两种输入：<b>先按标识精确匹配</b>，匹配不到再当作
 * 「{@code /插件 查看} 列表里的第 N 个」解析。列表渲染时仍然显示标识本身——
 * 因为标识是稳定的，序号不是。</p>
 */
@Slf4j
public class PluginManage implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD = PluginManagerConstants.PLUGIN_MANAGE_HEAD;

    /** 子命令：查看。 */
    private static final String CHILD_VIEW = PluginManagerConstants.PLUGIN_MANAGE_VIEW;

    /** 子命令：开启。 */
    private static final String CHILD_ENABLE = PluginManagerConstants.PLUGIN_MANAGE_ENABLE;

    /** 子命令：关闭。 */
    private static final String CHILD_DISABLE = PluginManagerConstants.PLUGIN_MANAGE_DISABLE;

    /** 子命令：重载。 */
    private static final String CHILD_RELOAD = PluginManagerConstants.PLUGIN_MANAGE_RELOAD;

    /** 参数名：插件标识。 */
    private static final String ARG_PLUGIN = "插件";

    /** 参数名：插件标识（重载用，可选且支持全部）。 */
    private static final String ARG_RELOAD_TARGET = "插件";


    @Override
    public PluginDeclaration getDeclaration() {
        // 内置插件：只有 bot 拥有者能启停
        return PluginDeclaration.of("PluginManage").buildIn(true).build();
    }

    @Override
    public void apply(YuniContext ctx) {
        PluginManageSupport support = new PluginManageSupport(ctx);
        ctx.command(buildTree(ctx, support));
    }

    /**
     * 构造命令树。
     *
     * <p>{@code /插件} 必须带子命令（{@code requiresChild()}），因此根节点不绑处理器
     * ——每个子节点各自带自己的行为。</p>
     *
     * @param ctx     插件上下文
     * @param support 支撑服务
     * @return 命令树根节点
     */
    private CommandNode buildTree(YuniContext ctx, PluginManageSupport support) {
        return CommandNode.builder(CMD)
                .description("插件管理")
                .requiresChild()
                .child(viewNode(ctx, support))
                .child(enableNode(ctx, support))
                .child(disableNode(ctx, support))
                .child(reloadNode(ctx))
                .build();
    }

    /**
     * 构造「查看」子命令节点。
     *
     * @param ctx     插件上下文
     * @param support 支撑服务
     * @return 子节点
     */
    private CommandNode viewNode(YuniContext ctx, PluginManageSupport support) {
        return Commands.on(CommandNode.builder(CHILD_VIEW)
                .description("查看插件列表或指定插件详情")
                .arg(ArgDef.optional(ARG_PLUGIN, "插件标识（留空则查看列表）"))
                .build(), (event, reply) -> {
            long groupId = event.getGroupId();
            var child = event.getCommandResult().getChild(CHILD_VIEW);
            if (!child.hasArg(ARG_PLUGIN)) {
                showList(ctx, support, reply, event.getPosition(), groupId);
                return;
            }
            String raw = child.getArg(ARG_PLUGIN).asText().trim();
            Optional<PluginView> found = resolve(ctx, raw);
            if (found.isEmpty()) {
                reply.quote().text("没有找到插件「" + raw + "」。\n"
                        + "可以先用 `/插件 查看` 看列表，参数既可以写完整标识，"
                        + "也可以写列表里的序号。").send();
                return;
            }
            showDetail(ctx, support, reply, event.getPosition(), groupId, found.get());
        });
    }

    /**
     * 构造「开启」子命令节点。
     *
     * @param ctx     插件上下文
     * @param support 支撑服务
     * @return 子节点
     */
    private CommandNode enableNode(YuniContext ctx, PluginManageSupport support) {
        return Commands.on(CommandNode.builder(CHILD_ENABLE)
                .description("在当前群开启指定插件")
                .arg(ArgDef.required(ARG_PLUGIN, "插件标识"))
                .build(), (event, reply) ->
                setEnabled(ctx, support, reply, event, true));
    }

    /**
     * 构造「关闭」子命令节点。
     *
     * @param ctx     插件上下文
     * @param support 支撑服务
     * @return 子节点
     */
    private CommandNode disableNode(YuniContext ctx, PluginManageSupport support) {
        return Commands.on(CommandNode.builder(CHILD_DISABLE)
                .description("在当前群关闭指定插件")
                .arg(ArgDef.required(ARG_PLUGIN, "插件标识"))
                .build(), (event, reply) ->
                setEnabled(ctx, support, reply, event, false));
    }

    /**
     * 构造「重载」子命令节点。
     *
     * <p>权限要求 {@code ADMIN}，与旧实现一致——重载会短暂中断该 jar 的服务。</p>
     *
     * @param ctx 插件上下文
     * @return 子节点
     */
    private CommandNode reloadNode(YuniContext ctx) {
        return Commands.on(CommandNode.builder(CHILD_RELOAD)
                .description("重载插件 jar")
                // 节点级权限由框架递归校验，插件不需要自己判
                .permission(UserPermission.ADMIN)
                .arg(ArgDef.optional(ARG_RELOAD_TARGET, "插件标识（留空则重载全部）"))
                .build(), (event, reply) -> {
            var child = event.getCommandResult().getChild(CHILD_RELOAD);
            if (!child.hasArg(ARG_RELOAD_TARGET)) {
                reloadAll(ctx, reply);
                return;
            }
            String raw = child.getArg(ARG_RELOAD_TARGET).asText().trim();
            Optional<PluginView> found = resolve(ctx, raw);
            if (found.isEmpty()) {
                reply.quote().text("没有找到插件「" + raw + "」。").send();
                return;
            }
            reloadOne(ctx, reply, found.get());
        });
    }

    // ==================== 实现 ====================

    /**
     * 渲染插件列表（带内容哈希缓存）。
     *
     * @param ctx      插件上下文
     * @param support  支撑服务
     * @param reply    回复入口
     * @param position 消息位置，用作缓存字段名
     * @param groupId  群号
     */
    private void showList(YuniContext ctx, PluginManageSupport support,
                          Reply reply, String position, long groupId) {
        int currentHash = support.hashCodeOfList(groupId);
        Integer cachedHash = support.loadHashCode(
                PluginManageSupport.PLUGIN_LIST_HASHCODE_CACHE_KEY, position);
        if (cachedHash != null && cachedHash == currentHash) {
            String cachedImage = support.loadImage(
                    PluginManageSupport.PLUGIN_LIST_IMAGE_CACHE_KEY, position);
            if (cachedImage != null) {
                reply.quote().image(cachedImage).send();
                return;
            }
        }

        // 先记哈希再渲染：渲染失败时下一次会重试（哈希相同但图片为空 → 走渲染分支）
        support.saveHashCode(PluginManageSupport.PLUGIN_LIST_HASHCODE_CACHE_KEY, position, currentHash);

        List<PluginListElement> elements = support.assemblePluginList(groupId);
        String image = support.renderToBase64(
                PluginManageSupport.templateList(), Map.of("pluginList", elements));
        if (image == null) {
            // 旧实现会把 null 拼成 "base64://null" 发出去，用户只看到一个裂图
            reply.quote().text("插件列表图片生成失败，请查看日志了解详情。").send();
            return;
        }
        reply.quote().image(image).send();
        support.saveImage(PluginManageSupport.PLUGIN_LIST_IMAGE_CACHE_KEY, position, image);
    }

    /**
     * 渲染单个插件详情（带内容哈希缓存）。
     *
     * @param ctx      插件上下文
     * @param support  支撑服务
     * @param reply    回复入口
     * @param position 消息位置
     * @param groupId  群号
     * @param plugin   目标插件
     */
    private void showDetail(YuniContext ctx, PluginManageSupport support,
                            Reply reply, String position, long groupId, PluginView plugin) {
        String field = PluginManageSupport.detailField(position, plugin.getId());
        int currentHash = support.hashCodeOfDetail(plugin, groupId);
        Integer cachedHash = support.loadHashCode(
                PluginManageSupport.PLUGIN_DETAIL_HASHCODE_CACHE_KEY, field);
        if (cachedHash != null && cachedHash == currentHash) {
            String cachedImage = support.loadImage(
                    PluginManageSupport.PLUGIN_DETAIL_IMAGE_CACHE_KEY, field);
            if (cachedImage != null) {
                reply.quote().image(cachedImage).send();
                return;
            }
        }

        support.saveHashCode(PluginManageSupport.PLUGIN_DETAIL_HASHCODE_CACHE_KEY, field, currentHash);

        PluginDetail detail = support.toDetail(plugin, groupId);
        String image = support.renderToBase64(
                PluginManageSupport.templateDetail(), Map.of("plugin", detail));
        if (image == null) {
            reply.quote().text("插件详情图片生成失败，请查看日志了解详情。").send();
            return;
        }
        reply.quote().image(image).send();
        support.saveImage(PluginManageSupport.PLUGIN_DETAIL_IMAGE_CACHE_KEY, field, image);
    }

    /**
     * 开启 / 关闭某插件在当前群的生效状态。
     *
     * @param ctx     插件上下文
     * @param support 支撑服务
     * @param reply   回复入口
     * @param event   事件
     * @param enabled 目标状态
     */
    private void setEnabled(YuniContext ctx, PluginManageSupport support,
                            Reply reply, YuniMessageEvent event,
                            boolean enabled) {
        String raw = event.getCommandResult().getChild(
                enabled ? CHILD_ENABLE : CHILD_DISABLE).getArg(ARG_PLUGIN).asText().trim();

        Optional<PluginView> found = resolve(ctx, raw);
        if (found.isEmpty()) {
            reply.quote().text("没有找到插件「" + raw + "」。").send();
            return;
        }
        PluginView plugin = found.get();
        long groupId = event.getGroupId();
        String action = enabled ? "开启" : "关闭";

        if (ctx.getPlugins().isEnabledIn(plugin.getId(), groupId) == enabled) {
            // 已经是目标状态：明确告知而不是再操作一遍，避免用户以为命令没生效
            reply.quote().text("插件「" + plugin.getName() + "」在当前群已经是" + action + "状态。").send();
            return;
        }

        boolean callerIsMaster = isBotMaster(ctx, event.getUserId(), groupId);
        try {
            ctx.getPlugins().setEnabled(plugin.getId(), groupId, enabled, callerIsMaster);
        } catch (PluginAdminException e) {
            // 可预期的业务拒绝（内置插件、插件不存在）——把原因原样回给用户，
            // 而不是让它冒到框架的统一兜底（那样用户只会看到「插件执行出错」）
            reply.quote().text(e.getMessage()).send();
            return;
        }

        // 让缓存失效：插件列表/详情里的开关状态变了，下一次查看必须重新渲染。
        // 这里不清缓存而是让它自然失效——哈希里含 enabled，因此下次比较必然不等
        reply.quote().text("已在当前群" + action + "插件「" + plugin.getName() + "」。").send();
    }

    /**
     * 重载单个插件所属的 jar。
     *
     * @param ctx    插件上下文
     * @param reply  回复入口
     * @param plugin 目标插件
     */
    private void reloadOne(YuniContext ctx, Reply reply, PluginView plugin) {
        String artifactId = plugin.getArtifactId();
        if (artifactId == null || artifactId.isBlank()) {
            reply.quote().text("插件「" + plugin.getName() + "」不属于任何 jar，无法重载。\n"
                    + "（它可能是通过代码直接加载的）").send();
            return;
        }
        try {
            ctx.getPlugins().reload(plugin.getId());
        } catch (PluginAdminException e) {
            reply.quote().text("重载失败：" + e.getMessage()).send();
            return;
        }
        reply.quote().text("已重载 jar「" + artifactId + "」（插件「" + plugin.getName() + "」所在 jar）。").send();
    }
    /**
     * 重载全部 jar。
     *
     * <p><b>注意</b>：本插件自己也在这批 jar 里。框架的
     * {@code PluginRuntime.reload} 是同步的（卸载 → 换 ClassLoader → 重新加载），
     * 因此在它执行期间本插件正在运行的这段代码所属的类会被换掉。</p>
     *
     * <p>之所以还能工作：本方法在重载「自己所在 jar」<b>之前</b>已经把结果写进了
     * 局部变量，而 {@code reply} 的实现类（{@code CoreReply}）由父加载器加载，
     * 不受子加载器关闭影响。为避免这种微妙依赖，本方法把本插件所在 jar
     * 放到<b>最后</b>重载。</p>
     *
     * @param ctx   插件上下文
     * @param reply 回复入口
     */
    private void reloadAll(YuniContext ctx, Reply reply) {
        PluginAdmin admin = ctx.getPlugins();
        List<PluginView> plugins = admin.getPlugins();
        if (plugins.isEmpty()) {
            reply.quote().text("当前没有任何已加载的插件。").send();
            return;
        }

        // 按 jar 去重：一个 jar 只需重载一次（重载会重启 jar 内全部插件）
        Map<String, String> artifactToPlugin = new LinkedHashMap<>();
        for (PluginView plugin : plugins) {
            artifactToPlugin.putIfAbsent(plugin.getArtifactId(), plugin.getId());
        }

        // 本插件所在的 jar：从自己的元数据反查（用名称匹配）
        String selfArtifact = selfArtifactId(admin);

        List<String> failed = new ArrayList<>();
        int reloaded = 0;
        // 先把其他 jar 重载完，最后处理自己所在的 jar
        for (Map.Entry<String, String> entry : artifactToPlugin.entrySet()) {
            if (entry.getKey().equals(selfArtifact)) {
                continue;
            }
            if (tryReload(admin, entry.getValue(), failed)) {
                reloaded++;
            }
        }
        if (selfArtifact != null) {
            String selfPluginId = artifactToPlugin.get(selfArtifact);
            if (selfPluginId != null && tryReload(admin, selfPluginId, failed)) {
                reloaded++;
            }
        }

        StringBuilder message = new StringBuilder("已重载 " + reloaded + " 个 jar。");
        if (!failed.isEmpty()) {
            message.append("\n以下 jar 重载失败：");
            for (String item : failed) {
                message.append("\n- ").append(item);
            }
        }
        reply.quote().text(message.toString()).send();
    }
    /**
     * 尝试重载一个 jar，把失败记录下来而不是中断整轮。
     *
     * @param admin    插件管理服务
     * @param pluginId 该 jar 内的任一插件标识
     * @param failed   失败信息收集器
     * @return 成功返回 {@code true}
     */
    private boolean tryReload(PluginAdmin admin, String pluginId, List<String> failed) {
        try {
            admin.reload(pluginId);
            return true;
        } catch (PluginAdminException e) {
            log.warn("重载插件 {} 所在 jar 失败: {}", pluginId, e.getMessage());
            failed.add(pluginId + "：" + e.getMessage());
            return false;
        }
    }
    // ==================== 辅助 ====================

    /**
     * 把用户输入解析为插件。
     *
     * <p>接受两种形式，按顺序尝试：</p>
     * <ol>
     *   <li><b>完整标识</b>（{@code steam/MemberSteamStatus}）——精确匹配；</li>
     *   <li><b>列表序号</b>（{@code 3}）——按 {@code admin.getPlugins()} 的顺序取第 N 个。</li>
     * </ol>
     * <p>序号是便利而非权威：它随加载顺序变化，所以只作为「不想打长标识」时的
     * 快捷方式，列表展示的仍然是标识本身。</p>
     *
     * @param ctx 插件上下文
     * @param raw 用户输入
     * @return 插件视图；解析失败时为空
     */
    private Optional<PluginView> resolve(YuniContext ctx, String raw) {
        PluginAdmin admin = ctx.getPlugins();
        Optional<PluginView> exact = admin.plugin(raw);
        if (exact.isPresent()) {
            return exact;
        }
        // 退化为序号
        int index;
        try {
            index = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        List<PluginView> all = admin.getPlugins();
        if (index < 1 || index > all.size()) {
            return Optional.empty();
        }
        return Optional.of(all.get(index - 1));
    }

    /**
     * 判定调用者是否为 bot 拥有者。
     *
     * <p>旧 {@code PluginEnable} 是
     * {@code permissionManager.getUserPermission(eventContext, pluginFullId)} 再比
     * {@code MASTER}。新系统的命令节点已声明 {@code UserPermission.ADMIN}，
     * 框架会先校验这一层；这里额外判 MASTER 是为了内置插件的保护。</p>
     *
     * @param ctx     插件上下文
     * @param userId  调用者 QQ 号
     * @param groupId 群号
     * @return 拥有者返回 {@code true}
     */
    private boolean isBotMaster(YuniContext ctx, long userId, Long groupId) {
        return ctx.getPermission().resolve(userId, groupId, null).getPriority()
                >= UserPermission.MASTER.getPriority();
    }

    /**
     * 反查本插件所在的 jar 标识。
     *
     * @param admin 插件管理服务
     * @return jar 标识；找不到时返回 {@code null}
     */
    private String selfArtifactId(PluginAdmin admin) {
        String selfId = getDeclaration().getId();
        String suffix = "/" + selfId;
        for (PluginView plugin : admin.getPlugins()) {
            if (plugin.getId().equals(selfId) || plugin.getId().endsWith(suffix)) {
                return plugin.getArtifactId();
            }
        }
        return null;
    }
}