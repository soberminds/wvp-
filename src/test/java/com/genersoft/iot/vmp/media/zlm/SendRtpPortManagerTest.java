package com.genersoft.iot.vmp.media.zlm;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SendRtpPortManagerTest {

    @Test
    void returnsCurrentPortWhenItIsFree() {
        assertEquals(50001, SendRtpPortManager.findAvailablePort(50000, 50799, 50001, new HashSet<>()));
    }

    @Test
    void skipsUsedPortsAndWrapsAroundRange() {
        Set<Integer> usedPorts = new HashSet<>();
        usedPorts.add(50000);
        usedPorts.add(50001);
        usedPorts.add(50002);
        assertEquals(50003, SendRtpPortManager.findAvailablePort(50000, 50005, 50000, usedPorts));
        usedPorts.add(50003);
        usedPorts.add(50004);
        usedPorts.add(50005);
        assertEquals(-1, SendRtpPortManager.findAvailablePort(50000, 50005, 50000, usedPorts));
    }

    @Test
    void usesStartPortWhenCurrentPortIsOutsideRange() {
        assertEquals(30000, SendRtpPortManager.findAvailablePort(30000, 30010, 0, new HashSet<>()));
        assertEquals(30000, SendRtpPortManager.findAvailablePort(30000, 30010, 65535, new HashSet<>()));
    }

    @Test
    void wrapsToStartPortWhenCurrentPortIsLastOne() {
        Set<Integer> usedPorts = new HashSet<>();
        usedPorts.add(30010);
        assertEquals(30000, SendRtpPortManager.findAvailablePort(30000, 30010, 30010, usedPorts));
    }

    @Test
    void rejectsInvalidRange() {
        assertEquals(-1, SendRtpPortManager.findAvailablePort(30010, 30000, 0, new HashSet<>()));
        assertEquals(-1, SendRtpPortManager.findAvailablePort(-1, 30000, 0, new HashSet<>()));
        assertEquals(-1, SendRtpPortManager.findAvailablePort(30000, 70000, 0, new HashSet<>()));
    }
}
