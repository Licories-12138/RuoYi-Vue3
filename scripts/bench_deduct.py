#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Day11 扣库存并发压测：对照 v0 / v1 / v2 / v3 四个版本。

关键设计：
  threading.Barrier 让 N 个线程在同一瞬间放出 —— 这是「超卖」能稳定复现的前提。
  如果线程是顺序启动的，第一个请求可能已经把库存扣完，后面的读到新值，反而看不出问题。

用法：
  python bench_deduct.py nolock   [并发数]
  python bench_deduct.py lock     [并发数]
  python bench_deduct.py atomic   [并发数]
  python bench_deduct.py version  [并发数]
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
DISH_ID = 80
MODE = sys.argv[1] if len(sys.argv) > 1 else "nolock"
N = int(sys.argv[2]) if len(sys.argv) > 2 else 20
COUNT = 1  # 每次扣 1


def login():
    body = json.dumps({"username": "admin", "password": "admin123"}).encode()
    req = urllib.request.Request(
        BASE + "/login", data=body, headers={"Content-Type": "application/json"}
    )
    return json.loads(urllib.request.urlopen(req, timeout=10).read())["token"]


def query_stock():
    """⚠️ 必须直接查数据库。
    /merchant/dish/{id} 走缓存，读到的是旧值，用它验收会得出错误结论。
    """
    sql = (f"select stock from `ry-vue`.tb_dish where id={DISH_ID}")
    out = subprocess.run(
        ["mysql", "-uroot", "-p1234", "-N", "-B", "-e", sql],
        capture_output=True, text=True, timeout=10,
    )
    return int(out.stdout.strip())


def main():
    token = login()
    results = []
    lock = threading.Lock()
    barrier = threading.Barrier(N)

    start_stock = query_stock()
    print(f"模式: {MODE} | 并发: {N} | 每次扣: {COUNT}")
    print(f"起始库存: {start_stock}")
    print("-" * 56)

    def worker(idx):
        barrier.wait()  # ← 同一瞬间放出
        req = urllib.request.Request(
            f"{BASE}/merchant/dish/{DISH_ID}/deduct/{MODE}?n={COUNT}",
            method="POST",
            headers={"Authorization": "Bearer " + token},
        )
        try:
            resp = json.loads(urllib.request.urlopen(req, timeout=60).read())
            # ⚠️ 若依的 GlobalExceptionHandler 对 ServiceException 也返回 HTTP 200，
            # 业务成功与否要看 body 里的 code（200 才是真成功）。
            if resp.get("code") == 200:
                with lock:
                    results.append(("ok", resp.get("stock")))
            else:
                with lock:
                    results.append(("biz-err", resp.get("msg", "")))
        except urllib.error.HTTPError as e:
            with lock:
                results.append(("http-err", e.read().decode()[:80]))
        except Exception as e:  # noqa: BLE001
            with lock:
                results.append(("exc", str(e)[:80]))

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(N)]
    t0 = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    cost = time.time() - t0

    ok = [r for r in results if r[0] == "ok"]
    bad = [r for r in results if r[0] != "ok"]

    end_stock = query_stock()
    expect_deduct = len(ok) * COUNT
    actual_deduct = start_stock - end_stock

    print(f"成功请求: {len(ok)} 个")
    if bad:
        print(f"失败请求: {len(bad)} 个  ← 前 3 条")
        for r in bad[:3]:
            print(f"    {r[0]}: {r[1]}")
    print(f"总耗时: {cost:.2f}s")
    print("-" * 56)
    print(f"起始库存        : {start_stock}")
    print(f"结束库存        : {end_stock}")
    print(f"成功扣减请求数  : {len(ok)} 个   （每次扣 {COUNT}）")
    print(f"库存实际减少量  : {actual_deduct}")
    print("-" * 56)
    if actual_deduct < expect_deduct:
        print(f"❌ 超卖！{len(ok)} 个请求称成功，库存只掉了 {actual_deduct} 份 "
              f"—— 差额 {expect_deduct - actual_deduct} 份被覆盖丢失")
    elif actual_deduct == expect_deduct:
        print(f"✅ 数据一致：成功 {len(ok)} 个，库存准确减少 {actual_deduct} 份")
    else:
        print(f"⚠️ 异常：库存减少量({actual_deduct}) > 成功请求数({expect_deduct})")


if __name__ == "__main__":
    main()
