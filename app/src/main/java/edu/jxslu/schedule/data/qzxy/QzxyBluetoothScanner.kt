package edu.jxslu.schedule.data.qzxy

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 扫到的趣智校园设备。
 *
 * [deviceKey] 是广播名里那串编号（形如 `KLCXKJ-Water,G,490067305242` 里的
 * `490067305242`）；名字里没有就退回 MAC 去冒号大写。它是设备侧的业务标识，
 * 和 MAC 不一定相等，所以两个都留着，下单时按需取用。
 */
data class QzxyScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val deviceKey: String,
) {
    /** 去冒号大写的地址，用作设备信息缓存的键（同一台设备在不同回调里大小写未必一致）。 */
    val addressKey: String get() = address.replace(":", "").uppercase(Locale.US)
}

/**
 * 趣智校园设备扫描（DESIGN §4.30）。
 *
 * 只扫 BLE 广播，不做连接。广播名以 `KLCXKJ` 开头即认定为本家设备，
 * 这条过滤规则来自两个参考实现与公开的设备样本，其它厂商的水控器扫不出来也不误报。
 *
 * 与参考实现的差别：这里保留完整广播名与 [QzxyScannedDevice.deviceKey] 两个值，
 * 上一版参考实现只取其一，抓包对不上时没法回看原始名字。
 */
class QzxyBluetoothScanner(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val handler = Handler(Looper.getMainLooper())

    private var scanning = false
    private var onDevice: ((QzxyScannedDevice) -> Unit)? = null
    private var onFinished: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private val seen = mutableSetOf<String>()

    private val stopRunnable = Runnable {
        val finished = onFinished
        stopScan()
        finished?.invoke(true)
    }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            publish(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { publish(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
            onError?.invoke("蓝牙扫描失败（错误码 $errorCode）")
        }
    }

    @SuppressLint("MissingPermission")
    private fun publish(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: result.device.name ?: return
        if (!name.startsWith(QzxyApiConfig.BLE_NAME_PREFIX, ignoreCase = true)) return
        val address = result.device.address ?: return
        if (!seen.add(address.uppercase(Locale.US))) return
        onDevice?.invoke(
            QzxyScannedDevice(
                name = name,
                address = address,
                rssi = result.rssi,
                deviceKey = extractDeviceKey(name, address),
            ),
        )
    }

    /**
     * 开扫。返回 false 表示蓝牙不可用或没权限，调用方据此提示。
     * [onFinished] 的参数是「是否正常扫完」：正常扫完但一台没有也算 true。
     */
    @SuppressLint("MissingPermission")
    fun startScan(
        durationMillis: Long = DEFAULT_DURATION_MILLIS,
        onDevice: (QzxyScannedDevice) -> Unit,
        onFinished: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        stopScan()
        this.onDevice = onDevice
        this.onFinished = onFinished
        this.onError = onError

        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            onError("本机没有蓝牙适配器")
            clearCallbacks()
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            onError("请先打开系统蓝牙")
            clearCallbacks()
            return false
        }
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            onError("当前设备不支持低功耗蓝牙扫描")
            clearCallbacks()
            return false
        }
        return try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
            scanning = true
            handler.postDelayed(stopRunnable, durationMillis)
            true
        } catch (e: SecurityException) {
            onError("附近设备权限不足：${e.message ?: "请授权后重试"}")
            clearCallbacks()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopRunnable)
        if (scanning) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
        scanning = false
        seen.clear()
        clearCallbacks()
    }

    private fun clearCallbacks() {
        onDevice = null
        onFinished = null
        onError = null
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 10_000L

        /** 扫描所需权限：API 31+ 用新蓝牙权限，之前版本靠定位（系统硬性规定）。 */
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 从广播名取设备编号：取最后一个逗号之后那段，12 位十六进制才算；
         * 否则退回 MAC 去冒号大写。
         */
        fun extractDeviceKey(name: String, address: String): String {
            val tail = name.substringAfterLast(',', "").trim()
            if (tail.length == 12 && tail.all { Character.digit(it, 16) >= 0 }) {
                return tail.uppercase(Locale.US)
            }
            return address.replace(":", "").uppercase(Locale.US)
        }
    }
}
