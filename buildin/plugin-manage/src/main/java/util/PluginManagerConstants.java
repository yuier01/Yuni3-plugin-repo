package util;

/**
 * 插件管理命令的名字常量。
 *
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
