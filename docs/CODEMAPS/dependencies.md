<!-- Generated: 2026-03-30 | Files scanned: 4 | Token estimate: ~300 -->

# Dependencies

## Android Libraries
- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.11.0`
- `androidx.constraintlayout:constraintlayout:2.1.4`

## Build Tools
- Android Gradle Plugin: 8.7.0
- Kotlin: 1.9.22
- Java: 17
- Compile SDK: 35, Min SDK: 26

## External Services
- 飞书 Webhook (结果上报)

## System APIs (Hidden/Privileged)
- `AccessibilityManager.setAccessibilityServiceEnabled()` — 反射调用
- `Settings.Secure.WRITE_SECURE_SETTINGS` — 需要 system UID
- `AppOpsManager.setMode()` — 反射调用解除受限设置

## Signing
- platform.jks (system 签名, sharedUserId=android.uid.system)

## Maven Repositories
- maven.aliyun.com (mirror)
- google(), mavenCentral()
