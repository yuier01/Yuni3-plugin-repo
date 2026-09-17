package util;

/**
 * 插件管理命令的名字常量。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>旧版本还定义了四个 {@code PLUGIN_MANAGE_*_SEQ = "pluginSeq"} 常量，
 * 它们是各子命令的参数名，含义是「插件序号」。新系统不再用序号定位插件
 * （序号会随加载顺序与热重载漂移），参数名统一为
 * {@code PluginManage.ARG_PLUGIN}（值就是「插件」），因此这些常量被删除。</p>
 *
 * <p>保留本类而不是把常量内联进 {@code PluginManage}，是为了让命令名在一处
 * 集中可见——将来若要做命令名国际化或重命名，这里是唯一的改动点。</p>
 */
public final class PluginManagerConstants {

    /** 插件管理命令名，同时也是根命令。 */
    public static final String PLUGIN_MANAGE_HEAD = "插件";

    /** 子命令：查看插件列表 / 详情。 */
    public static final String PLUGIN_MANAGE_VIEW = "查看";

    /** 子命令：在当前群开启插件。 */
    public static final String PLUGIN_MANAGE_ENABLE = "开启";

    /** 子命令：在当前群关闭插件。 */
    public static final String PLUGIN_MANAGE_DISABLE = "关闭";

    /** 子命令：重载插件模块。 */
    public static final String PLUGIN_MANAGE_RELOAD = "重载";

    /** 工具类不可实例化。 */
    private PluginManagerConstants() {
    }
}
