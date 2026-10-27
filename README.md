# 桌面模式（Desktop Mode Toggle）

用 Shizuku 打開 Android 原生桌面模式的相關開關，並在外接螢幕時只關掉平板（手機）的內建螢幕。
目標裝置：Xiaomi Pad Mini（USB-C DP Alt Mode）。

## 功能

- **螢幕**：列出內建與外接螢幕，插拔時自動更新
- **平板螢幕**：只關掉內建面板電源，系統保持清醒、外接畫面照常輸出（同 scrcpy `--turn-screen-off` 的做法）
  - Android 15+ 先試 `IDisplayManager.requestDisplayPower`，不行再用 `SurfaceControl.setDisplayPowerMode`
  - 下拉選單有「平板螢幕」快速設定磚可一鍵切換
  - 「接電源時保持喚醒」＝ `settings put global stay_on_while_plugged_in 7`
- **桌面模式開關**（改完要重開機）

| 開關 | 寫入 |
|---|---|
| 自由視窗 | `settings put global enable_freeform_support 1` |
| 強制可調整大小 | `settings put global force_resizable_activities 1` |
| 外接螢幕強制桌面 | `settings put global force_desktop_mode_on_external_displays 1` |
| 桌面視窗功能 | `settings put global override_desktop_mode_features 1` |
| 桌面體驗功能（實驗） | `settings put global override_desktop_experience_features 1` |
| 解除機型限制（實驗） | `setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false` |

「全部還原」會把前三個設回 0、覆寫類刪除、屬性設回 true。

## 使用

1. 打開 Shizuku 並啟動服務
2. 開 App → 點上方狀態列授權連線
3. 「一鍵開啟」→「重新開機」→ 重開後再啟動 Shizuku → 接上外接螢幕看結果

## 打包

推到 `main` 會由 GitHub Actions 自動編譯，APK 在 Releases。
