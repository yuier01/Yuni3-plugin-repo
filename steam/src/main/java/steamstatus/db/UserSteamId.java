package steamstatus.db;

import com.yuier.yuni.plugin.data.annotation.Id;
import com.yuier.yuni.plugin.data.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记录用户 QQ 号与 steam id 的绑定关系。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>旧实现由三部分组成：本类（纯 POJO）+ {@code UserSteamIdDao}
 * （JDBI SqlObject 接口，手写 {@code CREATE TABLE} 与全部 SQL）
 * + {@code DBHelper}（每次调用都 {@code Jdbi.create(...)} 新建连接并
 * {@code installPlugin(new SqlObjectPlugin())}）。</p>
 * <p>本类改为标注 {@code @Table} / {@code @Id}，DDL 与增删改查由
 * {@code TableSchema} + {@code CrudRepository} 从实体类推导：
 * 类名 {@code UserSteamId} → 表名 {@code user_steam_id}，
 * 字段 {@code userId} → 列 {@code user_id}，与原表结构完全一致，
 * 因此<b>历史数据无需迁移</b>。</p>
 *
 * <p>Lombok 的 {@code @Data} / {@code @NoArgsConstructor} 保留——本类字段与
 * 访问器是纯数据载体，且 {@code TableSchema} 通过反射读字段、
 * {@code CrudRepositoryImpl} 通过 setter 写字段。</p>
 */
@Table
@Data
@NoArgsConstructor
public class UserSteamId {

    /** 自增主键。 */
    @Id
    private Long id;

    /** QQ 号。 */
    private Long userId;

    /** steam id。 */
    private String steamId;

    /**
     * 构造绑定记录（不含主键，由数据库自增）。
     *
     * @param userId  QQ 号
     * @param steamId steam id
     */
    public UserSteamId(Long userId, String steamId) {
        this.userId = userId;
        this.steamId = steamId;
    }
}
