#!/bin/bash
# 安兔兔自动循环跑分脚本
# 用法: ./antutu_loop.sh [循环次数，默认无限]

DEVICE="10.214.74.236:5555"
PKG="com.antutu.ABenchMark"
RUN_ACTION="com.antutu.ABenchMark.RUN"
LOG_FILE="/home/hexin/ubuntu_ai_projects/antutu_results.log"
MAX_LOOPS=${1:-0}  # 0 = 无限循环
LOOP_COUNT=0
CHECK_INTERVAL=30  # 每30秒检查一次状态
MAX_WAIT=1200      # 单次跑分最多等20分钟

adb_cmd() {
    adb -s "$DEVICE" "$@"
}

# 获取UI中指定resource-id的text
get_ui_text() {
    local xml
    xml=$(adb_cmd shell uiautomator dump /sdcard/ui_dump.xml 2>/dev/null && adb_cmd shell cat /sdcard/ui_dump.xml 2>/dev/null)
    echo "$xml"
}

# 检查测试是否正在运行（界面上有"停止测试"按钮）
is_testing() {
    local xml
    xml=$(get_ui_text)
    echo "$xml" | grep -q "停止测试"
}

# 检查是否在结果页面
is_result_page() {
    local xml
    xml=$(get_ui_text)
    # 结果页面通常有"重新测试"或分数显示
    echo "$xml" | grep -qE "重新测试|再次测试|开始测试|mainTestStart"
}

# 检查是否在主页面（有"开始测试"按钮）
is_main_page() {
    local xml
    xml=$(get_ui_text)
    echo "$xml" | grep -qE "开始测试|mainTestStart"
}

# 获取当前前台包名
get_foreground_pkg() {
    adb_cmd shell dumpsys activity activities 2>/dev/null | grep -m1 "mResumedActivity" | grep -oP 'com\.[^\s/]+'
}

# 启动安兔兔跑分
start_benchmark() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 启动安兔兔跑分..."

    # 先强制停止安兔兔，确保干净状态
    adb_cmd shell am force-stop "$PKG" 2>/dev/null
    adb_cmd shell am force-stop "${PKG}.full" 2>/dev/null
    sleep 3

    # 通过Intent直接启动跑分
    adb_cmd shell am start -a "$RUN_ACTION" 2>/dev/null
    sleep 5

    # 检查是否有弹窗需要处理（如权限请求等）
    local xml
    xml=$(get_ui_text)

    # 处理可能的"允许"弹窗
    if echo "$xml" | grep -qE "允许|同意|确定"; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] 检测到弹窗，尝试点击确认..."
        # 尝试点击"允许"或"确定"
        local bounds
        bounds=$(echo "$xml" | grep -oP 'text="(允许|同意|确定)"[^]]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' | head -1 | grep -oP '\[\d+,\d+\]' | head -2)
        if [ -n "$bounds" ]; then
            local x1 y1 x2 y2
            x1=$(echo "$bounds" | head -1 | grep -oP '\d+' | head -1)
            y1=$(echo "$bounds" | head -1 | grep -oP '\d+' | tail -1)
            x2=$(echo "$bounds" | tail -1 | grep -oP '\d+' | head -1)
            y2=$(echo "$bounds" | tail -1 | grep -oP '\d+' | tail -1)
            local cx=$(( (x1 + x2) / 2 ))
            local cy=$(( (y1 + y2) / 2 ))
            adb_cmd shell input tap "$cx" "$cy"
            sleep 2
        fi
    fi

    # 如果没有自动开始跑分，检查是否需要手动点击开始
    if is_main_page; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] 在主页面，点击开始测试..."
        xml=$(get_ui_text)
        # 找到"开始测试"按钮的坐标
        local start_bounds
        start_bounds=$(echo "$xml" | grep -oP 'text="开始测试"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"' | grep -oP '\[([0-9]+),([0-9]+)\]')
        if [ -n "$start_bounds" ]; then
            local x1 y1 x2 y2
            x1=$(echo "$start_bounds" | head -1 | sed 's/\[//;s/\]//' | cut -d, -f1)
            y1=$(echo "$start_bounds" | head -1 | sed 's/\[//;s/\]//' | cut -d, -f2)
            x2=$(echo "$start_bounds" | tail -1 | sed 's/\[//;s/\]//' | cut -d, -f1)
            y2=$(echo "$start_bounds" | tail -1 | sed 's/\[//;s/\]//' | cut -d, -f2)
            local cx=$(( (x1 + x2) / 2 ))
            local cy=$(( (y1 + y2) / 2 ))
            adb_cmd shell input tap "$cx" "$cy"
            sleep 3
        else
            # 备用：尝试resource-id
            start_bounds=$(echo "$xml" | grep -oP 'resource-id="com.antutu.ABenchMark:id/mainTestStart"[^>]*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"' | grep -oP '\[([0-9]+),([0-9]+)\]')
            if [ -n "$start_bounds" ]; then
                local x1 y1 x2 y2
                x1=$(echo "$start_bounds" | head -1 | sed 's/\[//;s/\]//' | cut -d, -f1)
                y1=$(echo "$start_bounds" | head -1 | sed 's/\[//;s/\]//' | cut -d, -f2)
                x2=$(echo "$start_bounds" | tail -1 | sed 's/\[//;s/\]//' | cut -d, -f1)
                y2=$(echo "$start_bounds" | tail -1 | sed 's/\[//;s/\]//' | cut -d, -f2)
                local cx=$(( (x1 + x2) / 2 ))
                local cy=$(( (y1 + y2) / 2 ))
                adb_cmd shell input tap "$cx" "$cy"
                sleep 3
            fi
        fi
    fi
}

# 等待跑分完成
wait_for_completion() {
    local elapsed=0
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 等待跑分完成..."

    while [ $elapsed -lt $MAX_WAIT ]; do
        sleep "$CHECK_INTERVAL"
        elapsed=$((elapsed + CHECK_INTERVAL))

        # 检查设备是否还在线
        if ! adb_cmd shell echo "alive" >/dev/null 2>&1; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] 设备离线，等待重连..."
            sleep 10
            continue
        fi

        # 获取当前UI状态
        local xml
        xml=$(get_ui_text)

        # 获取进度百分比
        local percent
        percent=$(echo "$xml" | grep -oP 'resource-id="com.antutu.ABenchMark:id/mainTestPercent"[^>]*text="([0-9]+)"' | grep -oP 'text="[0-9]+"' | grep -oP '[0-9]+')
        if [ -n "$percent" ]; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] 进度: ${percent}% (已等待 ${elapsed}s)"
        fi

        # 检查是否还在测试中
        if ! echo "$xml" | grep -q "停止测试"; then
            # 不在测试中了，可能完成了
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] 测试似乎已完成"
            sleep 5
            return 0
        fi
    done

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 等待超时 (${MAX_WAIT}s)"
    return 1
}

# 记录跑分结果
log_result() {
    local xml
    xml=$(get_ui_text)

    # 截图保存
    local timestamp
    timestamp=$(date '+%Y%m%d_%H%M%S')
    adb_cmd shell screencap -p /sdcard/antutu_result_${timestamp}.png 2>/dev/null
    adb_cmd pull /sdcard/antutu_result_${timestamp}.png "/home/hexin/ubuntu_ai_projects/antutu_result_${timestamp}.png" 2>/dev/null

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] === 第 $((LOOP_COUNT + 1)) 轮跑分完成 ===" | tee -a "$LOG_FILE"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 截图已保存: antutu_result_${timestamp}.png" | tee -a "$LOG_FILE"
    echo "---" >> "$LOG_FILE"
}

# 主循环
main() {
    echo "========================================"
    echo "  安兔兔自动循环跑分脚本"
    echo "  设备: $DEVICE"
    echo "  日志: $LOG_FILE"
    if [ "$MAX_LOOPS" -gt 0 ]; then
        echo "  计划运行: ${MAX_LOOPS} 次"
    else
        echo "  计划运行: 无限循环 (Ctrl+C 停止)"
    fi
    echo "========================================"
    echo ""

    # 检查设备连接
    if ! adb_cmd shell echo "alive" >/dev/null 2>&1; then
        echo "错误: 设备未连接，请检查 ADB 连接"
        exit 1
    fi

    # 保持屏幕常亮
    adb_cmd shell svc power stayon true 2>/dev/null
    adb_cmd shell settings put system screen_off_timeout 1800000 2>/dev/null

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 已设置屏幕常亮" | tee -a "$LOG_FILE"

    # 检查是否已经在跑分中
    if is_testing; then
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] 检测到正在跑分，等待当前轮次完成..."
        wait_for_completion
        log_result
        LOOP_COUNT=$((LOOP_COUNT + 1))
    fi

    while true; do
        # 检查是否达到最大循环次数
        if [ "$MAX_LOOPS" -gt 0 ] && [ "$LOOP_COUNT" -ge "$MAX_LOOPS" ]; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] 已完成 ${MAX_LOOPS} 轮跑分，退出"
            break
        fi

        echo ""
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] ========== 开始第 $((LOOP_COUNT + 1)) 轮跑分 =========="

        # 启动跑分
        start_benchmark

        # 等一会儿确认测试已经开始
        sleep 10

        if is_testing; then
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] 跑分已启动，等待完成..."
            wait_for_completion
            log_result
            LOOP_COUNT=$((LOOP_COUNT + 1))
        else
            echo "[$(date '+%Y-%m-%d %H:%M:%S')] 跑分似乎没有成功启动，10秒后重试..."
            sleep 10
        fi

        # 每轮之间等待一小段时间让设备散热
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] 等待30秒让设备散热..."
        sleep 30
    done

    echo ""
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 总共完成 ${LOOP_COUNT} 轮跑分"
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] 结果日志: $LOG_FILE"
}

# 捕获 Ctrl+C
trap 'echo ""; echo "[$(date "+%Y-%m-%d %H:%M:%S")] 用户中断，已完成 ${LOOP_COUNT} 轮跑分"; echo "结果日志: $LOG_FILE"; exit 0' INT TERM

main
