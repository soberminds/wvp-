package com.genersoft.iot.vmp.media.bean;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OnStreamChangedHookParam;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaInfoTest {

    @Test
    void returnsNullWhenTracksAreMissing() {
        JSONObject mediaData = new JSONObject();
        mediaData.put("app", "rtp");
        mediaData.put("stream", "channel_stream");
        assertNull(MediaInfo.getInstance(mediaData, null));
    }

    @Test
    void parsesReadyVideoTrackAndWidth() {
        JSONObject mediaData = mediaDataWithTrack(0, true);
        MediaInfo mediaInfo = MediaInfo.getInstance(mediaData, null);
        assertEquals("H264", mediaInfo.getVideoCodec());
        assertEquals(Integer.valueOf(1920), mediaInfo.getWidth());
        assertEquals(Integer.valueOf(1080), mediaInfo.getHeight());
        assertTrue(mediaInfo.hasUsableVideoTrack());
    }

    @Test
    void trackThatIsNotReadyIsNotUsable() {
        MediaInfo mediaInfo = MediaInfo.getInstance(mediaDataWithTrack(0, false), null);
        assertEquals("H264", mediaInfo.getVideoCodec());
        assertFalse(mediaInfo.hasUsableVideoTrack());
    }

    @Test
    void audioOnlyStreamIsNotUsableAsVideo() {
        MediaInfo mediaInfo = MediaInfo.getInstance(mediaDataWithTrack(2, true), null);
        assertFalse(mediaInfo.hasUsableVideoTrack());
    }

    @Test
    void hookParamWithoutTracksHasNoUsableVideoTrack() {
        OnStreamChangedHookParam param = new OnStreamChangedHookParam();
        param.setApp("rtp");
        param.setStream("channel_stream");
        MediaInfo mediaInfo = MediaInfo.getInstance(param, null);
        assertFalse(mediaInfo.hasUsableVideoTrack());
    }

    @Test
    void hookParamWithReadyVideoTrackIsUsable() {
        OnStreamChangedHookParam param = new OnStreamChangedHookParam();
        param.setApp("rtp");
        param.setStream("channel_stream");
        OnStreamChangedHookParam.MediaTrack mediaTrack = new OnStreamChangedHookParam.MediaTrack();
        mediaTrack.setCodec_id(0);
        mediaTrack.setReady(true);
        mediaTrack.setWidth(1920);
        mediaTrack.setHeight(1080);
        param.setTracks(Collections.singletonList(mediaTrack));
        MediaInfo mediaInfo = MediaInfo.getInstance(param, null);
        assertEquals("H264", mediaInfo.getVideoCodec());
        assertEquals(Integer.valueOf(1920), mediaInfo.getWidth());
        assertTrue(mediaInfo.hasUsableVideoTrack());
    }

    private JSONObject mediaDataWithTrack(int codecId, boolean ready) {
        JSONObject track = new JSONObject();
        track.put("codec_id", codecId);
        track.put("codec_type", codecId < 2 ? 0 : 1);
        track.put("ready", ready);
        track.put("width", 1920);
        track.put("height", 1080);
        JSONArray tracks = new JSONArray();
        tracks.add(track);
        JSONObject mediaData = new JSONObject();
        mediaData.put("app", "rtp");
        mediaData.put("stream", "channel_stream");
        mediaData.put("tracks", tracks);
        return mediaData;
    }
}
