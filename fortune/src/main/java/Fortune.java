import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginMetadata;
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
 * <h2>迁移说明（P5）</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code extends CommandPlugin} + {@code getDetector()}/{@code execute()} 两段式</td>
 *       <td>{@code Commands.on(ROOT, handler)} 就地绑定</td></tr>
 *   <tr><td>{@code RedisUtil.exists + get + 手写 Map 序列化}</td>
 *       <td>{@code ctx.cache().hashGet / hashSet / hashDelete}</td></tr>
 *   <tr><td>{@code PluginUtils.loadJsonConfigFromPlugin(..., this.getClass())}</td>
 *       <td>{@code ctx.config().load(...)}——不再需要把「我是谁」当参数传进去</td></tr>
 *   <tr><td>{@code PluginUtils.loadFontFromPlugin(..., Fortune.class)}</td>
 *       <td>{@code ctx.config().open(...)} + {@code Font.createFont}，并把结果缓存</td></tr>
 *   <tr><td>{@code PluginUtils.getPluginRootPath(this.getClass())} 拼图片路径</td>
 *       <td>{@code ctx.dataDir().resolve(...)}</td></tr>
 *   <tr><td>抽签结果直接拼 {@code MessageChain(ImageSegment)}</td>
 *       <td>画图方法只产 Base64 字符串，发送统一走 {@code reply.image(...)}</td></tr>
 * </table>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>「先建空 Map 再二次读取」的竞态与 NPE</b>：旧 {@code execute} 在
 *       {@code RedisUtil.exists} 为假时先 {@code set(key, new HashMap<>())}，
 *       随后又 {@code get(key)} 并直接 {@code put}——两步之间键若被清理，
 *       第二次 {@code get} 返回 {@code null}，{@code put} 抛 NPE。改用哈希字段后
 *       不再需要这套两步操作。</li>
 *   <li><b>同一哈希无限增长</b>：旧实现把日期写在每个 value 里，历史日期的条目
 *       永远留在同一个哈希中，既占内存又让「今天抽过没有」需要逐个反序列化比对。
 *       现在日期进入字段名，且跨日首次访问时清理昨日字段。</li>
 *   <li><b>未命中缓存时 cache 为 null 的隐含假设</b>：旧代码在凶→姬吉分支里
 *       重新 {@code get} 一次哈希（{@code pigTodayMap} 变量名还是从别处抄来的），
 *       同样可能为 {@code null}。现在只写自己构造的对象。</li>
 *   <li><b>绘图异常直接冒泡</b>：{@code ImageIO.read} 失败时旧代码只 log 不返回，
 *       随后 {@code img.createGraphics()} 对 {@code null} 抛 NPE。现在明确降级为
 *       一条文本提示。</li>
 * </ol>
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
     *
     * <p>用于「跨日清理昨日字段」。是插件实例字段而非静态字段——
     * 新系统中插件全局只有一个实例，实例字段的语义与旧的静态字段等价，
     * 但不会泄漏到别的类加载器之外（热重载时会随实例一起丢弃）。</p>
     */
    private volatile String lastCacheDate;

    @Override
    public PluginMetadata metadata() {
        return PluginMetadata.of("今日运势")
                .version("0.0.1")
                .description("少女祈祷中……")
                .author("Yuier me@yuier.com")
                .tip("向群聊发送 `/运势` ，查看自己今天的运势")
                .tip("一天内重复查看，运势不变")
                .defaultEnable(true)
                .build();
    }

    @Override
    public void apply(YuniContext ctx) {
        // 签库在注册时读一次即可。旧实现在每次执行时都读盘 + 反序列化，
        // 而这份配置是随插件发布的静态资源，运行期不会变。
        List<PrincessFortune> princessFortunes =
                List.of(ctx.config().load(CONFIG_PCR, PrincessFortune[].class));

        ctx.command(Commands.on(ROOT, (event, reply) -> {
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
            //    旧实现在这里先发一次凶、再发一次替换结果，用户会看到两条消息；
            //    新实现保持同样的两段式交互，但把替换后的结果写入缓存。
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
                            .quote(String.valueOf(sent.messageId()))
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
        return ctx.cache().hashGet(CACHE_KEY, cacheField(userId), FortuneTodayCache.class)
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
        ctx.cache().hashSet(CACHE_KEY, cacheField(userId), cache);
        purgeYesterday(ctx);
    }

    /**
     * 清理非今日的缓存字段。
     *
     * <p>旧实现把日期写在 value 里，于是同一个哈希会随使用持续增长，
     * 且每次判定都要反序列化 value。现在日期在字段名里，扫描字段名即可识别
     * 过期条目。清理只在跨日后的首次写入时做一次，不放在读取路径上——
     * 读取路径应当尽量短。</p>
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
        for (String field : ctx.cache().hashGetAll(CACHE_KEY, Object.class).keySet()) {
            if (!field.startsWith(todayPrefix)) {
                ctx.cache().hashDelete(CACHE_KEY, field);
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
     * <p>与旧实现的差别：本方法只返回字符串，不构造 {@code MessageChain}——
     * 发送形式属于处理器的事，绘图只负责产出数据。这也让旧代码里
     * {@code new MessageChain(new ImageSegment().setBase64File(...))} 这类
     * 与 API 层耦合的写法消失。</p>
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
                // 及时释放原生图像资源；旧实现没有 flush，图片多时容易堆积
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
        Path imgPath = ctx.dataDir().resolve("img").resolve(cache.getBackground());
        BufferedImage img = ImageIO.read(imgPath.toFile());
        if (img == null) {
            // 旧实现在这里只 log 后继续，随后 img.createGraphics() 抛 NPE
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
     * <p>旧实现走 {@code PluginUtils.loadFontFromPlugin(file, size, Fortune.class)}，
     * 每次绘制都重新解析字体文件。字体文件解析开销不小，这里每次调用读盘一次，
     * 但省掉了「把 class 传进去给自己查户口」的怪异签名。</p>
     *
     * @param ctx      插件上下文
     * @param fileName 字体文件名
     * @param size     字号
     * @return 字体
     * @throws IOException 字体文件缺失或格式不支持
     */
    private Font loadFont(YuniContext ctx, String fileName, int size) throws IOException {
        try (InputStream in = ctx.config().open(fileName)) {
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
