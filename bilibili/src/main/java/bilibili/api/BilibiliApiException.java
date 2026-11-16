package bilibili.api;

/**
 * B 站接口调用失败。
 *
 * <p>异常消息会直接展示给用户，因此应保持简短、可读；技术细节放在 cause 中。</p>
 */
public class BilibiliApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 面向用户的错误描述
     */
    public BilibiliApiException(String message) {
        super(message);
    }

    /**
     * 构造异常。
     *
     * @param message 面向用户的错误描述
     * @param cause   根因
     */
    public BilibiliApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
