package guessnumber;

import com.yuier.yuni.core.event.YuniMessageEvent;
import com.yuier.yuni.event.detector.message.command.model.CommandNode;
import com.yuier.yuni.plugin.api.Commands;
import com.yuier.yuni.plugin.api.PluginDeclaration;
import com.yuier.yuni.plugin.api.Reply;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.YuniPlugin;
import com.yuier.yuni.plugin.api.session.SessionAction;
import com.yuier.yuni.plugin.api.session.SessionCloseEvent;
import com.yuier.yuni.plugin.api.session.SessionConflictException;
import com.yuier.yuni.plugin.api.session.SessionContext;
import com.yuier.yuni.plugin.api.session.SessionLimitExceededException;
import com.yuier.yuni.plugin.api.session.SessionSpec;
import com.yuier.yuni.plugin.api.session.SessionTarget;
import com.yuier.yuni.plugin.api.session.SessionUnavailableException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 长会话示例：群内部分群友多轮猜数字。
 *
 * <p>演示以下能力：</p>
 * <ul>
 *   <li>{@code GROUP_PARTICIPANTS} + 独有 {@code channelKey}；</li>
 *   <li>初始只有发起者，其他人必须发送该通道独有的 {@code /加入 猜数字}；</li>
 *   <li>参与者直接发数字，消息不会再进入其他插件；</li>
 *   <li>可通过 {@code /退出 猜数字} 离开；最后一人离开或猜中时关闭通道；</li>
 *   <li>参与者加入/离开/超时关闭的回调。</li>
 * </ul>
 */
public final class GuessNumber implements YuniPlugin {

    /** 同一群内区分本通道的唯一键。 */
    private static final String CHANNEL_KEY = "guess-number";

    /** 本通道独有的加入/退出指令。 */
    private static final String JOIN_COMMAND = "/加入 猜数字";
    private static final String EXIT_COMMAND = "/退出 猜数字";

    /** 猜数范围。 */
    private static final int MIN = 1;
    private static final int MAX = 100;

    @Override
    public PluginDeclaration getDeclaration() {
        return PluginDeclaration.of("GuessNumber").build();
    }

    @Override
    public void apply(YuniContext ctx) {
        ctx.command(Commands.on(
                CommandNode.builder("猜数字")
                        .description("和机器人玩多轮猜数字")
                        .build(),
                (event, result, reply) -> start(ctx, event, reply)));
    }

    private void start(YuniContext ctx, YuniMessageEvent event, Reply reply) {
        if (!event.isGroup()) {
            reply.text("这个游戏只在群里玩。").send();
            return;
        }
        long opener = event.getUserId();
        long groupId = event.getGroupId();
        SessionTarget target = SessionTarget.groupParticipants(groupId, CHANNEL_KEY);

        SessionSpec spec = SessionSpec.builder(target)
                .tag("guess-number")
                .opener(opener)
                // 初始只有发起者；其他人必须 /加入 猜数字
                .initialParticipants(Set.of(opener))
                .joinCommands(JOIN_COMMAND)
                .exitEnabled(true)
                .exitCommands(EXIT_COMMAND)
                .maxParticipants(30)
                .maxTurns(200)
                .idleTimeout(Duration.ofMinutes(5))
                .maxDuration(Duration.ofMinutes(30))
                .onParticipantJoin((session, userId) -> sendGroup(ctx, groupId,
                        "用户 " + userId + " 加入，当前 " + session.getParticipantCount() + " 人。"))
                .onParticipantLeave((session, userId) -> {
                    if (session.getParticipantCount() > 0) {
                        sendGroup(ctx, groupId,
                                "用户 " + userId + " 离开，当前 " + session.getParticipantCount() + " 人。");
                    }
                })
                .onClose(close -> notifyClose(ctx, groupId, close))
                .build();

        try {
            ctx.openSession(spec, this::onGuess);
            // 先开会话，再发提示：用户看到提示时通道已经生效
            reply.text("我想了一个 " + MIN + "~" + MAX + " 的整数。"
                    + "发送 " + JOIN_COMMAND + " 参与，发送 " + EXIT_COMMAND + " 离开。").send();
        } catch (SessionConflictException e) {
            reply.text("这个群已经有猜数字通道，或加入指令正被其他通道占用。").send();
        } catch (SessionLimitExceededException e) {
            reply.text("当前游戏太多了，稍后再试。").send();
        } catch (SessionUnavailableException e) {
            reply.text("长会话功能暂不可用。").send();
        }
    }

    private SessionAction onGuess(SessionContext session,
                                  YuniMessageEvent event,
                                  Reply reply) {
        GameState state = session.state(GameState.class);
        String text = event.getChain().getPlainText().strip();

        int guess;
        try {
            guess = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            reply.text("请输入 " + MIN + "~" + MAX + " 的整数，"
                    + "或发送 " + EXIT_COMMAND + " 离开。").send();
            return SessionAction.CONSUMED;
        }
        if (guess < MIN || guess > MAX) {
            reply.text("请输入 " + MIN + "~" + MAX + " 的整数。").send();
            return SessionAction.CONSUMED;
        }

        state.turns++;
        if (guess < state.answer) {
            reply.text("小了～").send();
            return SessionAction.CONSUMED;
        }
        if (guess > state.answer) {
            reply.text("大了～").send();
            return SessionAction.CONSUMED;
        }
        reply.text("猜对了！答案就是 " + state.answer
                + "，你用了 " + state.turns + " 次。").send();
        return SessionAction.CLOSE;
    }

    private void notifyClose(YuniContext ctx, long groupId, SessionCloseEvent close) {
        String message = switch (close.reason()) {
            case NO_PARTICIPANTS -> "最后一名参与者已离开，猜数字游戏结束。";
            case IDLE_TIMEOUT -> "猜数字游戏超时，已结束。";
            case MAX_DURATION -> "猜数字游戏达到最长时长，已结束。";
            case TURN_LIMIT -> "猜数字游戏达到最大轮数，已结束。";
            case ERROR_LIMIT -> "猜数字游戏出错，已结束。";
            default -> null;
        };
        if (message != null) {
            sendGroup(ctx, groupId, message);
        }
    }

    private static void sendGroup(YuniContext ctx, long groupId, String text) {
        ctx.getSender().group(groupId).text(text).send();
    }

    /**
     * 会话内状态：public static + 可访问无参构造器。
     */
    public static final class GameState {

        private final int answer =
                ThreadLocalRandom.current().nextInt(MIN, MAX + 1);
        private int turns;
    }
}
