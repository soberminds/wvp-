package com.genersoft.iot.vmp.service.impl;

import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InviteStreamServiceImplTest {

    private InviteStreamServiceImpl inviteStreamService;

    @BeforeEach
    void setUp() {
        inviteStreamService = new InviteStreamServiceImpl();
        UserSetting userSetting = new UserSetting();
        userSetting.setPlayTimeout(10000);
        ReflectionTestUtils.setField(inviteStreamService, "userSetting", userSetting);
        ReflectionTestUtils.setField(inviteStreamService, "redisTemplate",
                mock(org.springframework.data.redis.core.RedisTemplate.class));
    }

    @Test
    void callbackIsInvokedOnceByMatchingStreamKey() {
        AtomicInteger invoked = new AtomicInteger();
        ErrorCallback<Object> callback = (code, msg, data) -> invoked.incrementAndGet();
        inviteStreamService.once(InviteSessionType.PLAY, "device", "channel", "stream", callback);

        inviteStreamService.call(InviteSessionType.PLAY, "device", "channel", "stream", 0, "ok", null);
        inviteStreamService.call(InviteSessionType.PLAY, "device", "channel", "stream", 0, "ok", null);

        assertEquals(1, invoked.get());
    }

    @Test
    void callbackWithoutStreamIsNotInvokedByStreamResult() {
        AtomicInteger invoked = new AtomicInteger();
        ErrorCallback<Object> callback = (code, msg, data) -> invoked.incrementAndGet();
        inviteStreamService.once(InviteSessionType.PLAY, "device", "channel", null, callback);

        inviteStreamService.call(InviteSessionType.PLAY, "device", "channel", "stream", 0, "ok", null);
        assertEquals(0, invoked.get());

        inviteStreamService.call(InviteSessionType.PLAY, "device", "channel", null, 0, "ok", null);
        assertEquals(1, invoked.get());
    }

    @Test
    void expiredCallbacksAreRemoved() {
        AtomicInteger invoked = new AtomicInteger();
        ErrorCallback<Object> callback = (code, msg, data) -> invoked.incrementAndGet();
        inviteStreamService.once(InviteSessionType.PLAY, "device", "channel", "stream", callback);

        @SuppressWarnings("unchecked")
        Map<String, Long> timeMap = (Map<String, Long>) ReflectionTestUtils.getField(inviteStreamService, "inviteErrorCallbackTimeMap");
        timeMap.replaceAll((key, value) -> 0L);

        inviteStreamService.removeExpiredInviteCallbacks();

        @SuppressWarnings("unchecked")
        Map<String, List<ErrorCallback<Object>>> callbackMap =
                (Map<String, List<ErrorCallback<Object>>>) ReflectionTestUtils.getField(inviteStreamService, "inviteErrorCallbackMap");
        assertTrue(callbackMap.isEmpty());

        inviteStreamService.call(InviteSessionType.PLAY, "device", "channel", "stream", 0, "ok", null);
        assertEquals(0, invoked.get());
    }
}
