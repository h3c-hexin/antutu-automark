<!-- Generated: 2026-03-30 | Files scanned: 3 | Token estimate: ~500 -->

# Backend (Service Layer)

## AntutuAccessibilityService (533 lines)

核心自动化引擎，状态机模式。

### States
| State | 描述 | 转换条件 |
|-------|------|----------|
| IDLE | 空闲 | startLoop() → LAUNCHING |
| LAUNCHING | 启动安兔兔 | 检测到窗口 → WAITING_START |
| WAITING_START | 等待开始按钮 | 点击成功 → TESTING |
| TESTING | 跑分中 | 未检测到"停止测试" → READING_SCORE |
| READING_SCORE | 读取分数 | 读取完成 → COOLDOWN |
| COOLDOWN | 散热等待 | 超时 → LAUNCHING |

### Key Methods
- `launchBenchmark()` — force-stop + am start
- `pollForStartButton()` — 指数退避重试找按钮 (max 12次/60s)
- `checkAntutuState()` — 事件分发核心
- `readScore()` — 遍历 view hierarchy 提取分数 (max 3次)
- `handleDialogs()` — 自动点击权限弹窗
- `clickNode()` — performAction + gesture fallback

### Safeguards
- 事件去抖: 500ms
- 最短测试时间: 10分钟
- 完成确认: 连续3次无"停止测试"

## FeishuUploader (48 lines)

- `upload(name, data, time, callback)` → POST JSON to webhook
- 重试: 3次, 间隔2s
- 回调在主线程执行

## Key Files
```
app/src/main/java/com/autorun/antutu/
  AntutuAccessibilityService.kt  (533 lines, 自动化引擎)
  FeishuUploader.kt              (48 lines, 飞书上报)
  MainActivity.kt                (330 lines, UI)
```
