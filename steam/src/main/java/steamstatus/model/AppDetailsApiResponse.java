package steamstatus.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashMap;
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
 * <p>顶层以 <b>appid 为键</b>，没有固定的 {@code apps} 包装字段，因此这里用
 * {@link JsonAnySetter} 把动态键收集到 {@link #apps} 中。</p>
 */
public class AppDetailsApiResponse {

    /** appid → 该游戏的详情条目。 */
    private final Map<String, AppDetailResponse> apps = new HashMap<>();

    /**
     * 收集顶层动态 appid 字段。
     *
     * @param appId 游戏 appid
     * @param entry 游戏详情条目
     */
    @JsonAnySetter
    public void addApp(String appId, AppDetailResponse entry) {
        apps.put(appId, entry);
    }

    /**
     * 取 appid 到详情条目的映射。
     *
     * @return appid → 详情条目；永不为 {@code null}
     */
    public Map<String, AppDetailResponse> getApps() {
        return apps;
    }

    /**
     * 取指定 appid 的游戏中文名。
     *
     * @param appId 游戏 appid
     * @return 游戏名；取不到时返回 {@code null}
     */
    public String appName(String appId) {
        if (appId == null) {
            return null;
        }
        AppDetailResponse entry = apps.get(appId);
        if (entry == null || !Boolean.TRUE.equals(entry.getSuccess()) || entry.getData() == null) {
            return null;
        }
        return entry.getData().getName();
    }
}
