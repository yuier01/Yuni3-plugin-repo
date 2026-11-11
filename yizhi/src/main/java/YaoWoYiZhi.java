import com.madgag.gif.fmsware.AnimatedGifEncoder;
import com.madgag.gif.fmsware.GifDecoder;
import com.yuier.yuni.core.enums.CommandArgRequireType;
import com.yuier.yuni.event.detector.message.command.model.ArgDef;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginMetadata;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;
import java.util.Set;

/**
 * 要我一直 x 吗——把用户发来的图片做成表情包。
 *
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin} + {@code getDetector()}/{@code execute()} 两段式</td>
 *       <td>{@code Commands.on(ROOT, handler)} 就地绑定</td></tr>
 *   <tr><td>{@code eventContext.getChatSession().response(new MessageChain(...))}</td>
 *       <td>{@code reply.text(...)} / {@code reply.image("base64://...")}</td></tr>
 *   <tr><td>{@code PluginUtils.loadFontFromPlugin(..., YaoWoYiZhi.class)} 在逐帧处理里调用</td>
 *       <td>{@code ctx.getConfig().open(...)}，且字体只在每次命令处理开始时加载<b>一次</b></td></tr>
 * </table>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>字体逐帧解析</b>：旧 {@code processSingleFrame} 每帧都调
 *       {@code loadFontFromPlugin}，一张 60 帧的 GIF 就要解析 60 次 OTF/TTF。
 *       字体现在由 {@link #execute} 一次性加载后传入，静态图与 GIF 共用同一份。</li>
 *   <li><b>以「文件名后缀」判定格式过于脆弱</b>：QQ 上报的 {@code file} 字段
 *       可能是无扩展名的哈希串，旧代码 {@code split("\\.")[split.length - 1]}
 *       在无点号时会把整个串当后缀，从而落进「不支持的图片格式」。
 *       现在同时看 {@code file} 与 {@code url}，并只在真正拿到扩展名时判定。</li>
 *   <li><b>{@code printStackTrace}</b>：旧代码用 {@code e.printStackTrace()} 记录
 *       图片下载失败，不走日志框架。已改为 {@code log.error}。</li>
 * </ol>
 */
@Slf4j
public class YaoWoYiZhi implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD_YI_ZHI = "一直";

    /** 参数名：目标图片。 */
    private static final String ARG_TARGET_IMAGE = "图片";

    /** 画在左下角的固定文字。 */
    private static final String TEXT_LEFT = "要我一直";

    /** 画在右下角的固定文字。 */
    private static final String TEXT_RIGHT = "吗";

    /** 字体资源路径。 */
    private static final String FONT_PATH = "static/font/HarmonyOS_SansSC_Medium.ttf";

    /** 画布宽度。 */
    private static final int CANVAS_WIDTH = 600;

    /** 静态图片格式白名单（不含 gif，gif 走单独分支）。 */
    private static final Set<String> STATIC_IMAGE_EXTENSIONS = Set.of("jpg", "png", "jpeg");

    /** 命令树：单个必填的图片参数。 */
    private static final CommandNode ROOT = CommandNode.builder(CMD_YI_ZHI)
            .description("要我一直")
            .arg(ArgDef.required(ARG_TARGET_IMAGE, "要我一直", CommandArgRequireType.IMAGE))
            .build();

    @Override
    public PluginMetadata getMetadata() {
        return PluginMetadata.of("要我一直 IMAGE 吗")
                .version("0.0.1")
                .description("一直.image")
                .author("Yuier me@yuier.com")
                .tip("向群聊发送 `/一直 {图片}` ，bot 会向群聊发送一张 “<大图> 要我一直<小图>吗” 的图片")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        ctx.command(Commands.on(ROOT, (event, reply) -> {
            var image = event.getCommandResult().getArg(ARG_TARGET_IMAGE).asImage();
            String imageFileUrl = image.getUrl();
            String imageEndName = resolveExtension(image.getFile(), imageFileUrl);

            if (imageFileUrl == null || imageFileUrl.isBlank()) {
                log.warn("[要我一直] 事件里没有图片下载地址");
                reply.quote().text("没有拿到图片地址，请重新发送一次试试。").send();
                return;
            }

            // 字体只在本次命令处理开始时加载一次，GIF 逐帧复用
            Font font = loadFont(ctx);

            String imageBase64;
            if ("gif".equals(imageEndName)) {
                imageBase64 = processGifFromUrl(imageFileUrl, font);
            } else if (STATIC_IMAGE_EXTENSIONS.contains(imageEndName)) {
                imageBase64 = processStaticImageFromUrl(imageFileUrl, font);
            } else {
                reply.quote().text("不支持的图片格式: " + imageEndName).send();
                return;
            }

            if (imageBase64 == null) {
                reply.quote().text("图片处理失败，请稍后再试。").send();
                return;
            }
            reply.image("base64://" + imageBase64).send();
        }));
    }

    /**
     * 解析图片扩展名。
     *
     * <p>先看 OneBot 上报的文件名，它通常带真实扩展名；拿不到时回退到 URL 路径。
     * 两者都没有点号时返回空串，由调用方走「不支持的格式」分支。</p>
     *
     * @param fileName OneBot 上报的文件名；可为 {@code null}
     * @param url      图片下载地址；可为 {@code null}
     * @return 小写扩展名；无法判定时返回空串
     */
    private static String resolveExtension(String fileName, String url) {
        String fromName = extensionOf(fileName);
        return !fromName.isEmpty() ? fromName : extensionOf(url);
    }

    /**
     * 取字符串最后一个点号之后的部分并转小写。
     *
     * @param value 文件名或 URL；可为 {@code null}
     * @return 小写扩展名；无点号时返回空串
     */
    private static String extensionOf(String value) {
        if (value == null) {
            return "";
        }
        // 去掉 query / fragment，避免把 "a.jpg?x=1" 的 "jpg?x=1" 当成扩展名
        int cut = value.length();
        for (char stop : new char[] {'?', '#'}) {
            int idx = value.indexOf(stop);
            if (idx >= 0 && idx < cut) {
                cut = idx;
            }
        }
        String cleaned = value.substring(0, cut);
        int dot = cleaned.lastIndexOf('.');
        if (dot < 0 || dot == cleaned.length() - 1) {
            return "";
        }
        return cleaned.substring(dot + 1).toLowerCase();
    }

    /**
     * 加载表情包字体。
     *
     * @param ctx 插件上下文
     * @return 字体；加载失败时返回 {@code null}，调用方回退到默认字体
     */
    private Font loadFont(YuniContext ctx) {
        try (InputStream in = ctx.getConfig().open(FONT_PATH)) {
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(100f);
        } catch (IOException | FontFormatException e) {
            log.error("[要我一直] 加载字体失败，将使用默认字体", e);
            return null;
        }
    }

    /**
     * 处理 GIF 图片：逐帧套上表情包版式后重新编码为 GIF。
     *
     * @param imageFileUrl 图片地址
     * @param font         绘制文字用的字体；{@code null} 时用默认字体
     * @return Base64 编码的 GIF
     */
    private String processGifFromUrl(String imageFileUrl, Font font) {
        GifDecoder decoder = new GifDecoder();
        try (InputStream stream = new URL(imageFileUrl).openStream();
             BufferedInputStream buffered = new BufferedInputStream(stream)) {
            // 标记流以便解码器内部重置
            buffered.mark(Integer.MAX_VALUE);
            int status = decoder.read(buffered);
            if (status != GifDecoder.STATUS_OK || decoder.getFrameCount() <= 0) {
                throw new IOException("GIF 解码失败，status=" + status);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            AnimatedGifEncoder encoder = new AnimatedGifEncoder();
            encoder.start(outputStream);
            encoder.setRepeat(decoder.getLoopCount());
            // 1-10，10 为最高质量
            encoder.setQuality(10);

            int frameCount = decoder.getFrameCount();
            for (int i = 0; i < frameCount; i++) {
                BufferedImage frame = decoder.getFrame(i);
                int delay = decoder.getDelay(i);
                BufferedImage processedFrame = processSingleFrame(frame, font);

                // 最小延迟 10ms，避免播放过快
                encoder.setDelay(Math.max(delay, 10));
                encoder.addFrame(processedFrame);

                // 处理完成后立即释放原帧，帮助 GC 回收大图
                frame.flush();
                processedFrame.flush();
            }

            encoder.finish();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException e) {
            log.error("[要我一直] GIF 处理失败", e);
            return null;
        }
    }

    /**
     * 处理静态图片：套上表情包版式后编码为 JPEG。
     *
     * @param imageFileUrl 图片地址
     * @param font         绘制文字用的字体；{@code null} 时用默认字体
     * @return Base64 编码的 JPEG；下载或编码失败时返回 {@code null}
     */
    private String processStaticImageFromUrl(String imageFileUrl, Font font) {
        BufferedImage originalImage;
        try {
            originalImage = ImageIO.read(new URL(imageFileUrl));
        } catch (IOException e) {
            log.error("[要我一直] 下载图片失败: {}", imageFileUrl, e);
            return null;
        }
        if (originalImage == null) {
            // ImageIO 对无法识别的格式返回 null 而不抛异常
            log.error("[要我一直] 图片无法解码: {}", imageFileUrl);
            return null;
        }

        BufferedImage resultImage = processSingleFrame(originalImage, font);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(resultImage, "jpg", baos);
        } catch (IOException e) {
            log.error("[要我一直] 图片编码失败", e);
            return null;
        } finally {
            resultImage.flush();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /**
     * 把一帧图片套上「大图 + 右下角小图 + 底部两段文字」的版式。
     *
     * @param originalImage 原始帧
     * @param font          绘制文字用的字体；{@code null} 时用默认字体
     * @return 处理后的帧
     */
    private BufferedImage processSingleFrame(BufferedImage originalImage, Font font) {
        // 统一主图宽度为 600px，高度等比
        int newMainImgWidth = CANVAS_WIDTH;
        int newMainImgHeight = (int) (originalImage.getHeight() * 600.0 / originalImage.getWidth());

        // 画布高度取两种算法的大值：
        // 1) 按原始比例放大并预留 30px；2) 主图高度 + 150px
        int canvasHeightByProportion = (int) (originalImage.getHeight() * 700.0 / originalImage.getWidth()) + 30;
        int canvasHeightByAbsolute = newMainImgHeight + 150;
        int canvasHeight = Math.max(canvasHeightByProportion, canvasHeightByAbsolute);

        // 白色背景画布
        BufferedImage resultImage = new BufferedImage(newMainImgWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resultImage.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, newMainImgWidth, canvasHeight);

            // 主图贴在左上角
            BufferedImage resizedImage = resizeImage(originalImage, newMainImgWidth, newMainImgHeight);
            g2d.drawImage(resizedImage, 0, 0, null);

            // 右下角小图：宽 100，高为主图高度的 1/6
            int smallImageWidth = 100;
            int smallImageHeight = (int) (newMainImgHeight / 6.0);
            BufferedImage smallImage = resizeImage(originalImage, smallImageWidth, smallImageHeight);
            int smallImageX = 400;
            int smallImageY = newMainImgHeight
                    + (int) ((canvasHeight - newMainImgHeight - smallImageHeight) / 2.0);
            g2d.drawImage(smallImage, smallImageX, smallImageY, null);

            // 底部文字：「要我一直」靠左，「吗」靠右
            if (font != null) {
                g2d.setFont(font);
            }
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2d.setColor(Color.BLACK);

            int height = g2d.getFontMetrics().getAscent() + g2d.getFontMetrics().getDescent();
            int textY = newMainImgHeight + height;
            g2d.drawString(TEXT_LEFT, 0, textY);
            int rightWidth = g2d.getFontMetrics().stringWidth(TEXT_RIGHT);
            g2d.drawString(TEXT_RIGHT, CANVAS_WIDTH - rightWidth, textY);
        } finally {
            g2d.dispose();
        }
        return resultImage;
    }

    /**
     * 等比缩放到目标尺寸。
     *
     * <p>思路：创建目标尺寸画布 → 设置高质量渲染提示（双线性插值、
     * 质量优先渲染、抗锯齿）→ 一次 {@code drawImage} 完成缩放。</p>
     *
     * @param originalImage 原始图片
     * @param targetWidth   目标宽度
     * @param targetHeight  目标高度
     * @return 缩放后的图片
     */
    private static BufferedImage resizeImage(BufferedImage originalImage, int targetWidth, int targetHeight) {
        // 高度可能因取整变成 0，此时给 1 像素，避免构造 BufferedImage 抛异常
        int width = Math.max(targetWidth, 1);
        int height = Math.max(targetHeight, 1);
        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resizedImage.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(originalImage, 0, 0, width, height, null);
        } finally {
            g2d.dispose();
        }
        return resizedImage;
    }
}
