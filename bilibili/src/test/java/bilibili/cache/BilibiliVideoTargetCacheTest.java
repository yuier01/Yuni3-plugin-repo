package bilibili.cache;

import bilibili.parser.BilibiliVideoTarget;
import com.yuier.yuni.core.event.YuniMessageEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliVideoTargetCacheTest {

    @Test
    void storesAndTakesTargetByMessage() {
        BilibiliVideoTargetCache cache = new BilibiliVideoTargetCache();
        YuniMessageEvent event = event(1001L, 2002L, 3003L);
        BilibiliVideoTarget target = BilibiliVideoTarget.ofBvId("BV1xx411c7mD", 1);

        cache.put(event, target);

        assertEquals(Optional.of(target), cache.take(event));
        assertTrue(cache.take(event).isEmpty());
    }

    @Test
    void ignoresEventsWithoutMessageId() {
        BilibiliVideoTargetCache cache = new BilibiliVideoTargetCache();
        YuniMessageEvent event = event(null, 2002L, 3003L);

        cache.put(event, BilibiliVideoTarget.ofBvId("BV1xx411c7mD", 1));

        assertTrue(cache.take(event).isEmpty());
    }

    private static YuniMessageEvent event(Long messageId, Long groupId, Long userId) {
        YuniMessageEvent event = new YuniMessageEvent();
        event.setMessageId(messageId);
        event.setGroupId(groupId);
        event.setUserId(userId);
        return event;
    }
}
