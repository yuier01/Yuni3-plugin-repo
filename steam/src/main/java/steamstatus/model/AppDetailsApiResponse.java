package steamstatus.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * {@code store.steampowered.com/api/appdetails} 的响应模型。
 *
 * <h2>迁移说明（P5）</h2>
 * <p>旧实现把这份响应当成裸 {@code Map} 反序列化，随后一路
 * {@code instanceof Map} + 强制转型向下取 {@code data.name}，
 * 三处 {@code @SuppressWarnings}。本类描述同一份结构，取值变成普通 getter，
 * 且字段缺失时自然得到 {@code null} 而不是 {@code ClassCastException}。</p>
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
     * <p>任何一步缺失都返回 {@code null}，由调用方回退到英文名——
     * 与旧实现「取不到就 {@code setGameChineseName(gameextrainfo)}」的语义一致。</p>
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
