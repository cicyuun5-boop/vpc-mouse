package cc.vpc.hidmouse

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 界面只做三件事：要权限、起服务、显示 IP（电脑要连这个 IP 的 8089）。
 * 权限没给就**不启动服务**——Android 14 起，前台服务类型 connectedDevice 缺蓝牙权限会直接抛异常。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var ipText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 140, 60, 60)
        }

        status = TextView(this).apply { text = "未启动"; textSize = 18f }
        ipText = TextView(this).apply { text = "本机 IP：检测中…"; textSize = 16f }

        val startBtn = Button(this).apply { text = "启动 HID 鼠标服务" }
        val stopBtn = Button(this).apply { text = "停止" }

        root.addView(status)
        root.addView(ipText)
        root.addView(startBtn)
        root.addView(stopBtn)
        setContentView(root)

        if (!hasBtPermission()) requestBtPermission()
        ipText.text = "本机 IP：" + NetUtil.localIpv4(this)

        startBtn.setOnClickListener {
            if (!hasBtPermission()) {
                requestBtPermission()
                status.text = "请先允许「附近的设备」权限，然后再点一次"
                return@setOnClickListener
            }
            val i = Intent(this, HidMouseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            status.text = "服务已启动，等待电脑连接 :8089"
            ipText.text = "本机 IP：" + NetUtil.localIpv4(this)
            Toast.makeText(this, "已启动，电脑连 " + NetUtil.localIpv4(this) + ":8089", Toast.LENGTH_LONG).show()
        }

        stopBtn.setOnClickListener {
            stopService(Intent(this, HidMouseService::class.java))
            status.text = "已停止"
        }
    }

    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = arrayListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN)
            if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        }
    }
}
