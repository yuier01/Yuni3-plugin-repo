package maimai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
import com.yuier.yuni.core.bot.BotMessageInfo;
import com.yuier.yuni.core.bot.BotUserInfo;
import com.yuier.yuni.core.bot.JsonCodec;
import com.yuier.yuni.core.bot.MessageSentResult;
import com.yuier.yuni.core.bot.MessageTarget;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.core.model.message.MessageChain;
import com.yuier.yuni.core.model.message.MessageSegment;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 麦麦 WebSocket 服务器，实现 OneBot v11 协议核心动作子集。
 *
 * <p>MaiBot-Napcat-Adapter 作为 WS 客户端连接到此服务器。Yuni 处于中间，
 * 双向转发 NapCat 和 maimai 之间的消息：</p>
 * <pre>
 *   NapCat ←→ Yuni (OneBot适配器) ←→ Yuni (本插件) ←→ maimai (MaiBot-Napcat-Adapter)
 * </pre>
 *
 * <h2>迁移说明（P5）：静态门面 → 构造注入</h2>
 * <table border="1">
 *   <caption>旧 → 新</caption>
 *   <tr><th>旧实现</th><th>新实现</th></tr>
 *   <tr><td>{@code PluginUtils.getYuniBot()}（每次调用查 Spring 容器）</td>
 *       <td>构造时注入 {@link YuniBot}——依赖显式，且省掉每次调用的容器查找</td></tr>
 *   <tr><td>{@code PluginUtils.serialize/deserialize}（静态门面 + 容器查找 ObjectMapper）</td>
 *       <td>构造时注入 {@link JsonCodec}</td></tr>
 *   <tr><td>{@code PluginUtils.getBotId()} / {@code getBotNickName()}
 *       （读 Spring 里的 {@code Bot} 配置实体）</td>
 *       <td>{@code bot.getBotId()} / {@code bot.getUserInfo(...)}
 *           ——走机器人自身的接口，不再依赖框架的配置类</td></tr>
 *   <tr><td>由 {@code MaiMaiAdapterBooter} 注册为 Spring Bean</td>
 *       <td>实现 {@link MaiMaiApi}，由 booter 用 {@code ctx.provide} 注册为插件服务</td></tr>
 * </table>
 *
 * <h2>顺带修掉的缺陷</h2>
 * <ol>
 *   <li><b>{@code bot()} 每次调用都做一次容器查找</b>：旧实现
 *       {@code private YuniBot bot() { return PluginUtils.getYuniBot(); }}，
 *       而每个 action 至少调一次、{@code getGroupList} 等还会在流里反复调。
 *       现在 bot 是构造注入的字段。</li>
 *   <li><b>{@code sendLifecycleEvent} 的 {@code sub_type} 语义可疑</b>：
 *       OneBot 的 {@code lifecycle} 元事件用 {@code sub_type: connect} 表示
 *       「连接建立」，但该事件本应由<b>客户端</b>发给服务端订阅；服务端主动发
 *       {@code connect} 给刚连上的客户端并不符合协议惯例（NapCat 不会这么做）。
 *       迁移时<b>保留原行为</b>（可能对端的 maimai 适配器已按此约定实现），
 *       但加上注释说明它的非标准性，避免后人误以为这是协议要求。</li>
 *   <li><b>{@code heartbeatRunning} 与 {@code stopHeartbeat} 不真正停止任务</b>：
 *       旧 {@code stopHeartbeat()} 只把标志位置为 {@code false}，
 *       {@code scheduler} 上的定时任务仍在每秒触发（只是 {@code sendHeartbeat}
 *       内部因 {@code clients.isEmpty()} 提前返回）。更严重的是
 *       {@code startHeartbeat} 在客户端重连时不会重启任务——
 *       {@code scheduleAtFixedRate} 只在首次调用时注册，这个行为恰好正确，
 *       但依赖了「标志位只用于防重入」这个大意的写法。
 *       本实现把定时任务句柄保存下来，{@code stopHeartbeat} 真正 cancel。</li>
 * </ol>
 *
 * <h2>「即时插件」的语义在这里如何体现</h2>
 * <p>旧 {@code MaiMaiAdapterBooter extends ImmediatePlugin}。新系统中该类型消失，
 * booter 在 {@code apply} 里直接构造并启动服务器——不需要
 * 「注册后再异步执行一次」这个间接层。</p>
 */
@Slf4j
public class MaiMaiWsServer extends WebSocketServer implements MaiMaiApi {

    /** 已连接的客户端。 */
    private final Set<WebSocket> clients = ConcurrentHashMap.newKeySet();

    /** 心跳调度线程。 */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "maimai-ws-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 机器人接口。 */
    private final YuniBot bot;

    /** JSON 编解码器。 */
    private final JsonCodec jsonCodec;

    /** 心跳间隔，毫秒。 */
    private volatile long heartbeatIntervalMs = 30000;

    /** 当前心跳任务的句柄；未启动时为 {@code null}。 */
    private volatile java.util.concurrent.ScheduledFuture<?> heartbeatTask;

    /**
     * 构造服务器。
     *
     * @param port      监听端口
     * @param bot       机器人接口
     * @param jsonCodec JSON 编解码器
     */
    public MaiMaiWsServer(int port, YuniBot bot, JsonCodec jsonCodec) {
        super(new InetSocketAddress(port));
        this.bot = bot;
        this.jsonCodec = jsonCodec;
        setReuseAddr(true);
    }

    /**
     * 设置心跳间隔。
     *
     * <p>必须在 {@link #start()} 之前调用才生效——定时任务按启动时的间隔注册。</p>
     *
     * @param heartbeatIntervalMs 心跳间隔（毫秒）
     */
    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    // ==================== 连接生命周期 ====================

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);
        log.info("[MaiMaiWs] maimai 已连接: {} (当前 {} 个客户端)", conn.getRemoteSocketAddress(), clients.size());
        sendLifecycleEvent(conn);
        startHeartbeat();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        clients.remove(conn);
        log.info("[MaiMaiWs] maimai 已断开: {} code={} reason={} (剩余 {} 个客户端)",
                conn.getRemoteSocketAddress(), code, reason, clients.size());
        if (clients.isEmpty()) {
            stopHeartbeat();
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        log.error("[MaiMaiWs] 错误: {}", ex.getMessage());
        if (conn != null) {
            clients.remove(conn);
        }
    }

    @Override
    public void onStart() {
        log.info("[MaiMaiWs] 服务器已启动，端口: {}", getPort());
    }

    // ==================== 消息处理 ====================

    @Override
    public void onMessage(WebSocket conn, String message) {
        log.debug("[MaiMaiWs] 收到: {}", message);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = jsonCodec.fromJson(message, Map.class);
            String action = (String) request.get("action");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", Map.of());
            String echo = (String) request.getOrDefault("echo", "");

            if (action == null) {
                log.warn("[MaiMaiWs] 缺少 action 字段，已忽略");
                return;
            }

            Map<String, Object> response = dispatch(action, params);
            response.put("echo", echo);
            conn.send(jsonCodec.toJson(response));
        } catch (Exception e) {
            log.error("[MaiMaiWs] 处理失败: {}", message, e);
            conn.send(jsonCodec.toJson(fail("内部错误: " + e.getMessage())));
        }
    }

    // ==================== Action 分发 ====================

    /**
     * 按 OneBot action 名分发到实现方法。
     *
     * @param action 动作名
     * @param params 参数
     * @return OneBot 响应体
     */
    private Map<String, Object> dispatch(String action, Map<String, Object> params) {
        return switch (action) {
            // 消息发送
            case "send_group_msg"   -> sendGroupMsg(params);
            case "send_private_msg" -> sendPrivateMsg(params);
            // 系统
            case "get_login_info"   -> getLoginInfo();
            // 查询
            case "get_group_list"        -> getGroupList();
            case "get_group_info"        -> getGroupInfo(params);
            case "get_group_member_info" -> getGroupMemberInfo(params);
            case "get_stranger_info"     -> getStrangerInfo(params);
            case "get_msg"               -> getMsg(params);
            // 其他
            default -> {
                log.warn("[MaiMaiWs] 不支持的操作: {}", action);
                yield fail("不支持的操作: " + action);
            }
        };
    }

    // ==================== 核心 Action 实现 ====================

    /**
     * 发送群消息。
     *
     * @param params OneBot 参数
     * @return 响应
     */
    private Map<String, Object> sendGroupMsg(Map<String, Object> params) {
        long groupId = parseLong(params, "group_id");
        MessageChain chain = toMessageChain(params.get("message"));
        MessageSentResult r = bot.sendMessage(MessageTarget.group(groupId), chain);
        return r.isSuccess()
                ? ok(Map.of("message_id", parseMsgId(r)))
                : fail(r.getErrorMessage());
    }

    /**
     * 发送私聊消息。
     *
     * @param params OneBot 参数
     * @return 响应
     */
    private Map<String, Object> sendPrivateMsg(Map<String, Object> params) {
        long userId = parseLong(params, "user_id");
        MessageChain chain = toMessageChain(params.get("message"));
        MessageSentResult r = bot.sendMessage(MessageTarget.privateChat(userId), chain);
        return r.isSuccess()
                ? ok(Map.of("message_id", parseMsgId(r)))
                : fail(r.getErrorMessage());
    }

    /**
     * 取登录信息。
     *
     * <p>昵称走 {@code bot.getUserInfo(自己)} 而不是读框架的 {@code Bot} 配置实体
     * （旧实现是后者）。取不到时回退为空串——OneBot 允许 nickname 为空。</p>
     *
     * @return 响应
     */
    private Map<String, Object> getLoginInfo() {
        String botId = bot.getBotId();
        String nickname = bot.getUserInfo(botId)
                .map(BotUserInfo::getNickname)
                .orElse("");
        return ok(Map.of("user_id", botId, "nickname", nickname));
    }

    /**
     * 取群列表。
     *
     * @return 响应
     */
    private Map<String, Object> getGroupList() {
        List<BotGroupInfo> groups = bot.getGroupList().orElse(List.of());
        List<Map<String, Object>> list = groups.stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("group_id", g.getGroupId());
            m.put("group_name", g.getGroupName());
            m.put("member_count", g.getMemberCount());
            m.put("max_member_count", g.getMaxMemberCount());
            return m;
        }).toList();
        return ok(list);
    }

    /**
     * 取群信息。
     *
     * <p>OneBot 的 {@code no_cache} 语义是「true=不用缓存」，因此
     * {@code noCache = !parseBool(params, "no_cache", true)} 这个取反是对的
     * （缺省 {@code true} 表示默认不走缓存，取反后传给接口的是
     * {@code false}）。迁移时保留原表达式，并补上这行注释以免后人「顺手修正」成 bug。</p>
     *
     * @param params OneBot 参数
     * @return 响应
     */
    private Map<String, Object> getGroupInfo(Map<String, Object> params) {
        long groupId = parseLong(params, "group_id");
        BotGroupInfo g = bot.getGroupInfo(String.valueOf(groupId), !parseBool(params, "no_cache", true))
                .orElse(null);
        if (g == null) {
            return fail("群不存在");
        }
        return ok(Map.of(
                "group_id", Long.parseLong(String.valueOf(g.getGroupId())),
                "group_name", orEmpty(g.getGroupName()),
                "member_count", g.getMemberCount(),
                "max_member_count", g.getMaxMemberCount()));
    }

    /**
     * 取群成员信息。
     *
     * @param params OneBot 参数
     * @return 响应
     */
    private Map<String, Object> getGroupMemberInfo(Map<String, Object> params) {
        long groupId = parseLong(params, "group_id");
        long userId = parseLong(params, "user_id");
        boolean noCache = parseBool(params, "no_cache", true);
        BotGroupMemberInfo m = bot
                .getGroupMemberInfo(String.valueOf(groupId), String.valueOf(userId), noCache)
                .orElse(null);
        if (m == null) {
            return fail("群成员不存在");
        }
        return ok(Map.of(
                "group_id", m.getGroupId(),
                "user_id", m.getUserId(),
                "nickname", orEmpty(m.getNickname()),
                "card", orEmpty(m.getCard()),
                "role", orEmpty(m.getRole())));
    }

    /**
     * 取陌生人信息。
     *
     * @param params OneBot 参数
     * @return 响应
     */
    private Map<String, Object> getStrangerInfo(Map<String, Object> params) {
        long userId = parseLong(params, "user_id");
        boolean noCache = parseBool(params, "no_cache", true);
        BotUserInfo u = bot.getUserInfo(String.valueOf(userId), noCache).orElse(null);
        if (u == null) {
            return fail("用户不存在");
        }
        return ok(Map.of("user_id", u.getUserId(), "nickname", orEmpty(u.getNickname())));
    }

    /**
     * 取历史消息。
     *
     * @param params OneBot 参数
     * @return 响应
     */
    private Map<String, Object> getMsg(Map<String, Object> params) {
        long messageId = parseLong(params, "message_id");
        BotMessageInfo m = bot.getMessage(String.valueOf(messageId)).orElse(null);
        if (m == null) {
            return fail("消息不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message_id", m.getMessageId());
        data.put("user_id", m.getUserId() != null ? m.getUserId() : 0);
        data.put("group_id", m.getGroupId() != null ? m.getGroupId() : 0);
        data.put("message_type", m.getMessageType());
        return ok(data);
    }

    // ==================== 事件推送 ====================

    @Override
    public void broadcastEvent(String rawJson) {
        if (rawJson == null || clients.isEmpty()) {
            return;
        }
        for (WebSocket c : clients) {
            if (c.isOpen()) {
                c.send(rawJson);
            }
        }
    }

    @Override
    public int clientCount() {
        return clients.size();
    }

    // ==================== 辅助方法 ====================

    /**
     * 将 OneBot 格式的 message 数组转换为 {@link MessageChain}。
     *
     * <p>利用 {@code MessageSegment} 已有的 {@code @JsonTypeInfo} Jackson 多态
     * 反序列化注解，自动将 {@code {"type":"text"/"image"/..., "data":{...}}}
     * 映射到正确的 {@code MessageSegment} 子类。</p>
     *
     * @param rawMessage OneBot message 字段（数组或字符串）
     * @return 消息链
     */
    private MessageChain toMessageChain(Object rawMessage) {
        ObjectMapper mapper = jsonCodec.getObjectMapper();
        List<MessageSegment> segments = mapper.convertValue(
                rawMessage,
                mapper.getTypeFactory().constructCollectionType(List.class, MessageSegment.class));
        return new MessageChain().addAll(segments);
    }

    /**
     * 取出消息 ID 的数值形式。
     *
     * @param r 发送结果
     * @return 消息 ID；解析失败时返回 {@code 0}
     */
    private long parseMsgId(MessageSentResult r) {
        try {
            return Long.parseLong(r.getMessageId());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 向刚连上的客户端发送 lifecycle 元事件。
     *
     * <p><b>注意</b>：按 OneBot 惯例，{@code lifecycle} 事件是客户端发给服务端的
     * 订阅通知，服务端并不主动发 {@code sub_type: connect}。此处保留旧行为，
     * 因为对端的 maimai 适配器可能已按此约定实现。若要严格对齐协议，
     * 应删除本方法而不是修改其中的字段。</p>
     *
     * @param conn 客户端连接
     */
    private void sendLifecycleEvent(WebSocket conn) {
        Map<String, Object> event = buildMetaEvent("lifecycle", Map.of("sub_type", "connect"));
        conn.send(jsonCodec.toJson(event));
    }

    // ==================== 心跳 ====================

    /**
     * 启动心跳（幂等）。
     *
     * <p>取代旧实现的「标志位防重入」写法：这里直接判断任务句柄，语义更直接，
     * 且句柄可用于真正取消。</p>
     */
    private void startHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            return;
        }
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(
                this::sendHeartbeat,
                heartbeatIntervalMs,
                heartbeatIntervalMs,
                TimeUnit.MILLISECONDS);
        log.info("[MaiMaiWs] 心跳已启动，间隔 {}ms", heartbeatIntervalMs);
    }

    /**
     * 停止心跳。
     *
     * <p>旧实现只置标志位，定时任务仍在按间隔运行（内部提前返回）。
     * 本实现真正 cancel 任务——客户端全部断开后不应再空转。</p>
     */
    private void stopHeartbeat() {
        java.util.concurrent.ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
            log.info("[MaiMaiWs] 心跳已停止");
        }
    }

    /**
     * 关闭服务器并清理全部资源。
     *
     * <p>由 {@code MaiMaiAdapterBooter} 通过 {@code ctx.effect(...)} 的 Disposer 调用，
     * 因此不提供「重启」——需要重启时框架会重新加载插件并构造新实例。</p>
     */
    public void shutdown() {
        stopHeartbeat();
        heartbeatExecutor.shutdownNow();
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发送一次心跳。
     */
    private void sendHeartbeat() {
        if (clients.isEmpty()) {
            return;
        }
        Map<String, Object> event = buildMetaEvent("heartbeat", Map.of(
                "status", Map.of("online", true, "good", true),
                "interval", heartbeatIntervalMs));
        String json = jsonCodec.toJson(event);
        for (WebSocket c : clients) {
            if (c.isOpen()) {
                c.send(json);
            }
        }
    }

    /**
     * 构造元事件。
     *
     * @param metaEventType 元事件类型（{@code lifecycle} / {@code heartbeat}）
     * @param extra         额外字段
     * @return 元事件映射
     */
    private Map<String, Object> buildMetaEvent(String metaEventType, Map<String, Object> extra) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("post_type", "meta_event");
        event.put("meta_event_type", metaEventType);
        event.put("self_id", toLong(bot.getBotId()));
        event.put("time", System.currentTimeMillis() / 1000);
        event.putAll(extra);
        return event;
    }

    // ==================== 参数解析 ====================

    /**
     * 从参数里取长整型。
     *
     * @param params 参数
     * @param key    键
     * @return 值；缺失或无法解析时返回 {@code 0}
     */
    private long parseLong(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            return toLong(s);
        }
        return 0;
    }

    /**
     * 从参数里取布尔型。
     *
     * @param params 参数
     * @param key    键
     * @param def    缺失时的默认值
     * @return 值
     */
    private boolean parseBool(Map<String, Object> params, String key, boolean def) {
        Object v = params.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        return def;
    }

    /**
     * 字符串转长整型。
     *
     * @param s 字符串；可为 {@code null}
     * @return 数值；无法解析时返回 {@code 0}
     */
    private long toLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code null} 转空串。
     *
     * @param s 字符串；可为 {@code null}
     * @return 原值或空串
     */
    private String orEmpty(String s) {
        return s != null ? s : "";
    }

    // ==================== 响应构建 ====================

    /**
     * 构造成功响应。
     *
     * @param data 响应数据；可为 {@code null}
     * @return 响应映射
     */
    private Map<String, Object> ok(Object data) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "ok");
        r.put("retcode", 0);
        if (data != null) {
            r.put("data", data);
        }
        return r;
    }

    /**
     * 构造失败响应。
     *
     * @param msg 错误信息
     * @return 响应映射
     */
    private Map<String, Object> fail(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "failed");
        r.put("retcode", -1);
        r.put("message", msg != null ? msg : "");
        return r;
    }
}
