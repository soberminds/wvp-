# GB 录像下载压测说明

## 1. 脚本做什么

脚本文件：`tools/gb_record_stress.py`

单条用例会按下面顺序执行：

1. 调用 `GET /api/gb_record/query/{deviceId}/{channelId}` 查询录像片段。
2. 从查询结果中选一个片段（`random/latest/earliest`）并按 `clip_seconds` 裁剪时长。

  --random：随机选一条
    latest：选开始时间最新的一条
    earliest：选开始时间最早的一条

  --clip_seconds 是“选完之后要不要截短时长”：
    clip_seconds = 60：最多只测这条录像的前 60 秒
    clip_seconds = 0：不截短，按录像原始 startTime ~ endTime 全时段下载

3. 调用 `GET /api/gb_record/download/start/{deviceId}/{channelId}` 发起下载任务。
4. 轮询 `GET /api/gb_record/download/progress/{deviceId}/{channelId}/{stream}`。
5. 等待 `downLoadFilePath.httpPath/httpsPath` 出现，然后执行真实文件下载。
6. 最后调用 `GET /api/gb_record/download/stop/{deviceId}/{channelId}/{stream}` 清理会话。

## 2. 认证原理

支持两种方式给 `access-token`：

1. 静态token：在配置里填 `access_token`。
2. 动态token：配置 `token_url`，脚本先请求该地址，再按 `token_path`（默认 `data.accessToken`）提取token，放到 `token_header`（默认 `access-token`）里发给WVP。

当前你的配置已使用动态token：

- `base_url`: `http://111.198.2.202:65220/`
- `token_url`: `http://111.198.2.202:65493/get_token/`
- `token_path`: `data.accessToken`

## 3. 配置文件

配置文件：`tools/gb_record_stress.config.json`

这个文件内已经新增了 `_explain` 字段，逐项解释每个配置。  
脚本只读取实际配置键，`_explain` 仅用于说明，不影响执行。

## 4. 快速开始

### 4.1 基本运行（按配置文件）

```bash
python tools/gb_record_stress.py --config tools/gb_record_stress.config.json

或者

python .\tools\gb_record_stress.py --config .\tools\gb_record_stress.config.json

```

### 4.2 临时覆盖个别参数（CLI优先级更高）

```bash
python tools/gb_record_stress.py \
  --config tools/gb_record_stress.config.json \
  --total-cases 20 \
  --concurrency 5 \
  --poll-timeout 180

或者


```

## 5. 输出结果说明

### 5.1 明细CSV（`csv_out`）

每行1条用例，重点字段：

1. `query_ok`：录像查询成功。
2. `start_ok`：下载任务创建成功且拿到 `stream_id`。
3. `progress_complete`：轮询期间 `progress >= 1.0`。
4. `file_url_ready`：拿到了 `downLoadFilePath`。
5. `download_ok`：下载HTTP 200 且下载字节数 >= `min_download_bytes`。
6. `stop_ok`：stop接口返回200。
7. `e2e_ok`：全链路成功（上面关键步骤都成功）。
8. `error_stage` / `error_message`：失败阶段和原因。

### 5.2 汇总JSON（`summary_out`）

1. `counts`：各阶段成功数量。
2. `rates`：各阶段成功率。
3. `latency_ms`：关键阶段耗时分位数（P50/P95/P99）。
4. `error_stage_counts`：失败分布（query/start/progress/download等）。

## 6. 三档压测执行命令清单

下面命令都默认使用同一份配置文件：

`tools/gb_record_stress.config.json`

### 6.1 冒烟档（验证链路）

目标：确认token、query、start、progress、stop链路都能跑通。

```bash
python tools/gb_record_stress.py \
  --config tools/gb_record_stress.config.json \
  --total-cases 10 \
  --concurrency 1 \
  --clip-seconds 30 \
  --poll-timeout 120 \
  --no-save-file \
  --csv-out ./smoke_detail.csv \
  --summary-out ./smoke_summary.json

  或者

  python .\tools\gb_record_stress.py --config .\tools\gb_record_stress.config.json --total-cases 10 --concurrency 1 --clip-seconds 30 --poll-timeout 120 --no-save-file --csv-out .\smoke_detail.csv --summary-out .\smoke_summary.json

```

### 6.2 基线档（常规稳定性）

目标：观察中等并发下成功率和P95耗时。

```bash
python tools/gb_record_stress.py \
  --config tools/gb_record_stress.config.json \
  --total-cases 200 \
  --concurrency 10 \
  --clip-seconds 60 \
  --poll-timeout 240 \
  --save-file \
  --output-dir ./baseline_downloads \
  --csv-out ./baseline_detail.csv \
  --summary-out ./baseline_summary.json

  或者

  python .\tools\gb_record_stress.py --config .\tools\gb_record_stress.config.json --total-cases 200 --concurrency 10 --clip-seconds 60 --poll-timeout 240 --save-file --output-dir .\baseline_downloads --csv-out .\baseline_detail.csv --summary-out .\baseline_summary.json

```

### 6.3 增压档（找瓶颈）

目标：验证高并发下的容量上限与失败模式。

```bash
python tools/gb_record_stress.py \
  --config tools/gb_record_stress.config.json \
  --total-cases 600 \
  --concurrency 30 \
  --clip-seconds 60 \
  --poll-timeout 300 \
  --save-file \
  --output-dir ./stress_downloads \
  --csv-out ./stress_detail.csv \
  --summary-out ./stress_summary.json

  或者

  python .\tools\gb_record_stress.py --config .\tools\gb_record_stress.config.json --total-cases 600 --concurrency 30 --clip-seconds 60 --poll-timeout 300 --save-file --output-dir .\stress_downloads --csv-out .\stress_detail.csv --summary-out .\stress_summary.json

```

## 7. 推荐执行顺序

1. 先跑冒烟档，确认 `query_ok/start_ok/stop_ok` 至少接近100%。
2. 再跑基线档，观察 `e2e_ok_pct`、`file_url_ready_pct`、`download_ok_pct`。
3. 最后跑增压档，对比基线档看成功率是否明显下降，定位瓶颈阶段（看 `error_stage_counts`）。

## 8. 常见问题

1. `query_ok=true` 但 `start_ok=false`：通常是设备侧下载会话创建失败，先看 `start_msg` 和服务日志。
2. `progress_complete=false`：多为轮询超时，适当加大 `poll_timeout` 或降低并发。
3. `progress=1.0` 但 `file_url_ready=false`：可能落盘回调慢，可加大 `file_url_grace_sec`。
4. `download_ok=false` 且字节很小：可能拿到的是错误页或空文件，检查 `download_http_status` 和 `min_download_bytes`。

