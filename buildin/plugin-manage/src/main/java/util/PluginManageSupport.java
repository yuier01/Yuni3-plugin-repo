package util;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.yuier.yuni.plugin.api.PluginAdmin;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import static com.microsoft.playwright.options.WaitForSelectorState.VISIBLE;

/**
 * 插件管理插件的支撑服务——数据组装、模板渲染、HTML 截屏、结果缓存。
 *
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
     * <p>按 jar 分组：单插件 jar 渲染成一行，多插件 jar 渲染成一个带标题的卡片。
     * 与旧 {@code PluginShow.assemblerPluginListData} 的结构一致，区别是数据源
     * 从 {@code PluginContainer} 换成了 {@link PluginAdmin}。</p>
     *
     * @param groupId 当前消息所在群号，用于判定每个插件的生效状态
     * @return 列表元素
     */
    public List<PluginListElement> assemblePluginList(long groupId) {
        List<PluginListElement> elements = new ArrayList<>();
        Map<String, List<PluginView>> byArtifact = admin.getPlugins().stream()
                .collect(Collectors.groupingBy(
                        plugin -> plugin.getArtifactId().isBlank() ? "" : plugin.getArtifactId(),
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<PluginView>> entry : byArtifact.entrySet()) {
            List<PluginView> plugins = entry.getValue();
            if (plugins.size() == 1) {
                elements.add(new PluginListElement(toListElement(plugins.get(0), groupId)));
            } else {
                PluginListModule listModule = new PluginListModule();
                listModule.setModuleName(entry.getKey().isBlank() ? "未分组" : entry.getKey());
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
                plugin.getArtifactId(),
                plugin.getState().getDisplayName(),
                plugin.getState().name().toLowerCase(),
                plugin.getFailureSummary().orElse(null),
                plugin.getMissingServices(),
                plugin.getRequiredServices(),
                plugin.getHomepage(),
                plugin.getRepository(),
                plugin.getIcon());
    }

    // ==================== 内容哈希 ====================

    /**
     * 计算插件列表的内容哈希。
 *
     * <p>哈希决定「能不能复用上次渲染的图片」。它必须覆盖<b>所有会影响渲染结果</b>
     * 的字段——漏一个就会出现「明明变了却显示旧图」。</p>
 *
     * @param groupId 群号
     * @return 哈希值
     */
    public int hashCodeOfList(long groupId) {
        List<Integer> pluginHashes = new ArrayList<>();
        for (PluginView plugin : admin.getPlugins()) {
            pluginHashes.add(Objects.hash(
                    plugin.getName(),
                    plugin.getDescription(),
                    plugin.getState(),
                    admin.isEnabledIn(plugin.getId(), groupId)));
        }
        // 排序消除顺序影响：加载顺序变化不应导致重新渲染
        pluginHashes.sort(Integer::compareTo);
        return pluginHashes.hashCode();
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
                plugin.getArtifactId(),
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
     * 浏览器启动参数。
     *
     * <p>生产镜像使用 Alpine 的系统 Chromium，并设置
     * {@code YUNI_CHROMIUM_EXECUTABLE_PATH}；容器内通常以 root 运行，
     * 因此显式关闭 Chromium sandbox。未配置时回退到 Playwright 自带的浏览器。</p>
     *
     * @return 启动参数
     */
    private static BrowserType.LaunchOptions browserLaunchOptions() {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        String[] candidates = {
                System.getenv("YUNI_CHROMIUM_EXECUTABLE_PATH"),
                "/usr/bin/chromium-browser",
                "/usr/bin/chromium"
        };
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path executable = Paths.get(candidate);
            if (Files.isExecutable(executable)) {
                options.setExecutablePath(executable);
                options.setArgs(List.of("--no-sandbox"));
                return options;
            }
        }
        return options;
    }

    /**
     * 用无头浏览器把 HTML 截屏为 Base64。
 *
     * @param html HTML 字符串
     * @return Base64 图片数据；失败时返回 {@code null}
     */
    public static String screenshotToBase64(String html) {
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch(browserLaunchOptions())) {
                Page page = browser.newPage();
                page.setContent(html);
                return captureElementAsBase64(page);
            }
        } catch (Exception e) {
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
            try (Browser browser = playwright.chromium().launch(browserLaunchOptions())) {
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
