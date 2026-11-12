package entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 插件详情展示页的数据。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>相比旧版本有三处变化，都是为了回答「插件现在到底怎么样了」：</p>
 * <ol>
 *   <li>{@code index}（全局序号）→ {@link #id}（稳定标识）+ {@link #moduleName}
 *       （所属模块）——序号在热重载后会漂移，见
 *       {@link PluginListSinglePlugin} 的说明；</li>
 *   <li>新增 {@link #stateName} / {@link #stateKey}——现行系统的插件详情
 *       <b>完全看不到插件状态</b>，于是「插件在列表里但没反应」无法诊断。
 *       新系统把这个信息摆在详情页最显眼的位置；</li>
 *   <li>新增 {@link #failureSummary} 与 {@link #missingServices}——分别是
 *       「加载失败的原因」与「在等哪个服务」，也就是
 *       「我的插件为什么没启动」这个问题的两个答案。</li>
 * </ol>
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
