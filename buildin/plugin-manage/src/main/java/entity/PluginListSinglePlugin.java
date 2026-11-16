package entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 插件列表展示时的单个插件。
 *
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
