package com.leapauto.app

object AppReleaseInfo {
    val currentVersion: String
        get() = BuildConfig.VERSION_NAME

    // Update this text together with apkVersionName before each delivery package.
    const val currentReleaseNotes = "1. 蓝牙钥匙支持试用系统靠近监听，可切回兼容模式。\n2. 暂停后台连接后保留暂停状态。"
}
