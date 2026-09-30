package com.genersoft.iot.vmp.conf.security;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.genersoft.iot.vmp.storager.dao.dto.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSensitiveDataSerializationTest {

    @Test
    void passwordAndPushKeyAreHiddenFromBothJsonLibraries() throws Exception {
        User user = new User();
        user.setId(7);
        user.setUsername("stcz");
        user.setPassword("secret-password-hash");
        user.setPushKey("secret-push-key");

        String jackson = new ObjectMapper().writeValueAsString(user);
        String fastjson = JSON.toJSONString(user);

        assertTrue(jackson.contains("stcz"));
        assertTrue(fastjson.contains("stcz"));
        assertFalse(jackson.contains("secret-password-hash"));
        assertFalse(jackson.contains("secret-push-key"));
        assertFalse(fastjson.contains("secret-password-hash"));
        assertFalse(fastjson.contains("secret-push-key"));
    }
}
