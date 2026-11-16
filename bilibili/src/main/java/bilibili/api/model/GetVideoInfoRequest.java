package bilibili.api.model;

/**
 * {@code POST /v1/video/get_info} 的请求体。
 *
 * @param bvid BV 号，形如 {@code BV1GJ411x7h7}
 */
public record GetVideoInfoRequest(String bvid) {
}
