#!/usr/bin/env python3
"""
向 radio.cn (云听) 开放接口发送带签名的 GET 请求，按地区 / 地区+类型筛选广播电台。

签名算法（来自 www.radio.cn/pc-portal/js/api.js）:
    signText = "<参数按 key 字母排序后的 a=b&c=d>" + "&timestamp=" + tm + "&key=" + KEY
    sign     = MD5(signText).upper()
其中 KEY 为前端硬编码的固定盐，timestamp 为当前毫秒时间戳（服务端校验时效）。

用法示例:
    python3 radio_request.py 北京            # 按省份名查询全部分类
    python3 radio_request.py 110000          # 按省份代码查询
    python3 radio_request.py 广东 音乐        # 省份 + 分类（名称）
    python3 radio_request.py 广东 5          # 省份 + 分类（代码）
    python3 radio_request.py --list           # 列出可用的省份和分类
    python3 radio_request.py 北京 --json      # 输出原始 JSON
"""

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request

# 前端硬编码的签名密钥（盐）
SIGN_KEY = "f0fc4c668392f9f9a447e48584c214ee"
BASE_URL = "https://ytmsout.radio.cn"

_HERE = os.path.dirname(os.path.abspath(__file__))


def _load_lookup(filename: str, name_key: str, code_key: str) -> dict:
    """从同目录的 JSON 文件加载 {名称: 代码} 映射；文件缺失时返回空表。"""
    path = os.path.join(_HERE, filename)
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        data = json.load(f).get("data") or []
    return {str(item[name_key]): str(item[code_key]) for item in data}


# 中文名 -> 代码 映射（来自 provinces.json / categories.json）
PROVINCES = _load_lookup("provinces.json", "provinceName", "provinceCode")
CATEGORIES = _load_lookup("categories.json", "categoryName", "id")


def resolve(value: str, table: dict, what: str) -> str:
    """把用户输入（中文名或代码）解析为代码字符串。"""
    value = str(value).strip()
    if value in table:                 # 中文名命中
        return table[value]
    if value in table.values():        # 直接给的就是代码
        return value
    raise SystemExit(
        f"未识别的{what}: {value!r}\n可用{what}: " + "、".join(table.keys())
    )


def make_sign(params: dict, timestamp: int) -> str:
    """按算法计算 GET 请求的 sign。"""
    sorted_params = "&".join(f"{k}={params[k]}" for k in sorted(params))
    sign_text = f"{sorted_params}&timestamp={timestamp}&key={SIGN_KEY}"
    return hashlib.md5(sign_text.encode("utf-8")).hexdigest().upper()


def request_api(path: str, params: dict) -> dict:
    """对指定接口发起带签名的 GET 请求，返回解析后的 JSON。"""
    timestamp = int(time.time() * 1000)
    sign = make_sign(params, timestamp)

    url = f"{BASE_URL}{path}?{urllib.parse.urlencode(params)}"
    headers = {
        "Content-Type": "application/json",
        "platformCode": "WEB",
        "equipmentId": "0000",
        "timestamp": str(timestamp),
        "sign": sign,
        "Origin": "https://www.radio.cn",
        "Referer": "https://www.radio.cn/",
        "User-Agent": (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        ),
    }

    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))


def get_broadcast_list(province: str, category: str = "全部") -> dict:
    """按地区（必填）和分类（可选）查询广播电台列表。"""
    params = {
        "categoryId": resolve(category, CATEGORIES, "分类"),
        "provinceCode": resolve(province, PROVINCES, "地区"),
    }
    return request_api("/web/appBroadcast/list", params)


def print_tables():
    """打印可用的省份和分类对照表。"""
    print("可用地区（provinceCode）:")
    print("  " + "、".join(f"{n}={c}" for n, c in PROVINCES.items()))
    print("\n可用分类（categoryId）:")
    print("  " + "、".join(f"{n}={c}" for n, c in CATEGORIES.items()))


def main():
    parser = argparse.ArgumentParser(
        description="查询 radio.cn 广播电台列表（支持按地区 / 地区+类型筛选）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("province", nargs="?", help="地区名或代码，如 北京 / 110000")
    parser.add_argument("category", nargs="?", default="全部", help="分类名或代码，如 音乐 / 5（默认：全部）")
    parser.add_argument("--list", action="store_true", help="列出可用的地区和分类后退出")
    parser.add_argument("--json", action="store_true", help="输出原始 JSON")
    args = parser.parse_args()

    if args.list:
        print_tables()
        return

    if not args.province:
        parser.error("请提供地区名或代码（用 --list 查看可选值）")

    result = get_broadcast_list(args.province, args.category)

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return

    data = result.get("data") or []
    print(f"地区={args.province} 分类={args.category} | code={result.get('code')} "
          f"{result.get('message')} | 共 {len(data)} 个电台\n")
    for item in data:
        print(f"  [{item.get('contentId')}] {item.get('title')}  —  {item.get('subtitle')}")


if __name__ == "__main__":
    main()
