package edu.jxslu.schedule.data.qzxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import edu.jxslu.schedule.domain.QzxyFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 设备 GATT 表里的一项。页面上原样列出，用来回答「这台设备到底提供什么服务」——
 * 官方 SDK 只给了 `KServiceUUID` / `KWriteCharacteristicUUID` 这几个常量名，
 * 具体 UUID 在闭源二进制里，只能从设备上读回来。
 */
data class QzxyGattCharacteristicInfo(
    val serviceUuid: String,
    val characteristicUuid: String,
    val properties: Int,
    /** 已知用途的标注（透传写入口 / 回包口 / 流控），认不出来就是 null。 */
    val role: String? = null,
) {
    val propertyText: String
        get() = buildList {
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("读")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("写")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("无应答写")
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("通知")
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("指示")
        }.joinToString("/").ifBlank { "无" }

    val writable: Boolean
        get() = properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0

    val notifiable: Boolean
        get() = properties and (
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0
}

/**
 * 低功耗蓝牙（GATT）通道（DESIGN §4.30）。**实验性。**
 *
 * 为什么从经典蓝牙换到这里：实测本机去连 `KLCXKJ-Water` 会触发系统配对并报
 * 「PIN 码或通行密钥不正确」，随后 socket 读取失败（`read ret:-1`）。设备的广播是
 * 低功耗蓝牙广播，却在经典蓝牙侧配对失败，说明它不是串口透传设备。
 *
 * 与经典蓝牙的差别不只是换套 API：特征值 UUID 未知，所以先做一次**服务发现**，
 * 把设备公开的服务表读出来；写入口按 [PREFERRED_WRITE_UUIDS] 的优先级挑，
 * 逐个试到有回应为止。
 *
 * 2026-09-27 实测本校设备的服务表，答案是 Microchip/ISSC 蓝牙透传服务：
 *
 * | 特征值 | 属性 | 用途 |
 * |---|---|---|
 * | `49535343-8841-43f4-a8d4-ecbe34729bb3` | 写 / 无应答写 | 往设备发数据 |
 * | `49535343-1e4d-4bd9-ba61-23c647249616` | 通知 | 收设备回包 |
 * | `49535343-aca3-481c-91ec-d85e28a60318` | 写 / 通知 | 流控 |
 *
 * 这三条正好对上官方 SDK 暴露的 `KWriteCharacteristicUUID` / `KReadCharacteristicUUID` /
 * `KFlowControlCharacteristicUUID`。设备另有一个 `0000ff00` 自定义服务（`ff02` 可写），
 * 上一版取「第一个可写特征值」命中的就是它，写下去毫无反应。
 * 流控特征值目前不写，先看主通道通不通。
 */
class QzxyGattLink(private val context: Context) {

    @Volatile
    private var gatt: BluetoothGatt? = null

    /** 可写特征值，按 [PREFERRED_WRITE_UUIDS] 优先级排好；一次请求会依次试到有回应为止。 */
    @Volatile
    private var writeCharacteristics: List<BluetoothGattCharacteristic> = emptyList()

    /** 协商到的 ATT MTU；没协商上就用默认 23（有效载荷 20 字节）。 */
    @Volatile
    private var mtu = DEFAULT_MTU

    /** 设备主动上报的数据块，按到达顺序排队。 */
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * 流控特征值的通知。
     *
     * 这条通道必须与 [incoming] 分开：流控字节不是 `#<hex>\n` 回包，
     * 混进回包队列会卡在 [readUntilLineFeed] 的缓冲里，把下一条真正的回包顶掉。
     * 目前只做隔离与丢弃，不猜字节语义；真正的写入节流靠 GATT 写回执。
     */
    private val flowControl = Channel<ByteArray>(Channel.CONFLATED)
    private var buffered = ByteArray(0)

    /** 当前等待回执的 GATT 写操作；同一时刻只允许一个操作在飞。 */
    @Volatile
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    /** 当前等待回执的 CCCD 描述符写操作。 */
    @Volatile
    private var pendingDescriptorWrite: CompletableDeferred<Boolean>? = null

    val isConnected: Boolean
        get() = linkAlive && gatt != null

    /**
     * 是否已经连着**指定**设备。
     *
     * 调用方判断能否复用链路时必须用这个而不是 [isConnected]：连着另一台设备时
     * [isConnected] 同样是 true，复用会把指令发到别的设备上。
     */
    fun isConnectedTo(address: String): Boolean =
        isConnected && connectedAddress.equals(address, ignoreCase = true)

    /** 协商到的 ATT MTU。透传模块对分包敏感，排查时要知道实际值。 */
    val negotiatedMtu: Int get() = mtu

    /**
     * 当前连接读回的服务表。
     *
     * 链路现在是进程单例（见 `Graph.qzxyLink`），一份连接可能被两个窗口先后复用：
     * 面板里开阀连上、再进诊断页时，这份表就是上一次连接的产物，界面该把它显示出来。
     */
    val serviceTable: List<QzxyGattCharacteristicInfo> get() = cachedServiceTable

    /** 当前连接的设备地址与上次发现的服务表，用来判断能否复用连接。 */
    @Volatile
    private var connectedAddress: String? = null
    @Volatile
    private var cachedServiceTable: List<QzxyGattCharacteristicInfo> = emptyList()

    /**
     * 链路是否活着。自己维护而不是问 `BluetoothGatt`：官方 App 抢占或设备侧超时断开时，
     * 连接对象仍在，只有回调会告诉我们它已经断了。
     */
    @Volatile
    private var linkAlive = false

    /**
     * 连接设备并读回服务表。返回的表按「服务 → 特征值」平铺，页面上直接列。
     * 连接、服务发现任一失败都返回 failure，异常文本会显示给用户。
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(
        address: String,
        timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    ): Result<List<QzxyGattCharacteristicInfo>> = withContext(Dispatchers.IO) {
        // 已经连着同一台设备就直接复用：GATT 建链要一两秒，每次操作都重连纯属浪费，
        // 用户感受到的「开阀好慢」有一半出在这里
        // isConnected 必须一起判：连接对象还在、底层链路已经断了（被官方 App 抢占、
        // 或设备侧超时断开）时，复用它会一路超时——现象就是「查询设备没有回应」
        if (linkAlive &&
            connectedAddress.equals(address, ignoreCase = true) &&
            cachedServiceTable.isNotEmpty()
        ) {
            return@withContext Result.success(cachedServiceTable)
        }
        close()
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.failure(IllegalStateException("本机没有蓝牙适配器"))
        if (!adapter.isEnabled) {
            return@withContext Result.failure(IllegalStateException("请先打开系统蓝牙"))
        }

        val discovered = CompletableDeferred<List<QzxyGattCharacteristicInfo>>()
        val device = adapter.getRemoteDevice(address)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        linkAlive = true
                        // 先把 MTU 抬上去。查询帧加 `#` 与换行正好 20 字节，卡在默认上限的
                        // 边界上；之后的 downData 只会更长。协商失败也不要紧，写入按实际 MTU 分包。
                        gatt.requestMtu(REQUESTED_MTU)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> if (!discovered.isCompleted) {
                        markDisconnected()
                        discovered.completeExceptionally(
                            IllegalStateException("设备已断开（状态码 $status）"),
                        )
                    } else {
                        // 服务发现完成后设备仍可能被官方 App 抢占、走远或超时断开。
                        // 这里必须把链路标记清掉：connect() 复用判断只看 linkAlive +
                        // 缓存的服务表，留着旧值会把下一条命令写到已经断开的 gatt 上，
                        // 用户看到的就是「第一条命令超时，重连后才正常」。
                        markDisconnected()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    discovered.completeExceptionally(
                        IllegalStateException("服务发现失败（状态码 $status）"),
                    )
                    return
                }
                val table = gatt.services.flatMap { service ->
                    service.characteristics.map { characteristic ->
                        QzxyGattCharacteristicInfo(
                            serviceUuid = service.uuid.toString(),
                            characteristicUuid = characteristic.uuid.toString(),
                            properties = characteristic.properties,
                            role = roleOf(characteristic.uuid),
                        )
                    }
                }
                // 写入口候选。已知的透传写入口在，就只用它：留多个候选的代价是
                // 「没回应」时把同一条命令再往另一个口发一遍（重复下发），失败路径也翻倍。
                // 服务表里没有它（别的厂商设备）才退回「逐个试」。
                val writable = gatt.services.asSequence()
                    .flatMap { it.characteristics.asSequence() }
                    .filter { characteristic ->
                        characteristic.properties and (
                            BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                            ) != 0
                    }
                    .sortedBy { characteristic -> preferredWriteRank(characteristic.uuid) }
                    .toList()
                writeCharacteristics = writable.filter { it.uuid == WRITE_UUID }
                    .ifEmpty { writable }
                discovered.complete(table)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    this@QzxyGattLink.mtu = mtu
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                pendingWrite = null
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                pendingDescriptorWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
                pendingDescriptorWrite = null
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == FLOW_CONTROL_UUID) {
                    flowControl.trySend(value)
                    return
                }
                incoming.trySend(value)
            }

            @Deprecated("API 33 以下走这个重载")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                if (characteristic.uuid == FLOW_CONTROL_UUID) {
                    flowControl.trySend(value)
                    return
                }
                incoming.trySend(value)
            }
        }

        return@withContext try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            // 用 withTimeoutOrNull 而不是 withTimeout：后者的超时异常也是
            // CancellationException，会被下面的取消分支原样抛给调用方，
            // 把「连接超时」误报成协程取消。
            val table = withTimeoutOrNull(timeoutMillis) { discovered.await() }
                ?: throw IllegalStateException("连接设备超时（${timeoutMillis}ms）")
            // CCCD 写是 GATT 操作，必须等它落地再让调用方发第一条命令；
            // 开通知失败不阻断建链（部分设备没有 CCCD，但主通道仍可用）。
            try {
                enableNotifications()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // 开通知失败不阻断建链，主通道仍可能可用
            }
            if (!linkAlive) {
                close()
                return@withContext Result.failure(
                    IllegalStateException("设备在启用通知时断开"),
                )
            }
            connectedAddress = address
            cachedServiceTable = table
            Result.success(table)
        } catch (error: CancellationException) {
            close()
            throw error
        } catch (error: Exception) {
            close()
            Result.failure(error)
        }
    }

    /**
     * 打开回包口的通知。
     *
     * 只开已知的那两条（透传回包口、流控），不再「把所有可通知的特征值都开一遍」：
     * Android 的 GATT 同一时刻只允许一个操作在飞，连着调 `writeDescriptor` 时
     * 后面的会被静默丢掉，开哪条就成了看遍历顺序的运气。官方 SDK 也只写
     * `KReadCharacteristicUUID` 那一条。
     *
     * 服务表里认不出这两条时退回原来的做法：没见过的设备也不能收不到回包。
     */
    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications() {
        val current = gatt ?: return
        val notifiable = current.services
            .flatMap { service -> service.characteristics }
            .filter { characteristic ->
                characteristic.properties and (
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0
            }
        if (notifiable.isEmpty()) return

        // 回包口排最前：它必须先拿到 CCCD 写机会，流控口开不开都不影响收包
        val preferred = notifiable
            .filter { it.uuid == READ_UUID || it.uuid == FLOW_CONTROL_UUID }
            .sortedBy { if (it.uuid == READ_UUID) 0 else 1 }
        (preferred.ifEmpty { notifiable }).forEach { characteristic ->
            // 回包口必须等 CCCD 写完；流控口只开通知，不阻塞建链。
            enableNotification(
                gatt = current,
                characteristic = characteristic,
                await = characteristic.uuid == READ_UUID,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        await: Boolean,
    ) {
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) return
            val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
            val deferred = if (await) CompletableDeferred<Boolean>() else null
            pendingDescriptorWrite = deferred
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            if (!queued) {
                pendingDescriptorWrite = null
                return
            }
            if (deferred != null) {
                withTimeoutOrNull(DESCRIPTOR_WRITE_TIMEOUT_MILLIS) { deferred.await() }
                if (pendingDescriptorWrite === deferred) pendingDescriptorWrite = null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            pendingDescriptorWrite = null
        }
    }

    /**
     * 发一帧并等一帧回包。回包按 `\n` 收完整行；超时返回 null，由调用方呈现「设备无响应」。
     */
    suspend fun request(frame: ByteArray, timeoutMillis: Int = RESPONSE_TIMEOUT_MILLIS): Result<ByteArray?> =
        withContext(Dispatchers.IO) {
            val current = gatt ?: return@withContext Result.failure(IllegalStateException("蓝牙未连接"))
            if (writeCharacteristics.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("设备没有可写特征值"))
            }
            try {
                val payload = QzxyFrame.wrap(frame)
                for (characteristic in writeCharacteristics) {
                    drainIncoming()
                    buffered = ByteArray(0)
                    writeChunked(current, characteristic, payload)
                    val response = withTimeoutOrNull(timeoutMillis.toLong()) { readUntilLineFeed() }
                    if (response != null) return@withContext Result.success(response)
                }
                // 每个候选都写过了，设备一声不吭。多半是链路已经废了（被别的 App 抢占、
                // 或设备侧断开），把连接清掉，下一次操作重新建链，别继续复用死连接
                close()
                Result.success(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }

    /** 丢弃上一轮遗留的通知数据，免得把旧回包当成这次的应答。 */
    private fun drainIncoming() {
        while (incoming.tryReceive().isSuccess) {
            // 空循环即是消费
        }
    }

    /** 按协商到的 MTU 分包写入。默认 MTU 23 时每包 20 字节，长帧必须切开，否则写不进去。 */
    @SuppressLint("MissingPermission")
    private suspend fun writeChunked(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ) {
        val chunkSize = (mtu - 3).coerceAtLeast(MIN_CHUNK_BYTES)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            write(gatt, characteristic, payload.copyOfRange(offset, end))
            offset = end
            if (offset < payload.size) delay(WRITE_GAP_MILLIS)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ) {
        // Android 的 GATT 同一时刻只允许一个操作在飞，排队失败时 writeCharacteristic
        // 返回 false 且不报错。刚连上时最常见：CCCD 写还没落地，第一条指令就被丢了，
        // 现象是「连接后的第一条命令超时」。所以这里重试几次，别把 false 当成功。
        repeat(WRITE_ATTEMPTS) { attempt ->
            val deferred = CompletableDeferred<Boolean>()
            pendingWrite = deferred
            val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33 起这个重载返回 BluetoothStatusCodes 而不是 Boolean
                gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (queued) {
                // 队列里排上不等于写成功：等 onCharacteristicWrite 回执再发下一包。
                // 这样长 downData 不会在上一个 GATT 操作还没落地时被下一包挤掉。
                val completed = withTimeoutOrNull(WRITE_COMPLETION_TIMEOUT_MILLIS) {
                    deferred.await()
                }
                if (pendingWrite === deferred) pendingWrite = null
                when (completed) {
                    true -> return
                    false -> Unit // 设备明确回了失败，重试是安全的
                    // 回执没来：写入可能已经在设备侧生效，重发会造成重复的
                    // 0x21 / 0x86。这里直接报错，把「是否重试」交给上层流程。
                    null -> throw IllegalStateException("GATT 写入回执超时")
                }
            } else {
                pendingWrite = null
            }
            if (attempt < WRITE_ATTEMPTS - 1) delay(WRITE_RETRY_DELAY_MILLIS)
        }
        throw IllegalStateException("GATT 写入没有完成")
    }

    /** 累积通知数据到出现换行为止；设备可能分好几包发。 */
    private suspend fun readUntilLineFeed(): ByteArray? {
        while (true) {
            val chunk = incoming.receive()
            buffered += chunk
            val end = buffered.indexOfFirst { it == LINE_FEED }
            if (end >= 0) {
                val line = buffered.copyOfRange(0, end + 1)
                buffered = buffered.copyOfRange(end + 1, buffered.size)
                return line
            }
            if (buffered.size > MAX_RESPONSE_BYTES) {
                buffered = ByteArray(0)
                return null
            }
        }
    }

    /** 断开并释放。页面退出、重连前、异常分支都调它，不抛异常。 */
    @SuppressLint("MissingPermission")
    fun close() {
        pendingWrite?.complete(false)
        pendingWrite = null
        pendingDescriptorWrite?.complete(false)
        pendingDescriptorWrite = null
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        markDisconnected()
        mtu = DEFAULT_MTU
    }

    /**
     * 链路断开后的内存清理。
     *
     * 只清复用判断依赖的现场，不碰 [gatt] 本身：真正的释放仍由 [close] 做，
     * 而断线回调里再调 disconnect/close 容易触发重入。
     */
    private fun markDisconnected() {
        linkAlive = false
        writeCharacteristics = emptyList()
        connectedAddress = null
        cachedServiceTable = emptyList()
        buffered = ByteArray(0)
        while (flowControl.tryReceive().isSuccess) {
            // 清掉断链前的流控残留
        }
        pendingWrite?.complete(false)
        pendingWrite = null
        pendingDescriptorWrite?.complete(false)
        pendingDescriptorWrite = null
    }

    /** 已知优先级的排前面，其余按服务发现给出的原顺序跟在后面。 */
    private fun preferredWriteRank(uuid: UUID): Int {
        val index = PREFERRED_WRITE_UUIDS.indexOf(uuid)
        return if (index >= 0) index else PREFERRED_WRITE_UUIDS.size
    }

    /** 认出透传服务的三条特征值，页面上标出各自用途，省得对着 UUID 猜。 */
    private fun roleOf(uuid: UUID): String? = when (uuid) {
        WRITE_UUID -> "透传写入口：数据往这里发"
        READ_UUID -> "透传回包口：回复从这里来"
        FLOW_CONTROL_UUID -> "流控"
        else -> null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val RESPONSE_TIMEOUT_MILLIS = 5_000
        const val MAX_RESPONSE_BYTES = 512
        const val LINE_FEED = 0x0A.toByte()

        /** 请求的 ATT MTU；协商不上就退回 [DEFAULT_MTU]。 */
        const val REQUESTED_MTU = 247
        const val DEFAULT_MTU = 23
        const val MIN_CHUNK_BYTES = 20
        const val WRITE_GAP_MILLIS = 30L

        /** 写入排队失败时的重试次数与间隔：等上一个 GATT 操作落地。 */
        const val WRITE_ATTEMPTS = 3
        const val WRITE_RETRY_DELAY_MILLIS = 40L

        /** 单次 GATT 写回执的最长等待；超时按这次写失败处理，再试下一轮。 */
        const val WRITE_COMPLETION_TIMEOUT_MILLIS = 1_000L

        /** CCCD 描述符写回执的最长等待。 */
        const val DESCRIPTOR_WRITE_TIMEOUT_MILLIS = 2_000L

        /** Microchip/ISSC 蓝牙透传服务的三条特征值，用途见 [roleOf]。 */
        val WRITE_UUID: UUID = UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3")
        val READ_UUID: UUID = UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616")
        val FLOW_CONTROL_UUID: UUID = UUID.fromString("49535343-aca3-481c-91ec-d85e28a60318")

        /**
         * 写入特征值的优先顺序（2026-09-27 实测本校设备服务表得到）。
         *
         * 第一项是透传服务的写入口；第二项是设备自定义服务里的可写特征值。
         * 原来的「第一个可写特征值」启发式命中的正是第二项，写下去设备毫无反应。
         * 现在第二项只在服务表里找不到第一项时才用（见 `onServicesDiscovered`），
         * 免得「没回应」时把同一条命令往另一个口重发一遍。
         *
         * **声明顺序有讲究**：伴生对象按声明顺序初始化，这里引用的 [WRITE_UUID]
         * 必须排在前面，否则读到的是还没赋值的 null。别为了排版把它挪上去。
         */
        val PREFERRED_WRITE_UUIDS: List<UUID> = listOf(
            WRITE_UUID,
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb"),
        )

        /** 客户端特征配置描述符，固定 UUID。 */
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
