package com.enweilo.desktopmode;

interface IDesktopService {
    // Shizuku 規定的結束服務代碼
    void destroy() = 16777114;

    // 環境資訊
    String probe() = 1;

    // spec 每行 "kind\tkey"（kind = global 或 prop），回傳每行 "kind\tkey\tvalue"
    String readValues(String spec) = 2;

    // 寫入一個值；value 為空字串代表刪除（global）或設成空（prop）
    String setValue(String kind, String key, String value) = 3;

    // 只開關平板內建螢幕面板，系統保持清醒
    String setPanelPower(boolean on) = 4;
    boolean isPanelOff() = 5;

    String reboot() = 6;
}
