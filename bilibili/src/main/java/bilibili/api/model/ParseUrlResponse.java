package bilibili.api.model;

/**
 * {@code POST /v1/common/parse_url} 的响应。
 *
 * @param code    业务状态码，{@code 0} 表示成功
 * @param message 状态描述
 * @param data    解析结果
 */
public record ParseUrlResponse(int code, String message, ParseUrlData data) {

    /**
     * 链接解析结果。
     *
     * @param type 资源类型，视频为 {@code video}
     * @param bvid BV 号
     * @param url  B 站标准长链接
     */
    public record ParseUrlData(String type, String bvid, String url) {
    }
}
