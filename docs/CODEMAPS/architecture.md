<!-- Generated: 2026-03-30 | Files scanned: 11 | Token estimate: ~600 -->

# Architecture

## System Overview

```
┌──────────────────┐     AccessibilityAPI     ┌─────────────────────┐
│  MainActivity    │ ←──── listeners ────────→ │ AntutuAccessibility │
│  (UI / Controls) │                           │ Service (自动化引擎) │
└──────────────────┘                           └────────┬────────────┘
                                                        │ score data
                                                        ↓
                                               ┌────────────────┐     HTTP POST
                                               │ FeishuUploader │ ──────────→ 飞书 Webhook
                                               └────────────────┘
```

## Data Flow

1. 用户在 MainActivity 配置参数（冷却时间、轮次、名称）→ 启动循环
2. AntutuAccessibilityService 状态机驱动安兔兔跑分
3. 跑分完成 → readScore() 从 UI 树提取分数 → ScoreRecord
4. FeishuUploader.upload() 上报飞书 → callback 更新 uploaded 状态
5. 冷却等待 → 下一轮

## State Machine

```
IDLE → LAUNCHING → WAITING_START → TESTING → READING_SCORE → COOLDOWN → LAUNCHING ...
```

## Entry Points

- `MainActivity.onCreate()` — UI 入口
- `AntutuAccessibilityService.onAccessibilityEvent()` — 事件驱动入口
- `antutu_loop.sh` — ADB 脚本替代方案
