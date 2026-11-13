package com.ran.eldervideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 两件事：
 * 1) 开机完成后把桌面拉起来（HOME 应用系统本来会拉起，这是保险）；
 * 2) 插上充电器弹出充电页。
 * 注意：HyperOS 上后台启动 Activity 受「后台弹出界面」限制，
 * 安装后需要 MIUIOP(10021)=allow（adb appops set ... 10021 allow），
 * 与 CallActivity 是同一个开关，装包脚本里一起设。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                Intent.ACTION_POWER_CONNECTED -> {
                    // 1.0.34：不再开独立充电页，直接拉桌面并带「显示充电锁屏」标记
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("show_charge", true)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("ElderBoot", "onReceive failed: ${t.javaClass.simpleName} ${t.message}")
        }
    }
}
