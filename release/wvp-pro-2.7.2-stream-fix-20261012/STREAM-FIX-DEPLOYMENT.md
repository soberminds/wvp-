# WVP 流状态与端口回收修复（2026-10-12）

适用范围：wvp-qgc / WVP-PRO 2.7.2，针对点播超时无响应、ZLM 无可用 H.264 Track、
多路起流端口不足/漂移、失败后 SendRTP/INVITE/RTP Server 残留、设备在线状态误判等问题。

## 1. 本次修复内容

1. 点播成功判定
   - 只有 ZLM 真的形成了可用的 H.264/H.265 视频轨道（track ready）才向前端返回成功。
   - 轨道为空或 ready=false 时，最多等待 3 秒重新查询 ZLM，仍不可用则判失败并回收资源。
2. 失败与超时资源回收
   - 点播超时、轨道不可用、ZLM 收流超时（on_rtp_server_timeout）都会回收 SSRC、
     关闭 RTP Server、清理点播事务、点播会话（invite）与流订阅，避免端口和 pending 会话残留。
   - 修复点播超时任务在“点播已成功”时仍关闭 RTP Server 的问题，不再误杀正常播放的流。
3. 失效流对账（新增定时任务）
   - 每 30 秒对比 WVP 点播事务与 ZLM 实际流状态，连续 3 次（约 90 秒）确认流不存在才回收，
     避免 ZLM 接口瞬时异常导致误杀。
4. SendRTP 发送端口分配
   - 修复原递归查找端口导致的端口重复分配与栈溢出风险；端口耗尽时返回失败并记录错误日志，
     不再死循环。
5. 事件字段补全
   - ZLM 的 on_send_rtp_stopped、on_rtp_server_timeout 回调补齐 app/stream，
     级联推流停止、收流超时可以被正确回收。
6. 设备在线状态对账（新增定时任务）
   - 每 60 秒检查一次数据库中“在线”设备的最后心跳时间，超过 3 个心跳周期 + 30 秒自动置为离线，
     避免只看注册状态把平台误判为正常。
7. 端口范围扩大
   - 收流：30000-30599（600 个）
   - 推流/级联发送：50000-50799（800 个）
   - 收发端口不再重叠。
8. 解析健壮性
   - 修复 MediaInfo 在没有 tracks 时的空指针、width 误取 height、ZLM 媒体信息取错层级的问题。

## 2. 部署文件

上传这一个文件即可（配置在服务器上手动改）：

    wvp-pro-2.7.2-stream-fix-20261012.jar

SHA256：

    AF44F8F32B407649A2CF4E0381CB97D4BCCA468892AD65300DC18CF80AB20DF8

校验命令：

    sha256sum wvp-pro-2.7.2-stream-fix-20261012.jar

## 3. 部署步骤（Linux）

以现有部署目录 /mnt/disk1/wvp-GB28181-pro-master/target 为例：

    cd /mnt/disk1/wvp-GB28181-pro-master/target
    # 1. 上传新 jar 到本目录（即本文档所在目录）
    # 2. 记录旧进程并停止
    ps -ef | grep wvp-pro | grep -v grep
    kill <旧进程PID>
    # 3. 启动新 jar（保持原来的 profiles 与参数）
    nohup java -jar wvp-pro-2.7.2-stream-fix-20261012.jar --spring.profiles.active=dev > wvp.log 2>&1 &
    # 4. 确认启动
    tail -f wvp.log

注意：
- 新 jar 文件名与旧的不同。如果服务器用 systemd/supervisor/脚本固定了旧 jar 名，
  请同步修改启动配置，或把新 jar 重命名为原来的名字再启动。
- 启动参数（--spring.profiles.active=dev、端口、yml 路径等）保持和原来一致。

## 4. 配置文件修改（手动改，不用上传 yml）

在服务器上现有的 application.yml（或 application-dev.yml）中，把 media.rtp 两行改为：

    media:
      rtp:
        enable: true
        port-range: 30000,30599
        send-port-range: 50000,50799

说明：
- 只需要改这两行，其它配置保持不动。
- WVP 启动时会用这两个值更新 media_server 表，并向 ZLM 下发
  rtp_proxy.port_range=30000-30599。
- ZLM 自己的 config.ini 也建议同步改成 port_range=30000-30599，
  防止 ZLM 先于 WVP 重启时端口范围回退。
- 安全组/防火墙需要放行 TCP 和 UDP：30000-30599、50000-50799（以及原有业务端口）。

## 5. 部署后验证

1. ZLM 端口范围是否生效（替换 ZLM 地址和 secret）：

       curl -s "http://127.0.0.1:8080/index/api/getServerConfig?secret=你的secret" | grep -o '"rtp_proxy.port_range":"[^"]*"'

   期望结果：30000-30599

2. RTP Server 是否残留（点播失败/超时后执行）：

       curl -s "http://127.0.0.1:8080/index/api/listRtpServer?secret=你的secret"

   期望结果：没有已结束频道的 stream_id 残留。

3. 点播成功后确认轨道就绪（stream 换成实际流 ID）：

       curl -s "http://127.0.0.1:8080/index/api/getMediaList?secret=你的secret&app=rtp&stream=实际流ID"

   期望结果：data 中存在 video 轨道，codec_id=0（H264）、ready=true。

4. 日志关键字（正常工作时会出现）：

   - [媒体状态同步] 检测到失效流，回收资源
   - [RTP收流超时] 开始回收资源
   - [点播] 未获取到可用的视频轨道
   - [设备在线对账] 设备心跳超时，标记离线

5. 在线状态验证：设备断电后，最多 3 个心跳周期 + 30 秒，设备列表中该设备自动变为离线。

## 6. 说明与注意事项

- 本次问题与 JWT/token 机制无关，未修改登录 token 逻辑；上次安全修复保持不变。
- 多路起流仍受 ZLM 端口数量限制：本次收流 600 个、发送 800 个，
  按 100 路通道并发点播/回放/级联是足够的。
- 如果生产 yml 中 play-timeout 仍为 180000（毫秒，即 180 秒），
  点播失败需要等 3 分钟才会返回超时；建议按现场设备启动速度调整为 18000（18 秒）左右。
- 本次验证：mvn 全量单元测试 33 个全部通过，JAR 编译打包成功。
  未连接生产数据库/ZLM 做联调，请按第 5 节在生产环境验证。
