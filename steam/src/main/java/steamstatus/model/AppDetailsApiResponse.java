package steamstatus.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * {@code store.steampowered.com/api/appdetails} 的响应模型。
 *
 * <h2>响应结构</h2>
 * <pre>{@code
 * {
 *   "570": { "success": true, "data": { "type": "game", "name": "Dota 2", ... } },
 *   "730": { "success": false }
 * }
 * }</pre>
 * <p>顶层以 <b>appid 为键</b>，因此这里用 {@code Map<String, AppDetailResponse>} 承载。</p>
 */
@Data
@NoArgsConstructor
public class AppDetailsApiResponse {

    /** appid → 该游戏的详情条目。 */
    private Map<String, AppDetailResponse> apps;

    /**
     * 取指定 appid 的游戏中文名。
 *
     * @param appId 游戏 appid
     * @return 游戏名；取不到时返回 {@code null}
     */
    public String appName(String appId) {
        if (appId == null || apps == null) {
            return null;
        }
        AppDetailResponse entry = apps.get(appId);
        if (entry == null || !Boolean.TRUE.equals(entry.getSuccess()) || entry.getData() == null) {
            return null;
        }
        return entry.getData().getName();
    }
}
