package com.genersoft.iot.vmp.conf;

import com.genersoft.iot.vmp.common.InviteInfo;
import com.genersoft.iot.vmp.common.InviteSessionType;
import com.genersoft.iot.vmp.gb28181.bean.Device;
import com.genersoft.iot.vmp.gb28181.bean.SsrcTransaction;
import com.genersoft.iot.vmp.gb28181.session.VideoStreamSessionManager;
import com.genersoft.iot.vmp.gb28181.transmit.cmd.ISIPCommander;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.service.IDeviceChannelService;
import com.genersoft.iot.vmp.service.IInviteStreamService;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.storager.IVideoManagerStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 定时向zlm同步媒体流状态
 * 对账WVP中记录的点播/回放/下载事务，如果ZLM中对应的流已经不存在（连续多轮确认），
 * 说明该流已经失效，进行SSRC、RTP Server端口回收和通道播放状态标记，避免残留会话与端口占用。
 */
@Component
public class MediaStatusTimerTask {

    private final static Logger logger = LoggerFactory.getLogger(MediaStatusTimerTask.class);

    /**
     * 连续多少轮检测不到流才判定为失效，避免ZLM接口瞬时异常误杀正常播放的流
     */
    private final static int MISS_THRESHOLD = 3;

    private final Map<String, Integer> missingCountMap = new ConcurrentHashMap<>();

    @Autowired
    private VideoStreamSessionManager streamSession;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private IInviteStreamService inviteStreamService;

    @Autowired
    private IVideoManagerStorage storager;

    @Autowired
    private IDeviceChannelService channelService;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Autowired
    private ISIPCommander commander;

    @Scheduled(fixedDelay = 30 * 1000)
    public void execute() {
        List<SsrcTransaction> ssrcTransactions;
        try {
            ssrcTransactions = streamSession.getAllSsrc();
        } catch (Exception e) {
            logger.error("[媒体状态同步] 查询点播事务失败: {}", e.getMessage());
            return;
        }
        if (ssrcTransactions == null || ssrcTransactions.isEmpty()) {
            missingCountMap.clear();
            return;
        }
        for (SsrcTransaction ssrcTransaction : ssrcTransactions) {
            if (ssrcTransaction == null || ssrcTransaction.getStream() == null
                    || ssrcTransaction.getMediaServerId() == null || !isInviteSessionTypeSupported(ssrcTransaction.getType())) {
                continue;
            }
            String key = ssrcTransaction.getDeviceId() + ":" + ssrcTransaction.getChannelId() + ":"
                    + ssrcTransaction.getStream() + ":" + ssrcTransaction.getCallId();
            MediaServer mediaServerItem = mediaServerService.getOne(ssrcTransaction.getMediaServerId());
            if (mediaServerItem == null) {
                // 节点信息查不到，无法对账，保留原有状态
                missingCountMap.remove(key);
                continue;
            }
            boolean streamExists;
            try {
                streamExists = mediaServerService.getMediaInfo(mediaServerItem, "rtp", ssrcTransaction.getStream()) != null;
            } catch (Exception e) {
                logger.warn("[媒体状态同步] 查询ZLM流状态失败 stream: {}, {}", ssrcTransaction.getStream(), e.getMessage());
                continue;
            }
            if (streamExists) {
                missingCountMap.remove(key);
                continue;
            }
            int missCount = missingCountMap.merge(key, 1, Integer::sum);
            logger.info("[媒体状态同步] ZLM中未找到流 stream: {}, 第{}次检测", ssrcTransaction.getStream(), missCount);
            if (missCount >= MISS_THRESHOLD) {
                missingCountMap.remove(key);
                releaseInvalidStream(ssrcTransaction, mediaServerItem);
            }
        }
    }

    private boolean isInviteSessionTypeSupported(InviteSessionType type) {
        return type == InviteSessionType.PLAY || type == InviteSessionType.PLAYBACK || type == InviteSessionType.DOWNLOAD;
    }

    private void releaseInvalidStream(SsrcTransaction ssrcTransaction, MediaServer mediaServerItem) {
        logger.warn("[媒体状态同步] 检测到失效流，回收资源 deviceId: {}, channelId: {}, stream: {}, ssrc: {}, mediaServer: {}",
                ssrcTransaction.getDeviceId(), ssrcTransaction.getChannelId(), ssrcTransaction.getStream(),
                ssrcTransaction.getSsrc(), ssrcTransaction.getMediaServerId());
        try {
            if (ssrcTransaction.getSsrc() != null) {
                mediaServerService.releaseSsrc(ssrcTransaction.getMediaServerId(), ssrcTransaction.getSsrc());
            }
        } catch (Exception e) {
            logger.warn("[媒体状态同步] 释放SSRC失败 stream: {}, {}", ssrcTransaction.getStream(), e.getMessage());
        }
        try {
            mediaServerService.closeRTPServer(mediaServerItem, ssrcTransaction.getStream());
        } catch (Exception e) {
            logger.warn("[媒体状态同步] 关闭RTP Server失败 stream: {}, {}", ssrcTransaction.getStream(), e.getMessage());
        }
        try {
            streamSession.removeByCallId(ssrcTransaction.getDeviceId(), ssrcTransaction.getChannelId(), ssrcTransaction.getCallId());
        } catch (Exception e) {
            logger.warn("[媒体状态同步] 移除点播事务失败 stream: {}, {}", ssrcTransaction.getStream(), e.getMessage());
        }
        try {
            InviteInfo inviteInfo = inviteStreamService.getInviteInfoByStream(ssrcTransaction.getType(), ssrcTransaction.getStream());
            if (inviteInfo != null) {
                inviteStreamService.removeInviteInfo(inviteInfo);
            }
        } catch (Exception e) {
            logger.warn("[媒体状态同步] 移除点播会话失败 stream: {}, {}", ssrcTransaction.getStream(), e.getMessage());
        }
        try {
            if (ssrcTransaction.getDeviceId() != null && ssrcTransaction.getChannelId() != null) {
                storager.stopPlay(ssrcTransaction.getDeviceId(), ssrcTransaction.getChannelId());
                channelService.stopPlay(ssrcTransaction.getDeviceId(), ssrcTransaction.getChannelId());
            }
        } catch (Exception e) {
            logger.warn("[媒体状态同步] 标记通道停止播放失败 channel: {}, {}", ssrcTransaction.getChannelId(), e.getMessage());
        }
        Device device = redisCatchStorage.getDevice(ssrcTransaction.getDeviceId());
        if (device != null && device.isOnLine()) {
            try {
                commander.streamByeCmd(device, ssrcTransaction.getChannelId(), ssrcTransaction.getStream(), null);
            } catch (Exception e) {
                logger.error("[媒体状态同步] 发送BYE失败 {}", e.getMessage());
            }
        }
    }
}
