package entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 插件详情展示页的数据。
 *
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PluginDetail {

    /** 插件标识，形如 {@code steam/MemberSteamStatus}。 */
    private String id;

    /** 插件显示名。 */
    private String name;

    /** 插件描述。 */
    private String description;

    /** 使用提示。 */
    private List<String> tips;

    /** 作者。 */
    private String author;

    /** 版本。 */
    private String version;

    /** 在当前消息所在群是否生效。 */
    private Boolean enabled;

    /** 所属 jar 标识。 */
    private String artifactId;

    /** 运行状态的中文名，如「运行中」。 */
    private String stateName;

    /**
     * 运行状态的枚举名小写形式，如 {@code active}。
     *
     * <p>供模板拼 CSS 类名（{@code state-active}）。单独给一个字段而不是在模板里
     * 对中文名做映射，是因为模板里的映射逻辑既难读又难测。</p>
     */
    private String stateKey;

    /** 加载失败原因摘要；仅状态为「加载失败」时有值。 */
    private String failureSummary;

    /** 未满足的依赖服务类型名；仅状态为「等待依赖」时非空。 */
    private List<String> missingServices;

    /** 依赖的服务类型名。 */
    private List<String> requiredServices;

    /** 项目主页。 */
    private String homepage;

    /** 代码仓库。 */
    private String repository;

    /** 图标。 */
    private String icon;
}
