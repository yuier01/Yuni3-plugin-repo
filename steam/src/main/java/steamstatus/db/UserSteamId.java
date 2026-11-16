package steamstatus.db;

import com.yuier.yuni.plugin.data.annotation.Id;
import com.yuier.yuni.plugin.data.annotation.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记录用户 QQ 号与 steam id 的绑定关系。
 *
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
