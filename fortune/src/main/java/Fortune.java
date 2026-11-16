import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import entity.FortuneItem;
import entity.FortuneTodayCache;
import entity.PrincessFortune;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 今日运势——每日一抽，结果当天不变。
 *
 * <h2>关于「一天内结果不变」</h2>
 * <p>这是本插件的核心语义，与今日猪猪一致：同一个人在同一天内重复抽取得到同一支签。
 * 实现方式是把结果按「日期 + QQ号」作为哈希字段缓存。</p>
 */
@Slf4j
public class Fortune implements YuniPlugin {

    /** 命令名。 */
    private static final String CMD_FORTUNE = "运势";

    /** 缓存哈希键。 */
    private static final String CACHE_KEY = "plugin:fortune:key";

    /** 签库配置文件名（相对插件资源目录）。 */
    private static final String CONFIG_PCR = "special/pcr.json";

    /** 标题字体文件名。 */
    private static final String FONT_TITLE = "font/Mamelon.otf";

    /** 正文字体文件名。 */
    private static final String FONT_TEXT = "font/sakura.ttf";

    /** 标题字号。 */
    private static final int TITLE_FONT_SIZE = 45;

    /** 正文字号。 */
    private static final int COMMENT_FONT_SIZE = 25;

    /** 「姬吉」这一签的标题；抽到「凶」时替换为它。 */
    private static final String HI_ME_TITLE = "姫吉";

    /** 命令树：无参数无子命令。 */
    private static final CommandNode ROOT = CommandNode.builder(CMD_FORTUNE)
            .description("今日运势")
            .build();

    /**
     * 上一次写入缓存时的日期。
 */
    private volatile String lastCacheDate;


    @Override
    public void apply(YuniContext ctx) {
        // 而这份配置是随插件发布的静态资源，运行期不会变。
        List<PrincessFortune> princessFortunes =
                List.of(ctx.getConfigLoader().load(CONFIG_PCR, PrincessFortune[].class));

        ctx.command(Commands.on(ROOT, (event, result, reply) -> {
            // 命令处理器收到的是命令引擎的原生事件，取值方式与旧 execute() 一致
            long userId = event.getUserId();

            // 1. 命中今天的缓存就直接返回，保证「一天内结果不变」
            FortuneTodayCache cached = todayCache(ctx, userId);
            if (cached != null) {
                reply.quote().text("你今天已经抽取过运势，结果如下: \n").send();
                reply.image(toBase64(ctx, cached)).send();
                return;
            }

            // 2. 未命中：从签库随机抽一位公主、一张背景、一支签
            PrincessFortune princess = randomOf(princessFortunes);
            if (princess == null) {
                log.error("[今日运势] 签库配置为空，无法抽签");
                reply.text("运势签库好像出了问题，请联系 bot 管理员。").send();
                return;
            }
            String background = randomOf(princess.getBackgrounds());
            FortuneItem item = randomOf(princess.getFortunePool());
            if (item == null || background == null) {
                log.error("[今日运势] 公主 [{}] 缺少背景图或签池配置", princess.getName());
                reply.text("运势签库好像出了问题，请联系 bot 管理员。").send();
                return;
            }

            FortuneTodayCache today = new FortuneTodayCache(
                    item.getTitle(), item.getComment(), background, LocalDate.now().toString());

            // 3. 先画图，画不出来就别把结果写进缓存——否则用户当天再也抽不到
            String base64 = toBase64(ctx, today);
            if (base64 == null) {
                reply.text("运势图生成失败，请稍后再试。").send();
                return;
            }
            reply.quote().text("你今天抽取的运势是: \n").send();
            Reply.SentResult sent = reply.image(base64).send();

            // 4. 抽到「凶」就换成姬吉。
            if (item.getTitle() != null && item.getTitle().contains("凶")) {
                FortuneItem hiMe = randomOf(findHiMe(princess));
                if (hiMe == null) {
                    // 签池里没有姬吉：保留凶的结果，仍然缓存
                    cache(ctx, userId, today);
                    return;
                }
                today = new FortuneTodayCache(
                        hiMe.getTitle(), hiMe.getComment(), background, today.getDate());
                String hiMeBase64 = toBase64(ctx, today);
                if (hiMeBase64 != null) {
                    reply.text("逢凶化吉！已为你将运势替换为姬吉！\n")
                            .quote(String.valueOf(sent.getMessageId()))
                            .image(hiMeBase64)
                            .send();
                }
            }
            cache(ctx, userId, today);
        }));
    }

    // ==================== 缓存 ====================

    /**
     * 读取「今天」的运势缓存。
     *
     * <p>字段名带上日期，因此不需要把读到的条目再和当前日期比对——
     * 昨天写入的字段根本不会被今天读到。</p>
     *
     * @param ctx    插件上下文
     * @param userId 目标 QQ 号
     * @return 缓存结果；今天尚未抽取时返回 {@code null}
     */
    private FortuneTodayCache todayCache(YuniContext ctx, long userId) {
        return ctx.getCache().hashGet(CACHE_KEY, cacheField(userId), FortuneTodayCache.class)
                .orElse(null);
    }

    /**
     * 写入今天的运势缓存，并在跨日时清理昨日字段。
     *
     * @param ctx    插件上下文
     * @param userId 目标 QQ 号
     * @param cache  运势结果
     */
    private void cache(YuniContext ctx, long userId, FortuneTodayCache cache) {
        ctx.getCache().hashSet(CACHE_KEY, cacheField(userId), cache);
        purgeYesterday(ctx);
    }

    /**
     * 清理非今日的缓存字段。
 *
     * @param ctx 插件上下文
     */
    private void purgeYesterday(YuniContext ctx) {
        String today = LocalDate.now().toString();
        if (today.equals(lastCacheDate)) {
            return;
        }
        lastCacheDate = today;
        String todayPrefix = today + ":";
        for (String field : ctx.getCache().hashGetAll(CACHE_KEY, Object.class).keySet()) {
            if (!field.startsWith(todayPrefix)) {
                ctx.getCache().hashDelete(CACHE_KEY, field);
            }
        }
    }

    /**
     * 组装缓存字段名。
     *
     * @param userId 目标 QQ 号
     * @return 形如 {@code "2026-09-16:10001"} 的字段名
     */
    private static String cacheField(long userId) {
        return LocalDate.now() + ":" + userId;
    }

    // ==================== 抽签辅助 ====================

    /**
     * 找到姬吉。
     *
     * @param fortune 某位公主的签配置
     * @return 姬吉签列表；没有时返回空列表
     */
    List<FortuneItem> findHiMe(PrincessFortune fortune) {
        List<FortuneItem> hiMeList = new ArrayList<>();
        if (fortune.getFortunePool() == null) {
            return hiMeList;
        }
        for (FortuneItem fortuneItem : fortune.getFortunePool()) {
            if (HI_ME_TITLE.equals(fortuneItem.getTitle())) {
                hiMeList.add(fortuneItem);
            }
        }
        return hiMeList;
    }

    /**
     * 从列表里随机取一个元素。
     *
     * @param list 候选列表；可为 {@code null} 或空
     * @param <T>  元素类型
     * @return 随机元素；列表为空时返回 {@code null}
     */
    private static <T> T randomOf(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    // ==================== 绘图 ====================

    /**
     * 绘制运势图并编码为 Base64。
 *
     * @param ctx   插件上下文
     * @param cache 运势结果
     * @return {@code base64://} 前缀的图片数据；失败时返回 {@code null}
     */
    private String toBase64(YuniContext ctx, FortuneTodayCache cache) {
        try {
            BufferedImage img = draw(ctx, cache);
            if (img == null) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                ImageIO.write(img, "PNG", baos);
            } finally {
                img.flush();
            }
            return "base64://" + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            // 绘图是本地操作但仍可能因字体/图片资源缺失而失败，
            // 这里统一降级为提示，不让异常冒到事件分发器
            log.error("[今日运势] 生成运势图失败", e);
            return null;
        }
    }

    /**
     * 把运势文字画到背景图上。
     *
     * @param ctx   插件上下文
     * @param cache 运势结果
     * @return 绘制完成的图片；资源缺失时返回 {@code null}
     */
    private BufferedImage draw(YuniContext ctx, FortuneTodayCache cache) throws IOException {
        String title = cache.getTitle();
        String text = cache.getComment();

        Font titleFont = loadFont(ctx, FONT_TITLE, TITLE_FONT_SIZE);
        Font textFont = loadFont(ctx, FONT_TEXT, COMMENT_FONT_SIZE);
        Path imgPath = ctx.getDataDir().resolve("img").resolve(cache.getBackground());
        BufferedImage img = ImageIO.read(imgPath.toFile());
        if (img == null) {
            log.error("[今日运势] 背景图无法解码: {}", imgPath);
            return null;
        }

        Graphics2D g2d = img.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // ========== 1. 绘制标题（居中） ==========
            g2d.setColor(Color.decode("#F5F5F5"));
            g2d.setFont(titleFont);
            int[] titleCenter = {140, 99};
            FontRenderContext frc = g2d.getFontRenderContext();
            Rectangle2D titleBounds = titleFont.getStringBounds(title, frc);
            int titleX = titleCenter[0] - (int) titleBounds.getWidth() / 2;
            // Java 的 y 坐标是文字基线，这里向上微调 15 像素
            int titleY = titleCenter[1] + (int) titleBounds.getHeight() / 2 - 15;
            g2d.drawString(title, titleX, titleY);

            // ========== 2. 绘制正文（竖排） ==========
            g2d.setColor(Color.decode("#323232"));
            g2d.setFont(textFont);
            int[] textCenter = {140, 297};
            int textFontSize = textFont.getSize();
            TextSplitResult splitResult = decrement(text);
            int slices = splitResult.getColNum();
            List<String> result = splitResult.getTextList();
            for (int i = 0; i < slices; i++) {
                String colText = result.get(i);
                int fontHeight = colText.length() * (textFontSize + 4);
                int x = (int) (textCenter[0]
                        + (slices - 2) * textFontSize / 2.0
                        + (slices - 1) * 4
                        - i * (textFontSize + 4));
                int yStart = textCenter[1] - fontHeight / 2 + 22;
                for (int j = 0; j < colText.length(); j++) {
                    g2d.drawString(String.valueOf(colText.charAt(j)), x, yStart + j * (textFontSize + 4));
                }
            }
        } finally {
            g2d.dispose();
        }
        return img;
    }

    /**
     * 从插件资源目录加载字体。
 *
     * @param ctx      插件上下文
     * @param fileName 字体文件名
     * @param size     字号
     * @return 字体
     * @throws IOException 字体文件缺失或格式不支持
     */
    private Font loadFont(YuniContext ctx, String fileName, int size) throws IOException {
        try (InputStream in = ctx.getConfigLoader().open(fileName)) {
            return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont((float) size);
        } catch (java.awt.FontFormatException e) {
            throw new IOException("字体格式不支持: " + fileName, e);
        }
    }

    // ==================== 文本竖排拆分 ====================

    /**
     * 文本拆分函数（对应 Python 的 decrement）。
     *
     * @param text 原始正文文本
     * @return 文本拆分结果（列数 + 每列文本列表）
     */
    public TextSplitResult decrement(String text) {
        int length = text.length();
        List<String> result = new ArrayList<>();
        int cardinality = 9; // 每列最大字符数

        // 文本过长抛出异常
        if (length > 4 * cardinality) {
            throw new IllegalArgumentException("文本长度超过最大限制（36个字符）");
        }

        // 计算列数
        int colNum = 1;
        int tempLength = length;
        while (tempLength > cardinality) {
            colNum++;
            tempLength -= cardinality;
        }

        // 优化两列布局（核心逻辑和 Python 一致）
        String space = " ";
        if (colNum == 2) {
            List<String> twoColList = new ArrayList<>();
            if (length % 2 == 0) {
                int fillLen = 9 - length / 2;
                String fillIn = space.repeat(fillLen);
                twoColList.add(text.substring(0, length / 2) + fillIn);
                twoColList.add(fillIn + text.substring(length / 2));
            } else {
                int fillLen = 9 - (length + 1) / 2;
                String fillIn = space.repeat(fillLen);
                twoColList.add(text.substring(0, (length + 1) / 2) + fillIn);
                twoColList.add(fillIn + space + text.substring((length + 1) / 2));
            }
            return new TextSplitResult(colNum, twoColList);
        }

        // 处理其他列数
        for (int i = 0; i < colNum; i++) {
            int start = i * cardinality;
            int end = (i == colNum - 1) ? text.length() : (i + 1) * cardinality;
            result.add(text.substring(start, end));
        }

        return new TextSplitResult(colNum, result);
    }

    /**
     * 竖排拆分结果。
     *
     * <p>不可变值对象：{@code @Value} 生成全参构造器与 {@code getColNum()} /
     * {@code getTextList()}。</p>
     */
    @Value
    public static final class TextSplitResult {

        /** 列数。 */
        int colNum;

        /** 每列的文本列表。 */
        List<String> textList;
    }
}
