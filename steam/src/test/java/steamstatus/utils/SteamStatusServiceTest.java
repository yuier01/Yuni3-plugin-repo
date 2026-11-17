package steamstatus.utils;

import com.yuier.yuni.core.bot.BotGroupInfo;
import com.yuier.yuni.core.bot.BotGroupMemberInfo;
import com.yuier.yuni.core.bot.YuniBot;
import com.yuier.yuni.plugin.api.ScopeQuery;
import com.yuier.yuni.plugin.api.YuniContext;
import com.yuier.yuni.plugin.api.service.YuniHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import steamstatus.model.AppDetailsApiResponse;
import steamstatus.model.SinglePlayerSummary;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void cachesChineseGameNameAcrossPlayers() {
        YuniHttp http = mock(YuniHttp.class);
        YuniHttp.Request request = mock(YuniHttp.Request.class);
        AppDetailsApiResponse response = mock(AppDetailsApiResponse.class);
        when(ctx.getHttp()).thenReturn(http);
        when(http.get(anyString())).thenReturn(request);
        when(request.query(anyString(), any())).thenReturn(request);
        when(request.timeout(any(Duration.class))).thenReturn(request);
        when(request.execute(AppDetailsApiResponse.class)).thenReturn(response);
        when(response.appName("1245620")).thenReturn("艾尔登法环");

        SinglePlayerSummary first = player("1245620", "ELDEN RING");
        service.fillGameChineseName(first);
        assertEquals("艾尔登法环", first.getGameChineseName());

        SinglePlayerSummary second = player("1245620", "ELDEN RING");
        service.fillGameChineseName(second);
        assertEquals("艾尔登法环", second.getGameChineseName());
        verify(http, times(1)).get(anyString());
    }

    @Test
    void fallsBackToEnglishWhenChineseNameRequestFails() {
        YuniHttp http = mock(YuniHttp.class);
        YuniHttp.Request request = mock(YuniHttp.Request.class);
        when(ctx.getHttp()).thenReturn(http);
        when(http.get(anyString())).thenReturn(request);
        when(request.query(anyString(), any())).thenReturn(request);
        when(request.timeout(any(Duration.class))).thenReturn(request);
        when(request.execute(AppDetailsApiResponse.class)).thenThrow(
                new YuniHttp.HttpException("https://store.steampowered.com/api/appdetails",
                        -1, "Read timed out", null));

        SinglePlayerSummary player = player("1245620", "ELDEN RING");
        service.fillGameChineseName(player);

        assertEquals("ELDEN RING", player.getGameChineseName());
    }

    private static SinglePlayerSummary player(String gameId, String englishName) {
        SinglePlayerSummary player = new SinglePlayerSummary();
        player.setSteamid("76561199073791557");
        player.setGameid(gameId);
        player.setGameextrainfo(englishName);
        return player;
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
