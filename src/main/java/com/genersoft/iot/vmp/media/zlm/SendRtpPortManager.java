package com.genersoft.iot.vmp.media.zlm;

import com.genersoft.iot.vmp.common.VideoManagerConstants;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.gb28181.bean.SendRtpItem;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.utils.redis.RedisUtil;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.support.atomic.RedisAtomicInteger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Component
public class SendRtpPortManager {

    private final static Logger logger = LoggerFactory.getLogger(SendRtpPortManager.class);

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    private final String KEY = "VM_MEDIA_SEND_RTP_PORT_";

    public synchronized int getNextPort(MediaServer mediaServer) {
        if (mediaServer == null) {
            logger.warn("[发送端口管理] 参数错误，mediaServer为NULL");
            return -1;
        }
        String sendIndexKey = KEY + userSetting.getServerId() + "_" +  mediaServer.getId();
        String key = VideoManagerConstants.PLATFORM_SEND_RTP_INFO_PREFIX
                + userSetting.getServerId() + "_*";
        List<Object> queryResult = RedisUtil.scan(redisTemplate, key);
        Set<Integer> usedPorts = new HashSet<>();
        for (Object o : queryResult) {
            SendRtpItem sendRtpItem = (SendRtpItem) redisTemplate.opsForValue().get(o);
            if (sendRtpItem != null && sendRtpItem.getLocalPort() > 0) {
                usedPorts.add(sendRtpItem.getLocalPort());
            }
        }
        String sendRtpPortRange = mediaServer.getSendRtpPortRange();
        int startPort;
        int endPort;
        if (sendRtpPortRange != null) {
            String[] portArray = sendRtpPortRange.split(",");
            if (portArray.length != 2 || !NumberUtils.isParsable(portArray[0]) || !NumberUtils.isParsable(portArray[1])) {
                logger.warn("{}发送端口配置格式错误，自动使用50000-50799作为端口范围", mediaServer.getId());
                startPort = 50000;
                endPort = 50799;
            }else {
                if ( Integer.parseInt(portArray[1]) - Integer.parseInt(portArray[0]) < 1) {
                    logger.warn("{}发送端口配置错误,结束端口至少比开始端口大一，自动使用50000-50799作为端口范围", mediaServer.getId());
                    startPort = 50000;
                    endPort = 50799;
                }else {
                    startPort = Integer.parseInt(portArray[0]);
                    endPort = Integer.parseInt(portArray[1]);
                }
            }
        }else {
            logger.warn("{}未设置发送端口默认值，自动使用50000-50799作为端口范围", mediaServer.getId());
            startPort = 50000;
            endPort = 50799;
        }
        if (redisTemplate == null || redisTemplate.getConnectionFactory() == null) {
            logger.warn("{}获取redis连接信息失败", mediaServer.getId());
            return -1;
        }
        RedisAtomicInteger redisAtomicInteger = new RedisAtomicInteger(sendIndexKey, redisTemplate.getConnectionFactory());
        int selectedPort = findAvailablePort(startPort, endPort, redisAtomicInteger.get(), usedPorts);
        if (selectedPort < 0) {
            logger.error("[发送端口管理] 端口范围 {}-{} 已全部占用，mediaServer={}", startPort, endPort, mediaServer.getId());
            return -1;
        }
        redisAtomicInteger.set(selectedPort >= endPort ? startPort : selectedPort + 1);
        return selectedPort;
    }

    static int findAvailablePort(int startPort, int endPort, int currentPort, Set<Integer> usedPorts) {
        if (startPort <= 0 || endPort < startPort || endPort > 65535) {
            return -1;
        }
        int candidate = currentPort < startPort || currentPort > endPort ? startPort : currentPort;
        int rangeSize = endPort - startPort + 1;
        for (int i = 0; i < rangeSize; i++) {
            if (usedPorts == null || !usedPorts.contains(candidate)) {
                return candidate;
            }
            candidate = candidate == endPort ? startPort : candidate + 1;
        }
        return -1;
    }
}
