package com.genersoft.iot.vmp.web.gb28181;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.conf.SipConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * API兼容：系统接口
 */
@Controller

@RequestMapping(value = "/api/v1")
public class ApiController {

    @Autowired
    private SipConfig sipConfig;


    @GetMapping("/getserverinfo")
    private JSONObject getserverinfo(){
        JSONObject result = new JSONObject();
        result.put("Authorization","ceshi");
        result.put("Hardware","");
        result.put("InterfaceVersion","2.5.5");
        result.put("IsDemo","");
        result.put("Hardware","false");
        result.put("APIAuth","false");
        result.put("RemainDays","永久");
        result.put("RunningTime","");
        result.put("ServerTime","2020-09-02 17：11");
        result.put("StartUpTime","2020-09-02 17：11");
        result.put("Server","");
        result.put("SIPSerial", sipConfig.getId());
        result.put("SIPRealm", sipConfig.getDomain());
        result.put("SIPHost", sipConfig.getShowIp());
        result.put("SIPPort", sipConfig.getPort());
        result.put("ChannelCount","1000");
        result.put("VersionType","");
        result.put("LogoMiniText","");
        result.put("LogoText","");
        result.put("CopyrightText","");

        return result;
    }

    @GetMapping(value = "/userinfo")
    private JSONObject userinfo(){
//        JSONObject result = new JSONObject();
//        result.put("ID","ceshi");
//        result.put("Hardware","");
//        result.put("InterfaceVersion","2.5.5");
//        result.put("IsDemo","");
//        result.put("Hardware","false");
//        result.put("APIAuth","false");
//        result.put("RemainDays","永久");
//        result.put("RunningTime","");
//        result.put("ServerTime","2020-09-02 17：11");
//        result.put("StartUpTime","2020-09-02 17：11");
//        result.put("Server","");
//        result.put("SIPSerial", sipConfig.getId());
//        result.put("SIPRealm", sipConfig.getDomain());
//        result.put("SIPHost", sipConfig.getIp());
//        result.put("SIPPort", sipConfig.getPort());
//        result.put("ChannelCount","1000");
//        result.put("VersionType","");
//        result.put("LogoMiniText","");
//        result.put("LogoText","");
//        result.put("CopyrightText","");

        return null;
    }

}
