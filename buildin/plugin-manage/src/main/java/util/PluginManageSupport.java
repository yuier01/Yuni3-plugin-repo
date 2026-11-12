package util;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.yuier.yuni.plugin.api.PluginAdmin;
import com.yuier.yuni.plugin.api.PluginModuleView;
import com.yuier.yuni.plugin.api.PluginView;
import com.yuier.yuni.plugin.api.YuniContext;
import entity.PluginDetail;
import entity.PluginListElement;
import entity.PluginListModule;
import entity.PluginListSinglePlugin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static com.microsoft.playwright.options.WaitForSelectorState.VISIBLE;

/**
 * 插件管理插件的支撑服务——数据组装、模板渲染、HTML 截屏、结果缓存。
 *
 * <h2>迁移说明（P5）：静态工具类 → 持有 ctx 的实例</h2>
 * <p>旧 {@code PluginManageUtil} 是一个全静态类，它从 Spring 容器里捞
 * {@code PluginContainer} 与 {@code PluginEnableProcessor} 来读插件数据，
 * 并用静态方法 {@code RedisUtil} 读写缓存。迁移后：</p>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code PluginUtils.getBean(PluginContainer.class)}</td>
 *       <td>{@link PluginAdmin}（经 {@code ctx.getPlugins()}）</td></tr>
 *   <tr><td>{@code PluginUtils.getBean(PluginEnableProcessor.class)}</td>
 *       <td>{@code PluginAdmin.isEnabledIn(pluginId, groupId)}</td></tr>
 *   <tr><td>{@code RedisUtil.hGetAll / hSet} 读整个哈希再取值</td>
 *       <td>{@code ctx.getCache().hashGet / hashSet} 单字段读写</td></tr>
 *   <tr><td>{@code PluginUtils.loadTextFromPluginFolder(file, this.getClass())}</td>
 *       <td>{@code ctx.getConfigLoader().loadText(file)}</td></tr>
 *   <tr><td>全部静态方法</td><td>实例方法（渲染/截屏等纯函数仍为静态）</td></tr>
 * </table>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>缓存读取先取整个哈希再取值</b>：旧
 *       {@code getPluginListHashCodeCache} 调
 *       {@code RedisUtil.hGetAll(key)} 把<b>全部</b>会话的缓存都拉回来，再用
 *       {@code get(position)} 取其中一条。群多时这是明显的浪费（每个群各自
 *       一份缓存，N 个群就要传 N 条 value）。新实现按字段名单取。</li>
 *   <li><b>{@code printStackTrace}</b>：旧 {@code screenForHtmlStrToBase64} 与
 *       {@code screenForHtmlStrAndSave} 用 {@code e.printStackTrace()}，
 *       不走日志框架，日志文件里看不到。已改为 {@code log.error}。</li>
 *   <li><b>截屏失败静默返回 {@code null}</b>：旧实现的调用方拿到 {@code null}
 *       后会构造一个 {@code base64://null} 的消息发出去（用户看到一个裂图）。
 *       新实现由调用方显式判空并给出可读提示。</li>
 *   <li><b>{@code screenForHtmlStrToBase64} 不关闭浏览器</b>：旧实现
 *       {@code try (Playwright playwright = ...)} 只关了 Playwright，
 *       {@code Browser} 没有 {@code close()}——每次「查看插件列表」都会留下一个
 *       浏览器进程，几次之后就耗尽内存。已补上。</li>
 *   <li><b>列表哈希把 {@code enabled} 计入但没计入状态</b>：旧实现算哈希时
 *       只看名称/描述/使能。新系统多了一个「运行状态」（加载失败 / 等待依赖），
 *       而弹出插件失败恰恰是用户最需要看到变化的时刻——因此把它并入哈希。</li>
 * </ol>
 */
@Slf4j
public class PluginManageSupport {

    /** 插件列表「内容哈希」缓存键。 */
    public static final String PLUGIN_LIST_HASHCODE_CACHE_KEY = "plugin:list:hashcode:key";

    /** 插件列表「已渲染图片」缓存键。 */
    public static final String PLUGIN_LIST_IMAGE_CACHE_KEY = "plugin:list:image:key";

    /** 插件详情「内容哈希」缓存键。 */
    public static final String PLUGIN_DETAIL_HASHCODE_CACHE_KEY = "plugin:detail:hashcode:key";

    /** 插件详情「已渲染图片」缓存键。 */
    public static final String PLUGIN_DETAIL_IMAGE_CACHE_KEY = "plugin:detail:image:key";

    /** 插件列表模板路径。 */
    private static final String TEMPLATE_LIST = "templates/plugin-list.html";

    /** 插件详情模板路径。 */
    private static final String TEMPLATE_DETAIL = "templates/plugin-detail.html";

    /** 截屏目标的元素 ID（两个模板共用）。 */
    private static final String SCREENSHOT_ELEMENT_ID = "result-container";

    /** 插件上下文。 */
    private final YuniContext ctx;

    /** 插件管理服务。 */
    private final PluginAdmin admin;

    /**
     * 构造支撑服务。
     *
     * @param ctx 插件上下文
     */
    public PluginManageSupport(YuniContext ctx) {
        this.ctx = ctx;
        this.admin = ctx.getPlugins();
    }

    // ==================== 数据组装 ====================

    /**
     * 组装插件列表数据。
     *
     * <p>按模块分组：单插件模块渲染成一行，多插件模块渲染成一个带标题的卡片。
     * 与旧 {@code PluginShow.assemblerPluginListData} 的结构一致，区别是数据源
     * 从 {@code PluginContainer} 换成了 {@link PluginAdmin}。</p>
     *
     * @param groupId 当前消息所在群号，用于判定每个插件的生效状态
     * @return 列表元素
     */
    public List<PluginListElement> assemblePluginList(long groupId) {
        List<PluginListElement> elements = new ArrayList<>();
        for (PluginModuleView module : admin.getModules()) {
            List<PluginView> plugins = module.getPlugins();
            if (plugins.isEmpty()) {
                // 模块下没有任何插件（例如 jar 里没有 YuniPlugin 实现）：
                // 不展示，否则用户会看到一个永远打不开的空卡片
                continue;
            }
            if (plugins.size() == 1) {
                elements.add(new PluginListElement(toListElement(plugins.get(0), groupId)));
            } else {
                PluginListModule listModule = new PluginListModule();
                listModule.setModuleName(module.getName());
                for (PluginView plugin : plugins) {
                    listModule.addSubPlugin(toListElement(plugin, groupId));
                }
                elements.add(new PluginListElement(listModule));
            }
        }
        return elements;
    }

    /**
     * 把插件视图翻译为列表行。
     *
     * @param plugin  插件视图
     * @param groupId 群号
     * @return 列表行
     */
    private PluginListSinglePlugin toListElement(PluginView plugin, long groupId) {
        return new PluginListSinglePlugin(
                plugin.getId(),
                plugin.getName(),
                plugin.getDescription(),
                admin.isEnabledIn(plugin.getId(), groupId));
    }

    /**
     * 把插件视图翻译为详情数据。
     *
     * @param plugin  插件视图
     * @param groupId 群号
     * @return 详情数据
     */
    public PluginDetail toDetail(PluginView plugin, long groupId) {
        return new PluginDetail(
                plugin.getId(),
                plugin.getName(),
                plugin.getDescription(),
                plugin.getTips(),
                plugin.getAuthor(),
                plugin.getVersion(),
                admin.isEnabledIn(plugin.getId(), groupId),
                plugin.getModuleId(),
                plugin.getState().getDisplayName(),
                plugin.getState().name().toLowerCase(),
                plugin.getFailureSummary().orElse(null),
                plugin.getMissingServices());
    }

    // ==================== 内容哈希 ====================

    /**
     * 计算插件列表的内容哈希。
     *
     * <p>哈希决定「能不能复用上次渲染的图片」。它必须覆盖<b>所有会影响渲染结果</b>
     * 的字段——漏一个就会出现「明明变了却显示旧图」。</p>
     *
     * <p>与旧实现的差异：把 {@code state} 也计入。旧实现只看名称/描述/使能，
     * 于是「插件从 ACTIVE 变成 FAILED」不会让缓存失效，用户看到的仍然是
     * 「运行中」。</p>
     *
     * @param groupId 群号
     * @return 哈希值
     */
    public int hashCodeOfList(long groupId) {
        List<Integer> moduleHashes = new ArrayList<>();
        for (PluginModuleView module : admin.getModules()) {
            List<Integer> pluginHashes = new ArrayList<>();
            for (PluginView plugin : module.getPlugins()) {
                pluginHashes.add(Objects.hash(
                        plugin.getName(),
                        plugin.getDescription(),
                        plugin.getState(),
                        admin.isEnabledIn(plugin.getId(), groupId)));
            }
            // 排序消除顺序影响：模块内插件的顺序变化不应导致重新渲染
            pluginHashes.sort(Integer::compareTo);
            moduleHashes.add(pluginHashes.hashCode());
        }
        moduleHashes.sort(Integer::compareTo);
        return moduleHashes.hashCode();
    }

    /**
     * 计算单个插件详情的内容哈希。
     *
     * @param plugin  插件视图
     * @param groupId 群号
     * @return 哈希值
     */
    public int hashCodeOfDetail(PluginView plugin, long groupId) {
        return Objects.hash(
                plugin.getName(),
                plugin.getDescription(),
                plugin.getTips(),
                plugin.getAuthor(),
                plugin.getVersion(),
                plugin.getModuleId(),
                plugin.getState(),
                plugin.getFailureSummary().orElse(null),
                plugin.getMissingServices(),
                admin.isEnabledIn(plugin.getId(), groupId));
    }

    // ==================== 缓存 ====================

    /**
     * 读缓存的内容哈希。
     *
     * @param cacheKey 缓存键
     * @param field    字段名
     * @return 哈希；不存在时返回 {@code null}
     */
    public Integer loadHashCode(String cacheKey, String field) {
        // 单字段读取：取代旧实现的「hGetAll 拉回全部会话再取值」
        return ctx.getCache().hashGet(cacheKey, field, Integer.class).orElse(null);
    }

    /**
     * 写缓存的内容哈希。
     *
     * @param cacheKey 缓存键
     * @param field    字段名
     * @param hashCode 哈希值
     */
    public void saveHashCode(String cacheKey, String field, int hashCode) {
        ctx.getCache().hashSet(cacheKey, field, hashCode);
    }

    /**
     * 读缓存的图片。
     *
     * @param cacheKey 缓存键
     * @param field    字段名
     * @return 形如 {@code base64://...} 的图片引用；不存在时返回 {@code null}
     */
    public String loadImage(String cacheKey, String field) {
        return ctx.getCache().hashGet(cacheKey, field, String.class).orElse(null);
    }

    /**
     * 写缓存的图片。
     *
     * @param cacheKey 缓存键
     * @param field    字段名
     * @param image    形如 {@code base64://...} 的图片引用
     */
    public void saveImage(String cacheKey, String field, String image) {
        ctx.getCache().hashSet(cacheKey, field, image);
    }

    /**
     * 组装详情缓存字段名。
     *
     * @param position 消息位置（群/私聊标识）
     * @param pluginId 插件标识
     * @return 字段名
     */
    public static String detailField(String position, String pluginId) {
        return position + ":" + pluginId;
    }

    // ==================== 渲染 ====================

    /**
     * 读取模板、渲染为 HTML 并截屏为 Base64。
     *
     * @param templateFile 模板文件名（相对插件资源目录）
     * @param variables    模板变量名 → 值
     * @return 形如 {@code base64://...} 的图片引用；失败时返回 {@code null}
     */
    public String renderToBase64(String templateFile, Map<String, Object> variables) {
        String template = ctx.getConfigLoader().loadText(templateFile);
        Context context = new Context();
        variables.forEach(context::setVariable);
        String html = renderToHtml(template, context);
        String base64 = screenshotToBase64(html);
        return base64 == null ? null : "base64://" + base64;
    }

    /**
     * 用 Thymeleaf 把模板字符串渲染为 HTML。
     *
     * @param templateString 模板字符串
     * @param context        模板上下文
     * @return 渲染后的 HTML
     */
    public static String renderToHtml(String templateString, Context context) {
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(new StringTemplateResolver());
        return engine.process(templateString, context);
    }

    /**
     * 用无头浏览器把 HTML 截屏为 Base64。
     *
     * <p><b>注意资源释放</b>：旧实现只关闭了 {@code Playwright} 而没有关闭
     * {@code Browser}，于是每次调用都留下一个浏览器进程。这里两件都关——
     * {@code Browser} 放在 {@code try-with-resources} 里，
     * 它是 {@code AutoCloseable}。</p>
     *
     * @param html HTML 字符串
     * @return Base64 图片数据；失败时返回 {@code null}
     */
    public static String screenshotToBase64(String html) {
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true))) {
                Page page = browser.newPage();
                page.setContent(html);
                return captureElementAsBase64(page);
            }
        } catch (Exception e) {
            // 旧的 printStackTrace 不走日志框架，日志文件里看不到
            log.error("渲染插件信息图片失败", e);
            return null;
        }
    }

    /**
     * 截取结果容器并编码为 Base64。
     *
     * @param page 页面
     * @return Base64 图片数据；元素不存在或截屏失败时返回 {@code null}
     */
    public static String captureElementAsBase64(Page page) {
        try {
            Locator element = page.locator("#" + SCREENSHOT_ELEMENT_ID);
            element.waitFor(new Locator.WaitForOptions().setState(VISIBLE));
            if (element.count() == 0) {
                log.error("截图失败：找不到 id 为 {} 的元素", SCREENSHOT_ELEMENT_ID);
                return null;
            }
            byte[] bytes = element.screenshot();
            return java.util.Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.error("截取元素 {} 失败", SCREENSHOT_ELEMENT_ID, e);
            return null;
        }
    }

    /**
     * 把 HTML 截屏并保存到文件（调试用途）。
     *
     * @param html            HTML 字符串
     * @param outputFilePath  输出文件路径
     */
    public static void screenshotToFile(String html, String outputFilePath) {
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true))) {
                Page page = browser.newPage();
                page.setContent(html);
                Path output = Paths.get(outputFilePath).toAbsolutePath();
                Files.createDirectories(output.getParent());
                Locator element = page.locator("#" + SCREENSHOT_ELEMENT_ID);
                element.waitFor(new Locator.WaitForOptions().setState(VISIBLE).setTimeout(1000));
                element.screenshot(new Locator.ScreenshotOptions().setPath(output));
                log.info("已保存插件信息图片到 {}", output);
            }
        } catch (IOException | RuntimeException e) {
            log.error("保存插件信息图片失败: {}", outputFilePath, e);
        }
    }

    // ==================== 模板常量 ====================

    /**
     * 插件列表模板路径。
     *
     * @return 相对插件资源目录的模板路径
     */
    public static String templateList() {
        return TEMPLATE_LIST;
    }

    /**
     * 插件详情模板路径。
     *
     * @return 相对插件资源目录的模板路径
     */
    public static String templateDetail() {
        return TEMPLATE_DETAIL;
    }
}
