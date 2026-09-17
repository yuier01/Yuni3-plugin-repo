package entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件列表展示时的单个插件。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>旧字段是 {@code int index}——插件在框架里的<b>全局序号</b>。新系统取消了序号，
 * 改用稳定的插件标识 {@link #id}（形如 {@code module/PluginSimpleName}）。</p>
 *
 * <p>为什么必须改掉序号：序号是「加载顺序」的副产品，而热重载会改变加载顺序，
 * 于是每次重载后「3 号插件」可能变成完全不同的插件。现行系统为此专门写了
 * index 偏移修正逻辑（{@code PluginReloadProcessor}），是 bug 高发区。
 * 标识由「模块 + 插件类名」构成，只要模块不增删插件就恒定不变。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginListSinglePlugin {

    /** 插件标识，形如 {@code steam/MemberSteamStatus}。 */
    private String id;

    /** 插件显示名。 */
    private String name;

    /** 插件描述。 */
    private String desc;

    /** 在当前消息所在群是否生效。 */
    private boolean enabled;
}
