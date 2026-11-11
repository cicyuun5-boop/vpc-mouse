package cc.vpc.hidmouse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 把手机注册成蓝牙 HID 鼠标，并在 TCP 8089 上接收 PC 的指令。
 *
 * 协议：
 *   握手（必做，2 秒内）："VPC1\n"
 *   移动：             "dx dy\n"          整数
 *   点击：             "dx dy b\n"        b: 1=左键 2=右键 4=中键（一次性脉冲）
 *
 * 设计要点：
 *   - 权限不足时**不启动**（Android 14 起 FGS 类型 connectedDevice 要求蓝牙权限已授予，否则直接崩）
 *   - 只保留一个客户端，新连接会顶掉旧的
 *   - 高频指令合并后按 125 Hz 发出，避免队列堆积
 *   - 点击做「按下-释放」两帧，避免只按不放
 */
class HidMouseService : Service() {

    companion object {
        @Volatile var recvLines: Long = 0
        @Volatile var sendOk: Long = 0
        @Volatile var sendFail: Long = 0

        @Volatile var statusText: String = "未启动"
            private set

        private val events = java.util.ArrayDeque<String>()

        fun addEvent(msg: String) {
            synchronized(events) {
                if (events.size >= 6) events.removeFirst()
                events.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + msg)
            }
        }

        fun eventText(): String = synchronized(events) { events.joinToString(System.lineSeparator()) }
        private const val TAG = "VpcHid"
        private const val CHANNEL_ID = "vpc_hid"
        private const val PORT = 8089
        private const val EMIT_INTERVAL_MS = 8L
        private const val HANDSHAKE = "VPC1"
        private const val PREF = "vpc_target"
        private const val KEY_LAST_ADDR = "last_addr"

        // 用 intArrayOf 写，再统一转 Byte：Kotlin 不允许 >127 的字面量直接进 byteArrayOf
        private val REPORT_DESC: ByteArray = intArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1, 0x01,
            0x85, 0x02,                     // Report ID = 2（与 GhostHID 一致，Windows 才认）
            0x09, 0x01, 0xA1, 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
            0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
            0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31,
            0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x02, 0x81, 0x06,
            0xC0, 0xC0
        ).map { it.toByte() }.toByteArray()
    }

    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var lastDevice: BluetoothDevice? = null
    private var reconnectJob: Thread? = null

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var client: Socket? = null

    private val accLock = Any()
    private var accX = 0
    private var accY = 0
    private var pendingClick = false

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "app status registered=$registered")
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                host = device
                lastDevice = device
                getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAST_ADDR, device.address).apply()
                statusText = "已连接电脑"
                addEvent("已连接")
                Log.i(TAG, "host connected")
            } else if (host == device) {
                host = null
                lastDevice = device
                statusText = "等待电脑连接（自动重连中）"
                addEvent("断开，开始重连")
                Log.i(TAG, "host disconnected")
                startReconnect()
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            runCatching { hid?.replyReport(device, type, id, ByteArray(3)) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()

        if (!hasBtPermission()) {
            Log.e(TAG, "missing BLUETOOTH_CONNECT, abort")
            stopSelf()
            return
        }
        if (!startForegroundSafely()) {
            stopSelf()
            return
        }

        acquireLocks()
        statusText = "服务已启动，等待电脑连接"
        addEvent("服务启动")
        registerHid()
        running = true
        startWatchdog()
        thread(name = "tcp") { tcpLoop() }
        thread(name = "emit") { emitLoop() }
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun acquireLocks() {
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vpc:wifi")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vpc:cpu")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        runCatching { client?.close() }
        runCatching { hid?.unregisterApp() }
        releaseLocks()
        server = null
        client = null
        host = null
        hid = null
        super.onDestroy()
    }

    /** 断线后每 5 秒尝试重连，最多 60 次（约 5 分钟） */
    @SuppressLint("MissingPermission")
    private fun startReconnect() {
        if (reconnectJob?.isAlive == true) return
        reconnectJob = thread(name = "reconnect") {
            var tries = 0
            while (running && host == null && tries < 60) {
                tries++
                val dev = lastDevice
                if (dev != null) {
                    runCatching { hid?.connect(dev) }
                    statusText = "重连中（第 $tries 次）"
                    Log.i(TAG, "reconnect #$tries")
                } else {
                    statusText = "等待电脑连接"
                }
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (host == null) {
                statusText = "重连失败，请重新配对"
                addEvent("重连放弃")
            }
        }
    }

    /**
     * 看门狗：host 为空时每 3 秒尝试一次。
     * 场景：蓝牙先连上、App 后启动，安卓不会补发 onConnectionStateChanged。
     */
    /** 读上次连过的 PC 地址，主动发起 HID 连接（参考 GhostHID：不等系统回调） */
    @SuppressLint("MissingPermission")
    private fun connectRemembered(): Boolean {
        val dev = hid ?: return false
        val addr = getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_LAST_ADDR, null) ?: return false
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        val target = adapter.bondedDevices?.firstOrNull { it.address.equals(addr, ignoreCase = true) } ?: run {
            addEvent("上次的电脑已不在配对列表")
            return false
        }
        val ok = runCatching { dev.connect(target) }.getOrDefault(false)
        addEvent("主动连接 ${target.name}: $ok")
        Log.i(TAG, "connectRemembered ${target.address} -> $ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun startWatchdog() {
        thread(name = "watchdog") {
            runCatching {
                val a = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                val bonded = a?.bondedDevices?.map { it.name ?: it.address } ?: emptyList()
                addEvent("已配对 ${bonded.size} 台: " + bonded.joinToString("/"))
            }
            while (running) {
                if (host == null && hid != null) {
                    runCatching {
                        val list = hid?.connectedDevices
                        if (!list.isNullOrEmpty()) {
                            host = list[0]
                            lastDevice = list[0]
                            statusText = "已连接电脑"
                            addEvent("看门狗拿到设备")
                        } else if (!connectRemembered()) {
                            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                            adapter?.bondedDevices?.forEach { d ->
                                runCatching { hid?.connect(d) }
                            }
                            addEvent("看门狗尝试连接")
                        }
                    }
                }
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    // ---------------------------------------------------------------- 权限 / 前台
    private fun hasBtPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun startForegroundSafely(): Boolean = try {
        startForeground(1, buildNotification())
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed: ${e.message}")
        false
    }

    // ---------------------------------------------------------------- HID
    @SuppressLint("MissingPermission")
    private fun registerHid() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = mgr.adapter ?: run {
            Log.e(TAG, "no bluetooth adapter")
            return
        }
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val dev = proxy as BluetoothHidDevice
                hid = dev
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Bluetooth Mouse", "vpc", "vpc",
                    BluetoothHidDevice.SUBCLASS1_MOUSE, REPORT_DESC
                )
                val ok = runCatching {
                    dev.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
                }.getOrElse { e ->
                    Log.e(TAG, "registerApp threw: ${e.message}")
                    false
                }
                Log.i(TAG, "registerApp=$ok")
                addEvent("registerApp=$ok")
                connectRemembered()

                // 关键：注册时若主机已连接，不会补发 onConnectionStateChanged，这里主动取一次
                runCatching {
                    val list = dev.connectedDevices
                    if (list.isNotEmpty()) {
                        host = list[0]
                        Log.i(TAG, "host from connectedDevices: ${host?.name}")
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    // ---------------------------------------------------------------- TCP
    private fun tcpLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            server = ss
            Log.i(TAG, "listening :$PORT")

            while (running) {
                val s = ss.accept()
                val reader = authorize(s)
                if (reader == null) {
                    runCatching { s.close() }
                    continue
                }
                runCatching { client?.close() }
                client = s
                thread(name = "client") { readLoop(s, reader) }
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "tcp error: ${e.message}")
        } finally {
            runCatching { server?.close() }
        }
    }

    /** 握手：2 秒内收到 "VPC1" 才放行，避免局域网内其它设备误连 */
    private fun authorize(s: Socket): BufferedReader? = try {
        s.soTimeout = 2000
        // 只创建一次 reader：BufferedReader 会预读，握手后必须复用它
        val r = BufferedReader(InputStreamReader(s.getInputStream()))
        val line = r.readLine()
        s.soTimeout = 0
        if (line?.trim() != HANDSHAKE) {
            Log.w(TAG, "handshake rejected: $line")
            null
        } else r
    } catch (e: SocketTimeoutException) {
        Log.w(TAG, "handshake timeout")
        null
    } catch (e: Exception) {
        null
    }

    private fun readLoop(s: Socket, r: BufferedReader) {
        try {
            while (running) {
                val line = r.readLine() ?: break
                parse(line)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
            if (client == s) client = null
        }
    }

    private fun parse(line: String) {
        val p = line.trim().split(' ')
        if (p.size < 2) return
        val dx = p[0].toIntOrNull() ?: return
        val dy = p[1].toIntOrNull() ?: return
        val b = if (p.size >= 3) (p[2].toIntOrNull() ?: 0) else 0

        recvLines++
        synchronized(accLock) {
            accX += dx
            accY += dy
            if (b != 0) pendingClick = true
        }
    }

    // ---------------------------------------------------------------- 发送
    private fun emitLoop() {
        while (running) {
            var dx = 0
            var dy = 0
            var click = false
            synchronized(accLock) {
                dx = accX; dy = accY; click = pendingClick
                accX = 0; accY = 0; pendingClick = false
            }
            if (dx != 0 || dy != 0) sendMove(dx, dy)
            if (click) sendClick()
            try {
                Thread.sleep(EMIT_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMove(dx: Int, dy: Int) {
        val dev = hid ?: return
        val target = host ?: run { sendFail++; return }

        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127)
            val sy = y.coerceIn(-127, 127)
            x -= sx
            y -= sy
            val ok = runCatching {
                dev.sendReport(target, 2, byteArrayOf(0, sx.toByte(), sy.toByte()))
            }.getOrDefault(false)
            if (ok) sendOk++ else sendFail++
        }
    }

    /** 点击 = 按下 + 12ms 后释放，避免「只按不放」 */
    @SuppressLint("MissingPermission")
    private fun sendClick() {
        val dev = hid ?: return
        val target = host ?: return
        runCatching { dev.sendReport(target, 2, byteArrayOf(1, 0, 0)) }
        Thread.sleep(12)
        runCatching { dev.sendReport(target, 2, byteArrayOf(0, 0, 0)) }
    }

    // ---------------------------------------------------------------- 通知
    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPC Mouse", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("VPC Mouse 运行中")
            .setContentText("TCP :$PORT 等待电脑连接")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
    }
}
