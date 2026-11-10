package cc.vpc.hidmouse

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 极简界面，原则：**启动阶段不做任何可能抛异常的调用**。
 * - 不启动就读 IP（之前就是因为读 IP 缺权限，导致闪退）
 * - onCreate 整体包 try/catch，出错就把堆栈显示在屏幕上，绝不静默退出
 */
class MainActivity : Activity() {

    private var status: TextView? = null
    private var ipText: TextView? = null
    private var connText: TextView? = null
    private var logView: TextView? = null

    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            connText?.text = "连接状态：" + HidMouseService.statusText
            logView?.text = HidMouseService.eventText()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 屏幕常亮：安卓息屏会挂起蓝牙，HID 链路随之断开
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            buildUi()
        } catch (t: Throwable) {
            showFatal(t)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 140, 60, 60)
        }

        val st = TextView(this).apply { text = "未启动"; textSize = 18f }
        val ip = TextView(this).apply { text = "IP：点启动后显示"; textSize = 16f }
        val conn = TextView(this).apply { text = "连接状态：未启动"; textSize = 16f }
        val logv = TextView(this).apply { text = ""; textSize = 11f }
        val startBtn = Button(this).apply { text = "启动 HID 鼠标服务" }
        val stopBtn = Button(this).apply { text = "停止" }

        root.addView(st)
        root.addView(conn)
        root.addView(logv)
        root.addView(ip)
        root.addView(startBtn)
        root.addView(stopBtn)
        setContentView(root)

        status = st
        ipText = ip
        connText = conn
        logView = logv

        if (!hasBtPermission()) requestBtPermission()

        startBtn.setOnClickListener { onStartClicked() }
        stopBtn.setOnClickListener {
            runCatching { stopService(Intent(this, HidMouseService::class.java)) }
            st.text = "已停止"
        }
    }

    private fun onStartClicked() {
        val st = status ?: return
        if (!hasBtPermission()) {
            requestBtPermission()
            st.text = "请先允许「附近的设备」权限，再点一次"
            return
        }
        try {
            val i = Intent(this, HidMouseService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
            st.text = "服务已启动，等待电脑连接 :8089"

            val ip = NetUtil.localIpv4(this)
            ipText?.text = "IP：$ip"
            Toast.makeText(this, "电脑连 $ip:8089", Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            st.text = "启动失败：" + (t.message ?: t.javaClass.simpleName)
            Log.e("VpcHid", "start failed", t)
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
            if (need.isNotEmpty()) runCatching { requestPermissions(need.toTypedArray(), 1) }
        }
    }

    /** 兜底：任何未捕获异常都显示在屏幕上，便于排查，而不是闪退 */
    override fun onStart() {
        super.onStart()
        ui.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(ticker)
    }

    private fun showFatal(t: Throwable) {
        val tv = TextView(this).apply {
            text = "启动失败：\n\n" + Log.getStackTraceString(t)
            textSize = 11f
            setPadding(40, 80, 40, 40)
        }
        runCatching { setContentView(tv) }
    }
}
