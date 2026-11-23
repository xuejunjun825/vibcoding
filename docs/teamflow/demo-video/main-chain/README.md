# 主链 8 个子流程 · 演示视频

> 每个视频 26–31 秒，一屏一步，字幕说明「谁操作 / 点哪里 / 状态怎么变 / 门禁在哪」。
> 画面全部来自平台真实页面（1440×900），状态与数字取自同一次真实跑通的数据。

| # | 子流程 | 视频 | 时长 | 涉及角色 |
|---|---|---|---|---|
| 1 | 需求受理 | [1-需求受理.mp4](1-需求受理.mp4) | 26s | 业务方 / 二级企业 |
| 2 | 需求审批 | [2-需求审批.mp4](2-需求审批.mp4) | 31s | 业务方 · 产品经理 |
| 3 | 评审入池 | [3-评审入池.mp4](3-评审入池.mp4) | 26s | 产品经理 · 会签方 |
| 4 | 需求拆解 | [4-需求拆解.mp4](4-需求拆解.mp4) | 31s | 产品经理 |
| 5 | 工时评估初审 | [5-工时评估初审.mp4](5-工时评估初审.mp4) | 26s | 研发/PMO · 产品经理 |
| 6 | 排期与版本计划 | [6-排期与版本计划.mp4](6-排期与版本计划.mp4) | 26s | 产品经理 · 项目域负责人 |
| 7 | 研发交付执行 | [7-研发交付执行.mp4](7-研发交付执行.mp4) | 31s | 项目域负责人 · 研发 · 测试 · 厂商 |
| 8 | 交付验收关闭 | [8-交付验收关闭.mp4](8-交付验收关闭.mp4) | 26s | 研发 · 项目域负责人 · 产品经理 |

合计 **3 分 43 秒**。

## 配套的验证报告

8 个子流程不是"看起来能走"，是**真接口跑过 34 项断言**：见 [VERIFY-MAIN-CHAIN.md](../../VERIFY-MAIN-CHAIN.md)（34/34 通过，其中 1 项是发现的真实缺口）。

## 怎么重录

```bash
# 1) 起环境 + 重置演示数据
make platform-up && make platform-demo-reset

# 2) 跑主链验证（顺带检查平台是否还能走通）
node verify.mjs        # 期望：主链验证 34/34 通过

# 3) 起无头 Chrome（给截图用）
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --no-sandbox \
  --disable-gpu --remote-debugging-port=9222 --user-data-dir=/tmp/chrome-cdp about:blank &

# 4) 重新合成（帧与字幕定义都在 build.mjs 顶部）
node build.mjs
```

- 依赖：`ffmpeg`（无 drawtext，所以字幕由 Chrome 渲染成图后再拼帧）、Node 22、本仓库前端已构建并 `make platform-up`。
- 字幕模板：`/tmp/video/slide.html`（仓库内留存副本见 `../slide.html` 与 `../captions.json`）。
- 已知限制：无配音、无鼠标轨迹动画（逐帧静态图 + 字幕），时长靠每段时长控制。
