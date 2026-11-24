# 需求全流程 · 多角色串联长视频

**时长 1 分 45 秒 · 1440×900 · 30fps · 23 步 · 无配音（底部字幕条讲解）· 基线与 main `6fae84d`。**

与 `../main-chain/` 那 8 条章节版的区别：这一条是**一条需求从提报到关闭的连续过程**，每一步都是**在真实页面上点出来的**（不是截结果页），并且**跨 6 个账号切换**：业务方 biz1 → 产品经理 pm → 项目域负责人 tl1 → 研发 dev1 → 测试 qa1 → 回到业务方收口。

- 每一步录两帧：**动作前**（目标按钮用蓝框高亮）+ **动作后**（状态变化），共 46 帧 + 片头片尾。
- 数据由 `make platform-demo-reset` 重建；跑完真实链路后终态 `BUS CLOSED / PDR ACCEPTED_CLOSED / RD DONE`。
- 录制踩过的坑（重录前必读）：el-select 必须用真实鼠标事件才能展开；弹窗要取**可见** overlay 的确认按钮；`decompose` 不能给父需求传人天；RD/PDR 各自都要过工时评估门；`merge/finish` 必须在 `test_pass` 之后。

## 23 步

| # | 角色 | 做了什么 | 为什么这一步重要 |
|---|---|---|---|
| 1 | 业务方 biz1 | 业务方在需求池点「提报需求」 | 业务方只能提报与撤回，不能自己审批 |
| 2 | 业务方 biz1 | 选类型、填标题、选业务域与优先级 | 需求类型是卡片；业务域来自字典（ORD · 订单管理） |
| 3 | 业务方 biz1 | 提交后状态「已提报」，内容仍可改 | 提交审批后业务方只能撤回 |
| 4 | 业务方 biz1 | 点「提交审批」→ 审批中 | 右侧审批进度显示当前卡在「产品评审」 |
| 5 | 产品经理 pm | 切到产品经理：审批中心出现待办 | 谁能批由审批流决定 |
| 6 | 产品经理 pm | 点「同意」，确认后单据流转 | 驳回必须填原因，写进流转记录与审计 |
| 7 | 产品经理 pm | 待评审：产品经理发起评审 | 评审是独立单据（REQ_REVIEW），通过后自动入池 |
| 8 | 产品经理 pm | 同意评审单 → 自动入池 | 评审通过由回调推进 POOLED，无需再手动点 |
| 9 | 产品经理 pm | 已入池（POOLED） | 未入池时「拆解子需求」是禁用的 |
| 10 | 产品经理 pm | 业务需求拆成产品需求（PDR） | 父需求必须 POOLED 才能拆（409 PARENT_NOT_POOLED） |
| 11 | 项目域负责人 tl1 | PMO/研发提交工时评估（6.5 天） | 提交评估要 req:deliver：产品经理只审、不提 |
| 12 | 产品经理 pm | 产品经理同意工时评估 | 评估未通过时，排期与开始开发都会被 409 拦住 |
| 13 | 产品经理 pm | 排期 → 产品需求进入 SCHEDULED | 排期后版本计划才能挂这条需求 |
| 14 | 产品经理 pm | 再拆成研发需求（RD），预估 6.5 人天 | RD 是研发任务、工时与提测的载体 |
| 15 | 项目域负责人 tl1 | 转派承接厂商 VEN-A（原因写审计） | 厂商账号只能看到本厂商承接的需求 |
| 16 | 研发工程师 dev1 | 研发点「开始开发」→ IN_DEV | 评估已通过，start_dev 才被放行 |
| 17 | 研发工程师 dev1 | 任务拆到看板：DOING → DONE | 任务完成回写需求完成率；实际人天 = 工时 ÷ 8 |
| 18 | 研发工程师 dev1 | 提测 → PENDING_TEST | 提测后测试同学才能执行用例并给结论 |
| 19 | 测试工程师 qa1 | 测试执行通过（TEST_PASSED） | 失败会一键转缺陷回研发修复 |
| 20 | 研发工程师 dev1 | 合入 → 完成（RD DONE） | TEST_PASSED → MERGED → DONE |
| 21 | 项目域负责人 tl1 | 交付登记 → 产品需求 DELIVERED | 交付记录写审计，与后续验收关联 |
| 22 | 产品经理 pm | 产品经理验收 → ACCEPTED_CLOSED | 验收后需求闭环，父需求人天/进度汇总落定 |
| 23 | 业务方 biz1 | 业务方看汇总人天，关闭业务需求 | 父需求人天/进度由子需求实时汇总 |

## 复现

```bash
make platform-up && make platform-demo-reset        # 干净数据（80/80）
# Chrome: --headless=new --no-sandbox --remote-debugging-port=9222 --user-data-dir=/tmp/chrome-cdp
node long/record-all.mjs                          # 真实操作 + 逐步截图 → /tmp/rec/long/frames/
node long/build-frames.mjs                        # 套字幕条 → /tmp/v23/out/
cd /tmp/v23 && ffmpeg -y -f concat -safe 0 -i list.txt -vf "fps=30,format=yuv420p" \
  -c:v libx264 -preset medium -crf 23 -movflags +faststart long.mp4
```

| 文件 | 作用 |
|---|---|
| `需求全流程-多角色-长视频.mp4` | 成片 |
| `record-all.mjs` | 23 步真实点击录制（复用 `lib.mjs` 的 CDP 驱动 + `runner.mjs` 的前后两帧）|
| `beats.json` | 每步的角色/标题/说明/帧文件名/执行结果 |
| `build-frames.mjs` + `items.json` | 把截图套上字幕条并逐帧渲染 |

## 已知限制

- 无配音、无鼠标轨迹动画（只有动作前蓝框高亮）。
- 拆解子需求、转派厂商、建任务这三步用接口造数据后拍结果页（弹窗字段多、自动化成本高），其余 20 步都是页面上真点的。
- 演示 MR 是示例地址 `https://git.example.com/mr/r32-e2e`。
