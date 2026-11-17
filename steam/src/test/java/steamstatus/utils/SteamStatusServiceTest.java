package steamstatus.utils;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.plugin.api.ScopeQuery;
import com.yuier.yuni.plugin.api.YuniContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SteamStatusServiceTest {

    private static final long USER_ID = 2937818202L;
    private static final long MEMBER_GROUP = 100L;
    private static final long NON_MEMBER_GROUP = 200L;

    private YuniContext ctx;
    private YuniBot bot;
    private ScopeQuery scope;
    private SteamStatusService service;

    @BeforeEach
    void setUp() {
        ctx = mock(YuniContext.class);
        bot = mock(YuniBot.class);
        scope = mock(ScopeQuery.class);
        when(ctx.getBot()).thenReturn(bot);
        when(ctx.getScope()).thenReturn(scope);
        when(scope.isEnabledIn(anyLong())).thenReturn(true);
        when(bot.getGroupList()).thenReturn(Optional.of(List.of(
                group(MEMBER_GROUP), group(NON_MEMBER_GROUP))));
        service = new SteamStatusService(ctx);
    }

    @Test
    void doesNotBroadcastToGroupWhereUserIsNotMember() {
        when(bot.getGroupMemberInfo(String.valueOf(MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.of(member(MEMBER_GROUP, USER_ID, "yuier")));
        when(bot.getGroupMemberInfo(String.valueOf(NON_MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.of(placeholder(NON_MEMBER_GROUP, USER_ID)));
        when(bot.getGroupMemberList(String.valueOf(NON_MEMBER_GROUP)))
                .thenReturn(Optional.of(List.of()));

        Set<Long> groups = service.enabledGroupsOfUser(USER_ID);

        assertEquals(Set.of(MEMBER_GROUP), groups);
    }

    @Test
    void fallsBackToMemberListWhenMemberInfoIsPlaceholder() {
        when(bot.getGroupMemberInfo(String.valueOf(MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.empty());
        when(bot.getGroupMemberInfo(String.valueOf(NON_MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.of(placeholder(NON_MEMBER_GROUP, USER_ID)));
        when(bot.getGroupMemberList(String.valueOf(NON_MEMBER_GROUP)))
                .thenReturn(Optional.of(List.of(member(NON_MEMBER_GROUP, USER_ID, "yuier"))));

        Set<Long> groups = service.enabledGroupsOfUser(USER_ID);

        assertEquals(Set.of(NON_MEMBER_GROUP), groups);
    }

    @Test
    void treatsEmptyMemberInfoAsNonMemberWithoutMemberListLookup() {
        when(bot.getGroupMemberInfo(String.valueOf(MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.of(member(MEMBER_GROUP, USER_ID, "yuier")));
        when(bot.getGroupMemberInfo(String.valueOf(NON_MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.empty());

        Set<Long> groups = service.enabledGroupsOfUser(USER_ID);

        assertEquals(Set.of(MEMBER_GROUP), groups);
        verify(bot, never()).getGroupMemberList(String.valueOf(NON_MEMBER_GROUP));
    }

    @Test
    void returnsEmptyWhenMemberListLookupFails() {
        when(bot.getGroupMemberInfo(String.valueOf(MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.of(placeholder(MEMBER_GROUP, USER_ID)));
        when(bot.getGroupMemberList(String.valueOf(MEMBER_GROUP))).thenReturn(Optional.empty());
        when(bot.getGroupMemberInfo(String.valueOf(NON_MEMBER_GROUP), String.valueOf(USER_ID)))
                .thenReturn(Optional.empty());

        Set<Long> groups = service.enabledGroupsOfUser(USER_ID);

        assertTrue(groups.isEmpty());
    }

    private static BotGroupInfo group(long groupId) {
        BotGroupInfo group = new BotGroupInfo();
        group.setGroupId(groupId);
        group.setGroupName("group-" + groupId);
        return group;
    }

    private static BotGroupMemberInfo member(long groupId, long userId, String nickname) {
        BotGroupMemberInfo member = new BotGroupMemberInfo();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setNickname(nickname);
        member.setRole("member");
        return member;
    }

    private static BotGroupMemberInfo placeholder(long groupId, long userId) {
        BotGroupMemberInfo member = new BotGroupMemberInfo();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setNickname("");
        member.setRole("member");
        return member;
    }
}
