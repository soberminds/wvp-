#!/usr/bin/env python3
"""
GB28181 record download stress test.

Flow per case:
1) query record list
2) start historical media download
3) poll progress until file url appears
4) download file from downLoadFilePath.httpPath / httpsPath
5) stop historical media download (best effort cleanup)

Output:
- detail CSV (one row per case)
- summary JSON
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import random
import re
import ssl
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, unquote, urljoin, urlparse
from urllib.request import Request, urlopen
from xml.sax.saxutils import escape as xml_escape

try:
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
except ImportError:
    ZoneInfo = None  # type: ignore
    ZoneInfoNotFoundError = Exception  # type: ignore


API_TIME_FORMAT = "%Y-%m-%d %H:%M:%S"
if ZoneInfo:
    try:
        SHANGHAI_TZ = ZoneInfo("Asia/Shanghai")
    except ZoneInfoNotFoundError:
        SHANGHAI_TZ = timezone(timedelta(hours=8))
else:
    SHANGHAI_TZ = timezone(timedelta(hours=8))


KEY_EXPLAIN: Dict[str, str] = {
    # common / summary
    "timestamp": "时间戳",
    "config": "运行配置",
    "counts": "计数统计",
    "rates": "成功率统计",
    "latency_ms": "时延毫秒统计",
    "error_stage_counts": "失败阶段分布",
    "run_total_ms": "整次运行总耗时毫秒",
    # config block
    "base_url": "服务地址",
    "device_id": "设备ID",
    "channel_id": "通道ID",
    "query_start": "查询开始时间",
    "query_end": "查询结束时间",
    "fallback_start": "兜底开始时间",
    "fallback_end": "兜底结束时间",
    "download_speed": "下载倍速",
    "concurrency": "并发数",
    "total_cases": "总用例数",
    "poll_interval": "轮询间隔秒",
    "poll_timeout": "轮询超时秒",
    "file_url_grace_sec": "文件地址宽限秒",
    "request_timeout": "请求超时秒",
    "verify_ssl": "是否校验证书",
    "prefer_https_file_url": "优先HTTPS文件地址",
    "selection_strategy": "片段选择策略",
    "clip_seconds": "片段裁剪秒数",
    "min_download_bytes": "最小下载字节阈值",
    "save_file": "是否保存文件",
    "run_level": "运行级别",
    "auto_name_output": "是否自动命名输出",
    "annotate_output_keys": "是否输出字段注释",
    "excel_out": "Excel输出路径",
    "export_excel": "是否导出Excel",
    "key": "字段路径",
    "value": "字段值",
    # counts / rates
    "query_ok": "查询成功数",
    "start_ok": "开始下载成功数",
    "progress_complete": "进度完成数",
    "file_url_ready": "文件地址就绪数",
    "download_ok": "下载成功数",
    "e2e_ok": "端到端成功数",
    "stop_ok": "停止成功数",
    "query_ok_pct": "查询成功率%",
    "start_ok_pct": "开始下载成功率%",
    "progress_complete_pct": "进度完成率%",
    "file_url_ready_pct": "文件地址就绪率%",
    "download_ok_pct": "下载成功率%",
    "e2e_ok_pct": "端到端成功率%",
    "stop_ok_pct": "停止成功率%",
    # latency
    "case_p50": "单用例耗时P50",
    "case_p95": "单用例耗时P95",
    "case_p99": "单用例耗时P99",
    "start_p50": "start耗时P50",
    "start_p95": "start耗时P95",
    "poll_p50": "progress轮询耗时P50",
    "poll_p95": "progress轮询耗时P95",
    "download_p50": "下载耗时P50",
    "download_p95": "下载耗时P95",
    # detail csv row keys
    "case_id": "用例序号",
    "query_http_status": "查询HTTP状态码",
    "query_error": "查询错误",
    "query_count": "查询到录像条数",
    "query_ms": "查询耗时毫秒",
    "selected_source": "选片来源",
    "selected_start": "选片开始时间",
    "selected_end": "选片结束时间",
    "start_http_status": "start接口HTTP状态码",
    "start_code": "start业务状态码",
    "start_msg": "start业务消息",
    "start_error": "start错误",
    "start_ms": "start耗时毫秒",
    "stream_id": "流ID",
    "progress_max": "最大进度值",
    "poll_http_errors": "轮询HTTP异常次数",
    "file_url": "文件下载地址",
    "poll_ms": "轮询总耗时毫秒",
    "download_http_status": "下载HTTP状态码",
    "download_bytes": "下载字节数",
    "download_error": "下载错误",
    "download_file": "下载落盘路径",
    "download_ms": "下载耗时毫秒",
    "stop_http_status": "stop接口HTTP状态码",
    "stop_error": "stop错误",
    "stop_ms": "stop耗时毫秒",
    "error_stage": "失败阶段",
    "error_message": "失败原因",
    "case_ms": "单用例总耗时毫秒",
    # misc
    "none": "无",
    "selection": "选片阶段",
    "query": "查询阶段",
    "start": "开始下载阶段",
    "progress": "进度阶段",
    "download": "下载阶段",
    "exception": "异常阶段",
    "executor": "执行器异常",
}


def now_shanghai() -> datetime:
    return datetime.now(SHANGHAI_TZ)


def fmt_api_time(dt_obj: datetime) -> str:
    local_dt = dt_obj.astimezone(SHANGHAI_TZ) if dt_obj.tzinfo else dt_obj
    return local_dt.strftime(API_TIME_FORMAT)


def safe_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def display_key(key: str, annotate_output_keys: bool) -> str:
    if not annotate_output_keys:
        return key
    explain = KEY_EXPLAIN.get(key, "")
    if not explain:
        return key
    return f"{key}（{explain}）"


def annotate_object_keys(obj: Any, annotate_output_keys: bool) -> Any:
    if not annotate_output_keys:
        return obj
    if isinstance(obj, dict):
        annotated: Dict[str, Any] = {}
        for key, value in obj.items():
            new_key = display_key(str(key), annotate_output_keys=True)
            annotated[new_key] = annotate_object_keys(value, annotate_output_keys=True)
        return annotated
    if isinstance(obj, list):
        return [annotate_object_keys(item, annotate_output_keys=True) for item in obj]
    return obj


def percentile(values: List[float], p: float) -> float:
    if not values:
        return 0.0
    sorted_vals = sorted(values)
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[int(k)]
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def parse_time_candidate(value: str) -> Optional[datetime]:
    if not value:
        return None

    raw = value.strip()
    patterns = [
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%dT%H:%M:%S.%f",
        "%Y-%m-%d",
    ]
    for pattern in patterns:
        try:
            return datetime.strptime(raw, pattern)
        except ValueError:
            pass

    # ISO8601 with timezone, like 2026-05-12T10:00:00+08:00 / Z
    try:
        if raw.endswith("Z"):
            raw = raw[:-1] + "+00:00"
        return datetime.fromisoformat(raw)
    except ValueError:
        return None


def normalize_time_for_api(value: str) -> Optional[str]:
    dt_obj = parse_time_candidate(value)
    if dt_obj is None:
        return None
    if dt_obj.tzinfo is not None:
        dt_obj = dt_obj.astimezone(SHANGHAI_TZ).replace(tzinfo=None)
    return dt_obj.strftime(API_TIME_FORMAT)


def is_wvp_success(payload: Any) -> bool:
    if not isinstance(payload, dict):
        return False
    if "code" not in payload:
        return True
    code = payload.get("code")
    return str(code) == "0"


def unwrap_wvp_data(payload: Any) -> Any:
    """
    GlobalResponseAdvice wraps most responses into:
      {"code": 0, "msg": "...", "data": {...}}
    This helper returns the inner data if present.
    """
    if not isinstance(payload, dict):
        return payload
    if "code" in payload and "data" in payload:
        return payload.get("data")
    return payload


class HttpClient:
    def __init__(self, default_headers: Dict[str, str], timeout_sec: float, verify_ssl: bool) -> None:
        self.default_headers = dict(default_headers)
        self.timeout_sec = timeout_sec
        self.ssl_context = None if verify_ssl else ssl._create_unverified_context()

    def _build_url(self, url: str, params: Optional[Dict[str, Any]]) -> str:
        if not params:
            return url
        clean_params: Dict[str, Any] = {}
        for key, value in params.items():
            if value is None:
                continue
            clean_params[key] = value
        if not clean_params:
            return url
        return f"{url}?{urlencode(clean_params)}"

    def get(
        self,
        url: str,
        params: Optional[Dict[str, Any]] = None,
        headers: Optional[Dict[str, str]] = None,
    ) -> Tuple[int, Dict[str, str], bytes, Optional[str]]:
        req_headers = dict(self.default_headers)
        if headers:
            req_headers.update(headers)
        final_url = self._build_url(url, params)
        req = Request(final_url, headers=req_headers, method="GET")
        try:
            with urlopen(req, timeout=self.timeout_sec, context=self.ssl_context) as resp:
                body = resp.read()
                return resp.status, dict(resp.headers.items()), body, None
        except HTTPError as err:
            body = b""
            try:
                body = err.read() if err.fp else b""
            except Exception:
                body = b""
            headers_dict = dict(err.headers.items()) if err.headers else {}
            return err.code, headers_dict, body, str(err)
        except URLError as err:
            return 0, {}, b"", str(err)
        except Exception as err:  # pragma: no cover
            return 0, {}, b"", str(err)

    def download_file(
        self,
        url: str,
        destination: Optional[str],
        headers: Optional[Dict[str, str]] = None,
        chunk_size: int = 64 * 1024,
    ) -> Tuple[int, int, Optional[str]]:
        req_headers = dict(self.default_headers)
        if headers:
            req_headers.update(headers)
        req = Request(url, headers=req_headers, method="GET")
        bytes_written = 0
        try:
            with urlopen(req, timeout=self.timeout_sec, context=self.ssl_context) as resp:
                status = resp.status
                if status != 200:
                    body = resp.read()
                    return status, len(body), f"download status={status}"
                if destination:
                    os.makedirs(os.path.dirname(destination), exist_ok=True)
                    with open(destination, "wb") as fh:
                        while True:
                            chunk = resp.read(chunk_size)
                            if not chunk:
                                break
                            bytes_written += len(chunk)
                            fh.write(chunk)
                else:
                    while True:
                        chunk = resp.read(chunk_size)
                        if not chunk:
                            break
                        bytes_written += len(chunk)
                return status, bytes_written, None
        except HTTPError as err:
            return err.code, 0, str(err)
        except URLError as err:
            return 0, 0, str(err)
        except Exception as err:  # pragma: no cover
            return 0, 0, str(err)


@dataclass
class StressConfig:
    base_url: str
    device_id: str
    channel_id: str
    query_start: str
    query_end: str
    fallback_start: Optional[str]
    fallback_end: Optional[str]
    download_speed: int
    concurrency: int
    total_cases: int
    poll_interval: float
    poll_timeout: float
    file_url_grace_sec: float
    request_timeout: float
    verify_ssl: bool
    prefer_https_file_url: bool
    min_download_bytes: int
    save_file: bool
    output_dir: str
    csv_out: str
    summary_out: str
    excel_out: str
    run_level: str
    auto_name_output: bool
    annotate_output_keys: bool
    export_excel: bool
    selection_strategy: str
    clip_seconds: int
    allow_start_without_query: bool
    seed: int


def build_url(base_url: str, path: str) -> str:
    return urljoin(base_url.rstrip("/") + "/", path.lstrip("/"))


def parse_json_bytes(raw: bytes) -> Tuple[Optional[Any], Optional[str]]:
    if raw is None:
        return None, "empty body"
    text = raw.decode("utf-8", errors="replace").strip()
    if not text:
        return None, "empty body"
    try:
        return json.loads(text), None
    except json.JSONDecodeError as err:
        return None, f"json decode error: {err}"


def extract_record_items(query_payload: Any) -> List[Dict[str, Any]]:
    if not isinstance(query_payload, dict):
        return []
    data = query_payload.get("data")
    if isinstance(data, dict):
        record_list = data.get("recordList")
        if isinstance(record_list, list):
            return [x for x in record_list if isinstance(x, dict)]
    record_list = query_payload.get("recordList")
    if isinstance(record_list, list):
        return [x for x in record_list if isinstance(x, dict)]
    return []


def clip_end_time(start: str, end: str, clip_seconds: int) -> str:
    if clip_seconds <= 0:
        return end
    start_dt = parse_time_candidate(start)
    end_dt = parse_time_candidate(end)
    if start_dt is None or end_dt is None:
        return end
    clipped_end = start_dt + timedelta(seconds=clip_seconds)
    if end_dt > clipped_end:
        return clipped_end.strftime(API_TIME_FORMAT)
    return end


def choose_time_window(
    items: List[Dict[str, Any]],
    selection_strategy: str,
    clip_seconds: int,
    fallback_start: Optional[str],
    fallback_end: Optional[str],
    rng: random.Random,
) -> Tuple[Optional[str], Optional[str], str]:
    if items:
        valid_items = []
        for item in items:
            start_time = normalize_time_for_api(str(item.get("startTime", "")).strip())
            end_time = normalize_time_for_api(str(item.get("endTime", "")).strip())
            if not start_time or not end_time:
                continue
            valid_items.append((start_time, end_time, item))

        if valid_items:
            if selection_strategy == "latest":
                valid_items.sort(key=lambda x: x[0], reverse=True)
                selected = valid_items[0]
            elif selection_strategy == "earliest":
                valid_items.sort(key=lambda x: x[0])
                selected = valid_items[0]
            else:
                selected = rng.choice(valid_items)
            start_time, end_time, _ = selected
            end_time = clip_end_time(start_time, end_time, clip_seconds)
            return start_time, end_time, "record_list"

    if fallback_start and fallback_end:
        return fallback_start, fallback_end, "fallback"
    return None, None, "none"


def best_file_url(stream_payload: Dict[str, Any], prefer_https: bool) -> Optional[str]:
    download_obj = stream_payload.get("downLoadFilePath")
    if not isinstance(download_obj, dict):
        return None
    http_url = download_obj.get("httpPath")
    https_url = download_obj.get("httpsPath")
    if prefer_https and https_url:
        return str(https_url)
    if http_url:
        return str(http_url)
    if https_url:
        return str(https_url)
    return None


def infer_download_filename(case_id: int, url: str) -> str:
    parsed = urlparse(url)
    query = parse_qs(parsed.query or "")
    file_paths = query.get("file_path", [])
    candidate = ""
    if file_paths:
        candidate = unquote(file_paths[0])
    basename = os.path.basename(candidate) if candidate else ""
    if not basename:
        basename = f"record_{case_id:06d}.bin"
    return f"{case_id:06d}_{basename}"


def run_case(case_id: int, cfg: StressConfig, http: HttpClient) -> Dict[str, Any]:
    result: Dict[str, Any] = {
        "case_id": case_id,
        "query_http_status": 0,
        "query_ok": False,
        "query_error": "",
        "query_count": 0,
        "query_ms": 0,
        "selected_source": "",
        "selected_start": "",
        "selected_end": "",
        "start_http_status": 0,
        "start_ok": False,
        "start_code": "",
        "start_msg": "",
        "start_error": "",
        "start_ms": 0,
        "stream_id": "",
        "progress_max": 0.0,
        "poll_http_errors": 0,
        "progress_complete": False,
        "file_url_ready": False,
        "file_url": "",
        "poll_ms": 0,
        "download_http_status": 0,
        "download_ok": False,
        "download_bytes": 0,
        "download_error": "",
        "download_file": "",
        "download_ms": 0,
        "stop_http_status": 0,
        "stop_ok": False,
        "stop_error": "",
        "stop_ms": 0,
        "e2e_ok": False,
        "error_stage": "",
        "error_message": "",
        "case_ms": 0,
    }
    started_at = time.time()
    stream_id = ""
    rng = random.Random(cfg.seed + case_id)

    try:
        query_url = build_url(cfg.base_url, f"/api/gb_record/query/{cfg.device_id}/{cfg.channel_id}")
        query_params = {
            "startTime": cfg.query_start,
            "endTime": cfg.query_end,
        }
        t0 = time.time()
        q_status, _, q_body, q_err = http.get(query_url, params=query_params)
        result["query_ms"] = int((time.time() - t0) * 1000)
        result["query_http_status"] = q_status
        if q_err:
            result["query_error"] = q_err
        q_json, q_json_err = parse_json_bytes(q_body)
        if q_json_err and not result["query_error"]:
            result["query_error"] = q_json_err
        result["query_ok"] = q_status == 200 and is_wvp_success(q_json)
        record_items = extract_record_items(q_json)
        result["query_count"] = len(record_items)

        selected_start, selected_end, selected_source = choose_time_window(
            items=record_items,
            selection_strategy=cfg.selection_strategy,
            clip_seconds=cfg.clip_seconds,
            fallback_start=cfg.fallback_start,
            fallback_end=cfg.fallback_end,
            rng=rng,
        )
        result["selected_source"] = selected_source
        result["selected_start"] = selected_start or ""
        result["selected_end"] = selected_end or ""

        if not selected_start or not selected_end:
            result["error_stage"] = "selection"
            result["error_message"] = "no usable time range from query and fallback"
            return result

        if (not result["query_ok"]) and (not cfg.allow_start_without_query):
            result["error_stage"] = "query"
            result["error_message"] = result["query_error"] or "query failed"
            return result

        start_url = build_url(cfg.base_url, f"/api/gb_record/download/start/{cfg.device_id}/{cfg.channel_id}")
        start_params = {
            "startTime": selected_start,
            "endTime": selected_end,
            "downloadSpeed": str(cfg.download_speed),
        }
        t1 = time.time()
        s_status, _, s_body, s_err = http.get(start_url, params=start_params)
        result["start_ms"] = int((time.time() - t1) * 1000)
        result["start_http_status"] = s_status
        if s_err:
            result["start_error"] = s_err
        s_json, s_json_err = parse_json_bytes(s_body)
        if s_json_err and not result["start_error"]:
            result["start_error"] = s_json_err
        if isinstance(s_json, dict):
            result["start_code"] = str(s_json.get("code", ""))
            result["start_msg"] = str(s_json.get("msg", ""))
            s_data = s_json.get("data")
            if isinstance(s_data, dict):
                stream_id = str(s_data.get("stream", "") or "")
                result["stream_id"] = stream_id

        result["start_ok"] = (
            s_status == 200
            and isinstance(s_json, dict)
            and is_wvp_success(s_json)
            and bool(stream_id)
        )
        if not result["start_ok"]:
            result["error_stage"] = "start"
            result["error_message"] = result["start_error"] or result["start_msg"] or "start failed"
            return result

        progress_url = build_url(
            cfg.base_url, f"/api/gb_record/download/progress/{cfg.device_id}/{cfg.channel_id}/{stream_id}"
        )
        t2 = time.time()
        progress_reached_at: Optional[float] = None
        file_url: Optional[str] = None
        poll_deadline = time.time() + cfg.poll_timeout
        while time.time() < poll_deadline:
            p_status, _, p_body, _ = http.get(progress_url)
            if p_status != 200:
                result["poll_http_errors"] += 1
                time.sleep(cfg.poll_interval)
                continue
            p_json, _ = parse_json_bytes(p_body)
            if not isinstance(p_json, dict):
                result["poll_http_errors"] += 1
                time.sleep(cfg.poll_interval)
                continue

            if "code" in p_json and not is_wvp_success(p_json):
                result["poll_http_errors"] += 1
                time.sleep(cfg.poll_interval)
                continue

            progress_obj = unwrap_wvp_data(p_json)
            if not isinstance(progress_obj, dict):
                result["poll_http_errors"] += 1
                time.sleep(cfg.poll_interval)
                continue

            progress = safe_float(progress_obj.get("progress"), 0.0)
            if progress > result["progress_max"]:
                result["progress_max"] = progress
            if progress >= 1.0 and progress_reached_at is None:
                progress_reached_at = time.time()
                result["progress_complete"] = True

            current_file_url = best_file_url(progress_obj, prefer_https=cfg.prefer_https_file_url)
            if current_file_url:
                file_url = current_file_url
                result["file_url_ready"] = True
                result["file_url"] = file_url
                break

            if progress_reached_at is not None:
                if (time.time() - progress_reached_at) >= cfg.file_url_grace_sec:
                    break

            time.sleep(cfg.poll_interval)

        result["poll_ms"] = int((time.time() - t2) * 1000)

        if not result["file_url_ready"]:
            result["error_stage"] = "progress"
            if not result["progress_complete"]:
                result["error_message"] = "progress timeout before reaching 1.0"
            else:
                result["error_message"] = "progress reached 1.0 but file url not ready"
            return result

        download_url = result["file_url"]
        destination = None
        if cfg.save_file:
            filename = infer_download_filename(case_id, download_url)
            destination = os.path.join(cfg.output_dir, filename)
            result["download_file"] = destination

        t3 = time.time()
        d_status, d_bytes, d_err = http.download_file(download_url, destination=destination)
        result["download_ms"] = int((time.time() - t3) * 1000)
        result["download_http_status"] = d_status
        result["download_bytes"] = d_bytes
        if d_err:
            result["download_error"] = d_err
        result["download_ok"] = d_status == 200 and d_bytes >= cfg.min_download_bytes
        if not result["download_ok"]:
            result["error_stage"] = "download"
            result["error_message"] = result["download_error"] or f"download bytes < {cfg.min_download_bytes}"
            return result

        result["e2e_ok"] = True
        return result
    except Exception as err:  # pragma: no cover
        result["error_stage"] = "exception"
        result["error_message"] = str(err)
        return result
    finally:
        if stream_id:
            stop_url = build_url(
                cfg.base_url, f"/api/gb_record/download/stop/{cfg.device_id}/{cfg.channel_id}/{stream_id}"
            )
            t4 = time.time()
            st_status, _, _, st_err = http.get(stop_url)
            result["stop_ms"] = int((time.time() - t4) * 1000)
            result["stop_http_status"] = st_status
            result["stop_ok"] = st_status == 200
            if st_err:
                result["stop_error"] = st_err
        result["case_ms"] = int((time.time() - started_at) * 1000)


def write_csv(path: str, rows: List[Dict[str, Any]], annotate_output_keys: bool) -> None:
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    if not rows:
        with open(path, "w", newline="", encoding="utf-8") as fh:
            fh.write("")
        return
    fieldnames = list(rows[0].keys())
    # utf-8-sig makes Chinese headers display correctly in Excel on Windows.
    with open(path, "w", newline="", encoding="utf-8-sig") as fh:
        writer = csv.writer(fh)
        header = [display_key(field, annotate_output_keys) for field in fieldnames]
        writer.writerow(header)
        for row in rows:
            writer.writerow([row.get(field, "") for field in fieldnames])


def _excel_col_name(col_index: int) -> str:
    name = ""
    x = col_index
    while x > 0:
        x, rem = divmod(x - 1, 26)
        name = chr(65 + rem) + name
    return name


def _sanitize_excel_text(value: str) -> str:
    # remove control chars not allowed in XML 1.0 (except tab/newline/carriage)
    chars: List[str] = []
    for ch in value:
        code = ord(ch)
        if code in (0x09, 0x0A, 0x0D) or code >= 0x20:
            chars.append(ch)
    return "".join(chars)


def _excel_cell_xml(row_idx: int, col_idx: int, value: Any) -> str:
    cell_ref = f"{_excel_col_name(col_idx)}{row_idx}"
    if value is None or value == "":
        return f'<c r="{cell_ref}"/>'
    if isinstance(value, bool):
        return f'<c r="{cell_ref}" t="b"><v>{1 if value else 0}</v></c>'
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if isinstance(value, float):
            if not math.isfinite(value):
                text = _sanitize_excel_text(str(value))
                return f'<c r="{cell_ref}" t="inlineStr"><is><t xml:space="preserve">{xml_escape(text)}</t></is></c>'
        return f"<c r=\"{cell_ref}\"><v>{value}</v></c>"

    if isinstance(value, (dict, list)):
        text = json.dumps(value, ensure_ascii=False)
    else:
        text = str(value)
    text = _sanitize_excel_text(text)
    return f'<c r="{cell_ref}" t="inlineStr"><is><t xml:space="preserve">{xml_escape(text)}</t></is></c>'


def _build_sheet_xml(rows: List[List[Any]]) -> str:
    lines = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">',
        "  <sheetData>",
    ]
    for r_idx, row in enumerate(rows, start=1):
        lines.append(f'    <row r="{r_idx}">')
        for c_idx, value in enumerate(row, start=1):
            lines.append("      " + _excel_cell_xml(r_idx, c_idx, value))
        lines.append("    </row>")
    lines.extend(["  </sheetData>", "</worksheet>"])
    return "\n".join(lines)


def _flatten_summary_pairs(obj: Any, prefix: str = "") -> List[Tuple[str, Any]]:
    pairs: List[Tuple[str, Any]] = []
    if isinstance(obj, dict):
        for key, value in obj.items():
            next_prefix = f"{prefix}.{key}" if prefix else str(key)
            pairs.extend(_flatten_summary_pairs(value, next_prefix))
        return pairs
    if isinstance(obj, list):
        pairs.append((prefix, json.dumps(obj, ensure_ascii=False)))
        return pairs
    pairs.append((prefix, obj))
    return pairs


def write_excel_workbook(
    path: str,
    detail_rows: List[Dict[str, Any]],
    summary_obj: Dict[str, Any],
    annotate_output_keys: bool,
) -> Optional[str]:
    try:
        os.makedirs(os.path.dirname(path) or ".", exist_ok=True)

        if detail_rows:
            detail_fields = list(detail_rows[0].keys())
            detail_table: List[List[Any]] = [
                [display_key(field, annotate_output_keys) for field in detail_fields]
            ]
            for row in detail_rows:
                detail_table.append([row.get(field, "") for field in detail_fields])
        else:
            detail_table = [["detail", "empty"]]

        summary_pairs = _flatten_summary_pairs(summary_obj)
        summary_header_key = display_key("key", annotate_output_keys)
        summary_header_value = display_key("value", annotate_output_keys)
        summary_table: List[List[Any]] = [[summary_header_key, summary_header_value]]
        for key, value in summary_pairs:
            summary_table.append([key, value])

        detail_sheet = _build_sheet_xml(detail_table)
        summary_sheet = _build_sheet_xml(summary_table)

        content_types = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>
"""

        rels_root = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>
"""

        workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="detail" sheetId="1" r:id="rId1"/>
    <sheet name="summary" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>
"""

        workbook_rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
"""

        styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1">
    <font><sz val="11"/><name val="Calibri"/></font>
  </fonts>
  <fills count="2">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
  </cellXfs>
  <cellStyles count="1">
    <cellStyle name="Normal" xfId="0" builtinId="0"/>
  </cellStyles>
</styleSheet>
"""

        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("[Content_Types].xml", content_types)
            zf.writestr("_rels/.rels", rels_root)
            zf.writestr("xl/workbook.xml", workbook)
            zf.writestr("xl/_rels/workbook.xml.rels", workbook_rels)
            zf.writestr("xl/styles.xml", styles)
            zf.writestr("xl/worksheets/sheet1.xml", detail_sheet)
            zf.writestr("xl/worksheets/sheet2.xml", summary_sheet)
        return None
    except Exception as err:
        return str(err)


def make_summary(cfg: StressConfig, rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    total = len(rows)

    def count_true(key: str) -> int:
        return sum(1 for row in rows if bool(row.get(key)))

    def pct(num: int, den: int) -> float:
        if den <= 0:
            return 0.0
        return round((num * 100.0) / den, 2)

    query_ok = count_true("query_ok")
    start_ok = count_true("start_ok")
    progress_complete = count_true("progress_complete")
    file_url_ready = count_true("file_url_ready")
    download_ok = count_true("download_ok")
    e2e_ok = count_true("e2e_ok")
    stop_ok = count_true("stop_ok")

    stage_counter: Dict[str, int] = {}
    for row in rows:
        stage = str(row.get("error_stage") or "")
        if not stage:
            stage = "none"
        stage_counter[stage] = stage_counter.get(stage, 0) + 1

    case_lat = [safe_float(row.get("case_ms"), 0.0) for row in rows if row.get("case_ms") is not None]
    start_lat = [safe_float(row.get("start_ms"), 0.0) for row in rows if row.get("start_ok")]
    poll_lat = [safe_float(row.get("poll_ms"), 0.0) for row in rows if row.get("start_ok")]
    download_lat = [safe_float(row.get("download_ms"), 0.0) for row in rows if row.get("file_url_ready")]

    summary = {
        "timestamp": now_shanghai().isoformat(),
        "config": {
            "base_url": cfg.base_url,
            "device_id": cfg.device_id,
            "channel_id": cfg.channel_id,
            "query_start": cfg.query_start,
            "query_end": cfg.query_end,
            "fallback_start": cfg.fallback_start,
            "fallback_end": cfg.fallback_end,
            "download_speed": cfg.download_speed,
            "concurrency": cfg.concurrency,
            "total_cases": cfg.total_cases,
            "poll_interval": cfg.poll_interval,
            "poll_timeout": cfg.poll_timeout,
            "file_url_grace_sec": cfg.file_url_grace_sec,
            "selection_strategy": cfg.selection_strategy,
            "clip_seconds": cfg.clip_seconds,
            "min_download_bytes": cfg.min_download_bytes,
            "save_file": cfg.save_file,
            "excel_out": cfg.excel_out,
            "run_level": cfg.run_level,
            "auto_name_output": cfg.auto_name_output,
            "annotate_output_keys": cfg.annotate_output_keys,
            "export_excel": cfg.export_excel,
        },
        "counts": {
            "total_cases": total,
            "query_ok": query_ok,
            "start_ok": start_ok,
            "progress_complete": progress_complete,
            "file_url_ready": file_url_ready,
            "download_ok": download_ok,
            "e2e_ok": e2e_ok,
            "stop_ok": stop_ok,
        },
        "rates": {
            "query_ok_pct": pct(query_ok, total),
            "start_ok_pct": pct(start_ok, total),
            "progress_complete_pct": pct(progress_complete, total),
            "file_url_ready_pct": pct(file_url_ready, total),
            "download_ok_pct": pct(download_ok, total),
            "e2e_ok_pct": pct(e2e_ok, total),
            "stop_ok_pct": pct(stop_ok, total),
        },
        "latency_ms": {
            "case_p50": round(percentile(case_lat, 0.50), 2),
            "case_p95": round(percentile(case_lat, 0.95), 2),
            "case_p99": round(percentile(case_lat, 0.99), 2),
            "start_p50": round(percentile(start_lat, 0.50), 2),
            "start_p95": round(percentile(start_lat, 0.95), 2),
            "poll_p50": round(percentile(poll_lat, 0.50), 2),
            "poll_p95": round(percentile(poll_lat, 0.95), 2),
            "download_p50": round(percentile(download_lat, 0.50), 2),
            "download_p95": round(percentile(download_lat, 0.95), 2),
        },
        "error_stage_counts": stage_counter,
    }
    return summary


def parse_header_pairs(header_args: Optional[List[str]]) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    if not header_args:
        return headers
    for item in header_args:
        if ":" not in item:
            continue
        key, value = item.split(":", 1)
        key = key.strip()
        value = value.strip()
        if key:
            headers[key] = value
    return headers


def parse_headers_from_config(raw: Any) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    if raw is None:
        return headers
    if isinstance(raw, dict):
        for key, value in raw.items():
            if key is None:
                continue
            key_str = str(key).strip()
            if not key_str:
                continue
            headers[key_str] = "" if value is None else str(value)
        return headers
    if isinstance(raw, list):
        # list entry can be "Key: Value" strings
        items = [str(x) for x in raw if x is not None]
        return parse_header_pairs(items)
    if isinstance(raw, str):
        return parse_header_pairs([raw])
    return headers


def get_nested_value(payload: Any, path: str) -> Any:
    if not path:
        return None
    current = payload
    for part in path.split("."):
        part = part.strip()
        if not part:
            return None
        if isinstance(current, dict) and part in current:
            current = current[part]
        else:
            return None
    return current


def load_json_config(config_path: str) -> Dict[str, Any]:
    with open(config_path, "r", encoding="utf-8-sig") as fh:
        payload = json.load(fh)
    if not isinstance(payload, dict):
        raise ValueError("config file root must be a JSON object")
    return payload


def get_config_value(config: Dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in config:
            value = config[key]
            if value is None:
                continue
            if isinstance(value, str) and not value.strip():
                continue
            return value
    return None


def choose_string(cli_value: Optional[str], config_value: Any, default: str) -> str:
    if cli_value is not None and str(cli_value).strip() != "":
        return str(cli_value).strip()
    if config_value is not None and str(config_value).strip() != "":
        return str(config_value).strip()
    return default


def choose_int(cli_value: Optional[int], config_value: Any, default: int, minimum: Optional[int] = None) -> int:
    if cli_value is not None:
        value = int(cli_value)
    elif config_value is not None:
        value = int(config_value)
    else:
        value = default
    if minimum is not None:
        value = max(minimum, value)
    return value


def choose_float(
    cli_value: Optional[float],
    config_value: Any,
    default: float,
    minimum: Optional[float] = None,
) -> float:
    if cli_value is not None:
        value = float(cli_value)
    elif config_value is not None:
        value = float(config_value)
    else:
        value = default
    if minimum is not None:
        value = max(minimum, value)
    return value


def choose_bool(cli_value: Optional[bool], config_value: Any, default: bool) -> bool:
    if cli_value is not None:
        return bool(cli_value)
    if config_value is None:
        return default
    if isinstance(config_value, bool):
        return config_value
    if isinstance(config_value, (int, float)):
        return config_value != 0
    if isinstance(config_value, str):
        return config_value.strip().lower() in {"1", "true", "yes", "y", "on"}
    return default


def sanitize_level(level: str) -> str:
    raw = (level or "").strip().lower()
    if not raw:
        return "run"
    cleaned = re.sub(r"[^a-z0-9_-]+", "_", raw).strip("_")
    return cleaned or "run"


def resolve_output_paths_with_sequence(
    csv_out: str,
    summary_out: str,
    excel_out: str,
    run_level: str,
) -> Tuple[str, str, str]:
    """
    Create non-overwrite output names using level + sequence:
      <level>_detail_001.csv
      <level>_summary_001.json
    """
    level = sanitize_level(run_level)
    csv_dir = os.path.dirname(csv_out) or "."
    summary_dir = os.path.dirname(summary_out) or "."
    excel_dir = os.path.dirname(excel_out) or "."
    os.makedirs(csv_dir, exist_ok=True)
    os.makedirs(summary_dir, exist_ok=True)
    os.makedirs(excel_dir, exist_ok=True)

    seq = 1
    while True:
        csv_candidate = os.path.join(csv_dir, f"{level}_detail_{seq:03d}.csv")
        summary_candidate = os.path.join(summary_dir, f"{level}_summary_{seq:03d}.json")
        excel_candidate = os.path.join(excel_dir, f"{level}_report_{seq:03d}.xlsx")
        if (
            (not os.path.exists(csv_candidate))
            and (not os.path.exists(summary_candidate))
            and (not os.path.exists(excel_candidate))
        ):
            return csv_candidate, summary_candidate, excel_candidate
        seq += 1


def fetch_access_token(
    token_url: str,
    token_path: str,
    timeout_sec: float,
    verify_ssl: bool,
    token_headers: Dict[str, str],
) -> Tuple[Optional[str], Optional[str]]:
    client = HttpClient(default_headers=token_headers, timeout_sec=timeout_sec, verify_ssl=verify_ssl)
    status, _, body, err = client.get(token_url)
    if status != 200:
        error_msg = err or f"token endpoint status={status}"
        return None, error_msg

    payload, parse_err = parse_json_bytes(body)
    if parse_err:
        return None, parse_err
    if not isinstance(payload, dict):
        return None, "token endpoint did not return a JSON object"

    token_val = None
    # primary path
    token_val = get_nested_value(payload, token_path)
    # useful fallbacks
    if token_val is None:
        token_val = payload.get("accessToken")
    if token_val is None and isinstance(payload.get("data"), dict):
        token_val = payload["data"].get("accessToken")

    if token_val is None:
        return None, f"token not found, tried path='{token_path}'"

    token_str = str(token_val).strip()
    if not token_str:
        return None, f"token empty at path='{token_path}'"
    return token_str, None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="GB record download stress test")
    parser.add_argument("--config", default="", help="JSON config file path")
    parser.add_argument("--base-url", default=None, help="API base URL, e.g. http://127.0.0.1:18080")
    parser.add_argument("--device-id", default=None)
    parser.add_argument("--channel-id", default=None)
    parser.add_argument("--access-token", default=None, help="Header access-token value")
    parser.add_argument("--api-key", default=None, help="Header api-key value if enabled")
    parser.add_argument("--token-url", default=None, help="Token endpoint URL, GET")
    parser.add_argument(
        "--token-path",
        default=None,
        help="Dot path in token JSON, default data.accessToken",
    )
    parser.add_argument(
        "--token-header",
        default=None,
        help="Auth header name for WVP requests, default access-token",
    )
    parser.add_argument(
        "--header",
        action="append",
        default=None,
        help='Custom header, can repeat, format: "Key: Value"',
    )
    parser.add_argument("--query-start", default=None, help="Query startTime, format: yyyy-MM-dd HH:mm:ss")
    parser.add_argument("--query-end", default=None, help="Query endTime, format: yyyy-MM-dd HH:mm:ss")
    parser.add_argument(
        "--fallback-start",
        default=None,
        help="Fallback startTime when query has no records / failed",
    )
    parser.add_argument(
        "--fallback-end",
        default=None,
        help="Fallback endTime when query has no records / failed",
    )
    parser.add_argument("--download-speed", type=int, default=None)
    parser.add_argument("--concurrency", type=int, default=None)
    parser.add_argument("--total-cases", type=int, default=None)
    parser.add_argument("--poll-interval", type=float, default=None)
    parser.add_argument("--poll-timeout", type=float, default=None)
    parser.add_argument(
        "--file-url-grace-sec",
        type=float,
        default=None,
        help="Extra wait after progress=1.0 for downLoadFilePath",
    )
    parser.add_argument("--request-timeout", type=float, default=None)
    parser.add_argument("--verify-ssl", action=argparse.BooleanOptionalAction, default=None)
    parser.add_argument("--prefer-https-file-url", action=argparse.BooleanOptionalAction, default=None)
    parser.add_argument("--min-download-bytes", type=int, default=None)
    parser.add_argument("--save-file", action=argparse.BooleanOptionalAction, default=None)
    parser.add_argument("--output-dir", default=None)
    parser.add_argument("--csv-out", default=None)
    parser.add_argument("--summary-out", default=None)
    parser.add_argument("--excel-out", default=None, help="Excel workbook output path (.xlsx)")
    parser.add_argument(
        "--export-excel",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Export detail and summary to an Excel workbook",
    )
    parser.add_argument("--run-level", default=None, help="Output name level, e.g. smoke/baseline/stress")
    parser.add_argument(
        "--auto-name-output",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Auto generate non-overwrite file names using level + sequence",
    )
    parser.add_argument(
        "--annotate-output-keys",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="Annotate output field names with Chinese descriptions",
    )
    parser.add_argument(
        "--selection-strategy",
        choices=["random", "latest", "earliest"],
        default=None,
        help="How to pick one clip from query result recordList",
    )
    parser.add_argument(
        "--clip-seconds",
        type=int,
        default=None,
        help="Trim clip duration for each case; 0 means use full record item duration",
    )
    parser.add_argument(
        "--allow-start-without-query",
        action=argparse.BooleanOptionalAction,
        default=None,
        help="If false, query failure will fail case immediately",
    )
    parser.add_argument("--seed", type=int, default=None)
    return parser.parse_args()


def normalize_or_empty(time_text: str) -> Optional[str]:
    if not time_text:
        return None
    return normalize_time_for_api(time_text)


def main() -> int:
    args = parse_args()

    file_cfg: Dict[str, Any] = {}
    if args.config:
        try:
            file_cfg = load_json_config(args.config)
        except Exception as err:
            print(f"[error] failed to load config file '{args.config}': {err}")
            return 2

    base_url = choose_string(args.base_url, get_config_value(file_cfg, "base_url", "baseUrl"), "")
    if not base_url:
        print("[error] base_url is required, set --base-url or config.base_url")
        return 2

    device_id = choose_string(
        args.device_id, get_config_value(file_cfg, "device_id", "deviceId"), "34020000001320000019"
    )
    channel_id = choose_string(
        args.channel_id, get_config_value(file_cfg, "channel_id", "channelId"), "34020000001320000019"
    )

    request_timeout = choose_float(
        args.request_timeout,
        get_config_value(file_cfg, "request_timeout", "requestTimeout"),
        35.0,
        minimum=1.0,
    )
    verify_ssl = choose_bool(args.verify_ssl, get_config_value(file_cfg, "verify_ssl", "verifySsl"), False)
    prefer_https_file_url = choose_bool(
        args.prefer_https_file_url,
        get_config_value(file_cfg, "prefer_https_file_url", "preferHttpsFileUrl"),
        False,
    )

    download_speed = choose_int(
        args.download_speed,
        get_config_value(file_cfg, "download_speed", "downloadSpeed"),
        4,
        minimum=1,
    )
    concurrency = choose_int(args.concurrency, get_config_value(file_cfg, "concurrency"), 5, minimum=1)
    total_cases = choose_int(args.total_cases, get_config_value(file_cfg, "total_cases", "totalCases"), 50, minimum=1)
    poll_interval = choose_float(
        args.poll_interval,
        get_config_value(file_cfg, "poll_interval", "pollInterval"),
        2.0,
        minimum=0.2,
    )
    poll_timeout = choose_float(
        args.poll_timeout,
        get_config_value(file_cfg, "poll_timeout", "pollTimeout"),
        240.0,
        minimum=5.0,
    )
    file_url_grace_sec = choose_float(
        args.file_url_grace_sec,
        get_config_value(file_cfg, "file_url_grace_sec", "fileUrlGraceSec"),
        20.0,
        minimum=0.0,
    )
    min_download_bytes = choose_int(
        args.min_download_bytes,
        get_config_value(file_cfg, "min_download_bytes", "minDownloadBytes"),
        1024,
        minimum=0,
    )
    save_file = choose_bool(args.save_file, get_config_value(file_cfg, "save_file", "saveFile"), True)
    output_dir = choose_string(
        args.output_dir, get_config_value(file_cfg, "output_dir", "outputDir"), "./gb_record_downloads"
    )
    csv_out = choose_string(
        args.csv_out, get_config_value(file_cfg, "csv_out", "csvOut"), "./gb_record_stress_detail.csv"
    )
    summary_out = choose_string(
        args.summary_out, get_config_value(file_cfg, "summary_out", "summaryOut"), "./gb_record_stress_summary.json"
    )
    excel_out = choose_string(
        args.excel_out, get_config_value(file_cfg, "excel_out", "excelOut"), "./gb_record_stress_report.xlsx"
    )
    run_level = sanitize_level(choose_string(args.run_level, get_config_value(file_cfg, "run_level", "runLevel"), "run"))
    auto_name_output = choose_bool(
        args.auto_name_output,
        get_config_value(file_cfg, "auto_name_output", "autoNameOutput"),
        True,
    )
    annotate_output_keys = choose_bool(
        args.annotate_output_keys,
        get_config_value(file_cfg, "annotate_output_keys", "annotateOutputKeys"),
        True,
    )
    export_excel = choose_bool(
        args.export_excel,
        get_config_value(file_cfg, "export_excel", "exportExcel"),
        True,
    )
    if auto_name_output:
        csv_out, summary_out, excel_out = resolve_output_paths_with_sequence(
            csv_out=csv_out,
            summary_out=summary_out,
            excel_out=excel_out,
            run_level=run_level,
        )
    selection_strategy = choose_string(
        args.selection_strategy,
        get_config_value(file_cfg, "selection_strategy", "selectionStrategy"),
        "random",
    )
    if selection_strategy not in {"random", "latest", "earliest"}:
        print("[error] selection_strategy must be one of random/latest/earliest")
        return 2
    clip_seconds = choose_int(args.clip_seconds, get_config_value(file_cfg, "clip_seconds", "clipSeconds"), 60, minimum=0)
    allow_start_without_query = choose_bool(
        args.allow_start_without_query,
        get_config_value(file_cfg, "allow_start_without_query", "allowStartWithoutQuery"),
        True,
    )
    seed = choose_int(args.seed, get_config_value(file_cfg, "seed"), 20260512)

    default_query_end = fmt_api_time(now_shanghai())
    default_query_start = fmt_api_time(now_shanghai() - timedelta(days=1))
    raw_query_start = choose_string(args.query_start, get_config_value(file_cfg, "query_start", "queryStart"), "")
    raw_query_end = choose_string(args.query_end, get_config_value(file_cfg, "query_end", "queryEnd"), "")
    raw_fallback_start = choose_string(
        args.fallback_start, get_config_value(file_cfg, "fallback_start", "fallbackStart"), ""
    )
    raw_fallback_end = choose_string(args.fallback_end, get_config_value(file_cfg, "fallback_end", "fallbackEnd"), "")

    query_start = normalize_or_empty(raw_query_start) or default_query_start
    query_end = normalize_or_empty(raw_query_end) or default_query_end
    fallback_start = normalize_or_empty(raw_fallback_start)
    fallback_end = normalize_or_empty(raw_fallback_end)

    if (fallback_start and not fallback_end) or (fallback_end and not fallback_start):
        print("[error] fallback_start and fallback_end must be provided together")
        return 2

    headers = {
        "Accept": "*/*",
        "User-Agent": "gb-record-stress/1.0",
    }
    headers.update(parse_headers_from_config(get_config_value(file_cfg, "headers", "header")))
    headers.update(parse_header_pairs(args.header))

    api_key = choose_string(args.api_key, get_config_value(file_cfg, "api_key", "apiKey"), "")
    if api_key:
        headers["api-key"] = api_key

    token_header = choose_string(args.token_header, get_config_value(file_cfg, "token_header", "tokenHeader"), "access-token")
    access_token = choose_string(args.access_token, get_config_value(file_cfg, "access_token", "accessToken"), "")
    token_url = choose_string(args.token_url, get_config_value(file_cfg, "token_url", "tokenUrl"), "")
    token_path = choose_string(args.token_path, get_config_value(file_cfg, "token_path", "tokenPath"), "data.accessToken")
    token_headers = parse_headers_from_config(get_config_value(file_cfg, "token_headers", "tokenHeaders"))
    if not token_headers:
        token_headers = {
            "Accept": "application/json",
            "User-Agent": "gb-record-stress-token/1.0",
        }

    token_source = "none"
    if access_token:
        token_source = "cli_or_config"
    elif token_url:
        fetched_token, token_err = fetch_access_token(
            token_url=token_url,
            token_path=token_path,
            timeout_sec=request_timeout,
            verify_ssl=verify_ssl,
            token_headers=token_headers,
        )
        if token_err:
            print(f"[error] fetch token failed from '{token_url}': {token_err}")
            return 2
        access_token = fetched_token or ""
        token_source = "token_url"

    if access_token:
        headers[token_header] = access_token
    else:
        print("[warn] no access token provided or fetched; protected endpoints may return 401")

    cfg = StressConfig(
        base_url=base_url,
        device_id=device_id,
        channel_id=channel_id,
        query_start=query_start,
        query_end=query_end,
        fallback_start=fallback_start,
        fallback_end=fallback_end,
        download_speed=download_speed,
        concurrency=concurrency,
        total_cases=total_cases,
        poll_interval=poll_interval,
        poll_timeout=poll_timeout,
        file_url_grace_sec=file_url_grace_sec,
        request_timeout=request_timeout,
        verify_ssl=verify_ssl,
        prefer_https_file_url=prefer_https_file_url,
        min_download_bytes=min_download_bytes,
        save_file=save_file,
        output_dir=output_dir,
        csv_out=csv_out,
        summary_out=summary_out,
        excel_out=excel_out,
        run_level=run_level,
        auto_name_output=auto_name_output,
        annotate_output_keys=annotate_output_keys,
        export_excel=export_excel,
        selection_strategy=selection_strategy,
        clip_seconds=clip_seconds,
        allow_start_without_query=allow_start_without_query,
        seed=seed,
    )

    if cfg.save_file:
        os.makedirs(cfg.output_dir, exist_ok=True)

    http = HttpClient(default_headers=headers, timeout_sec=cfg.request_timeout, verify_ssl=cfg.verify_ssl)

    print("=== GB Record Stress Test ===")
    print(f"base_url={cfg.base_url}")
    print(f"device_id={cfg.device_id}, channel_id={cfg.channel_id}")
    print(f"query_range={cfg.query_start} -> {cfg.query_end}")
    print(f"total_cases={cfg.total_cases}, concurrency={cfg.concurrency}")
    print(f"download_speed={cfg.download_speed}, clip_seconds={cfg.clip_seconds}")
    print(f"token_source={token_source}, token_header={token_header}")
    print(f"run_level={cfg.run_level}, auto_name_output={cfg.auto_name_output}")
    print(f"annotate_output_keys={cfg.annotate_output_keys}")
    print(f"export_excel={cfg.export_excel}")
    print(f"save_file={cfg.save_file}, output_dir={cfg.output_dir}")
    print()

    rows: List[Dict[str, Any]] = []
    rows_lock = threading.Lock()
    finished = 0
    begin = time.time()

    with ThreadPoolExecutor(max_workers=cfg.concurrency) as executor:
        futures = {
            executor.submit(run_case, case_id, cfg, http): case_id
            for case_id in range(1, cfg.total_cases + 1)
        }
        for fut in as_completed(futures):
            case_id = futures[fut]
            try:
                row = fut.result()
            except Exception as err:  # pragma: no cover
                row = {
                    "case_id": case_id,
                    "query_ok": False,
                    "start_ok": False,
                    "progress_complete": False,
                    "file_url_ready": False,
                    "download_ok": False,
                    "stop_ok": False,
                    "e2e_ok": False,
                    "error_stage": "executor",
                    "error_message": str(err),
                    "case_ms": 0,
                }
            with rows_lock:
                rows.append(row)
                finished += 1
                if finished % max(1, cfg.total_cases // 10) == 0 or finished == cfg.total_cases:
                    e2e_ok_count = sum(1 for r in rows if r.get("e2e_ok"))
                    print(f"[progress] {finished}/{cfg.total_cases} done, e2e_ok={e2e_ok_count}")

    total_ms = int((time.time() - begin) * 1000)
    rows.sort(key=lambda x: int(x.get("case_id", 0)))

    write_csv(cfg.csv_out, rows, annotate_output_keys=cfg.annotate_output_keys)
    summary = make_summary(cfg, rows)
    summary["run_total_ms"] = total_ms
    summary_for_output = annotate_object_keys(summary, annotate_output_keys=cfg.annotate_output_keys)

    os.makedirs(os.path.dirname(cfg.summary_out) or ".", exist_ok=True)
    with open(cfg.summary_out, "w", encoding="utf-8") as fh:
        json.dump(summary_for_output, fh, ensure_ascii=False, indent=2)

    excel_error = None
    if cfg.export_excel:
        excel_error = write_excel_workbook(
            path=cfg.excel_out,
            detail_rows=rows,
            summary_obj=summary_for_output,
            annotate_output_keys=cfg.annotate_output_keys,
        )

    print()
    print("=== Summary ===")
    print(json.dumps(summary["counts"], ensure_ascii=False, indent=2))
    print(json.dumps(summary["rates"], ensure_ascii=False, indent=2))
    print(f"detail_csv={os.path.abspath(cfg.csv_out)}")
    print(f"summary_json={os.path.abspath(cfg.summary_out)}")
    if cfg.export_excel:
        if excel_error:
            print(f"excel_xlsx_write_error={excel_error}")
        else:
            print(f"excel_xlsx={os.path.abspath(cfg.excel_out)}")
    print(f"run_total_ms={total_ms}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
