package bilibili.api.model;

/**
 * {@code POST /v1/common/parse_url} 的请求体。
 *
 * @param url 分享文案、b23.tv 短链、B 站长链或裸 BV 号
 */
public record ParseUrlRequest(String url) {
}
