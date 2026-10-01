package edu.jxslu.schedule.ui.ebike

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.SourceData
import edu.jxslu.schedule.JuwRoot
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Flashlight
import me.rerere.hugeicons.stroke.Image01

/** 底部两枚按钮的尺寸：同高（一圆一胶囊），横排不会一高一低。 */
private val ScanButtonSize = 48.dp

/** 按钮离屏幕边缘的间距（下边再叠系统导航栏内边距）。 */
private val ScanButtonMargin = 20.dp

/** 取景页按钮底色：半透明黑 + 白图标，压在亮 / 暗的取景画面上都看得清。 */
private val ScanButtonScrim = Color.Black.copy(alpha = 0.45f)

/** 相册图解码时的长边上限（见 [EbikeScanActivity.readSampledBitmap]）。 */
private const val GALLERY_MAX_EDGE = 2000

/**
 * 内置相机扫一扫（账号方式的「扫车身码」，DESIGN §3.9）。
 *
 * **为什么不复用扫码库自带的 `CaptureActivity`**（2026-09-30）：它只有取景框 + 一行提示，
 * 既没有开关手电筒的按钮，也没有「从相册选图」的入口——而「暗光下车看不清码」与
 * 「码早就拍在相册里」正是扫码最常见的两种来路。这里走库文档的「自定义窗口」形态：
 * 预览仍是 [DecoratedBarcodeView]，相机开关机 / 权限 / 取景框 / 解码节流仍归
 * [CaptureManager]，只把界面换成 Compose（[JuwRoot] + 两枚按钮），与全 App 同一套观感。
 *
 * **返回结果与库自带窗口逐字段同形**（[CaptureManager.resultIntent]），`ScanContract`
 * 那侧不改：相册认出来的车号也从同一条通道回给 `RideScreen`。
 *
 * 2026-10-01 起 U净 洗衣房（DESIGN §4.37）复用本窗口：调起时传
 * [EXTRA_RESULT_MODE] = [MODE_RAW]，相册图不再解析车号、二维码原文原样回传
 * （机身码的解析归 U净 服务端）。不传则维持骑行模式，行为不变。
 *
 * 手电筒状态由库的 [DecoratedBarcodeView.TorchListener] 驱动——**别在页面里另存一份
 * 开关值**：暂停再回来（去相册选图就是）相机重开时 `CameraPreview` 会按它自己记住的状态
 * 把补光灯重新点亮，两处各记一份迟早对不上。
 */
class EbikeScanActivity : ComponentActivity() {

    companion object {
        /**
         * 回传模式（Intent extra）。默认骑行模式：相册图必须解析出车号才回传；
         * [MODE_RAW] 把二维码原文原样回传（U净 扫码复用，见类注释）。
         */
        const val EXTRA_RESULT_MODE = "scan_result_mode"
        const val MODE_BIKE = "bike"
        const val MODE_RAW = "raw"
    }

    /** true = 原文模式（相册图不解析车号，直接回传二维码文本）。 */
    private val rawMode: Boolean
        get() = intent.getStringExtra(EXTRA_RESULT_MODE) == MODE_RAW

    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var capture: CaptureManager

    /** 手电筒当前状态（唯一来源是库回调；界面只读它）。 */
    private val torchOn = mutableStateOf(false)

    private val snackbar = SnackbarHostState()

    /**
     * 相册入口：系统 Photo Picker（**零权限**，低版本由契约落到 `GET_CONTENT`），
     * 与笔记插图（`rememberImageInserter`）同一口径。
     */
    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scanFromGallery(uri)
        }

    /** 没有补光灯的设备不摆手电筒按钮（点了没反应的东西摆着是骗人）。 */
    private val hasFlash: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = DecoratedBarcodeView(this)
        scanner.setTorchListener(object : DecoratedBarcodeView.TorchListener {
            override fun onTorchOn() {
                torchOn.value = true
            }

            override fun onTorchOff() {
                torchOn.value = false
            }
        })
        // 提示文字由本页自己画（下面那枚 Text）：库自带的 status 视图贴在窗口最底边，
        // 在 edge-to-edge 的窗口里正好被导航条盖住（2026-09-30 模拟器截图确认）
        val prompt = intent.getStringExtra(Intents.Scan.PROMPT_MESSAGE)
        scanner.statusView.visibility = View.GONE
        capture = CaptureManager(this, scanner)
        capture.initializeFromIntent(intent, savedInstanceState)
        setContent {
            JuwRoot {
                ScanScreen(
                    scanner = scanner,
                    prompt = prompt,
                    hasFlash = hasFlash,
                    torchOn = torchOn.value,
                    onToggleTorch = {
                        if (torchOn.value) scanner.setTorchOff() else scanner.setTorchOn()
                    },
                    onPickImage = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    snackbar = snackbar,
                )
            }
        }
        capture.decode()
    }

    // 相机与权限的生命周期全部转交给 CaptureManager（库自带窗口的同一套接线与顺序）

    override fun onResume() {
        super.onResume()
        capture.onResume()
    }

    override fun onPause() {
        super.onPause()
        capture.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        capture.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        capture.onSaveInstanceState(outState)
    }

    // 库仍走老的 requestPermissions（请求码 250）拿相机权限，授没授予只能从这个回调进来
    // ——不转发的话用户点了「允许」相机也不会开，取景页停在黑屏
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        capture.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * 相册图 → 结果文本，认出来就走与相机识别同一条返回通道。
     *
     * 取像素与二值化放 IO 线程（一张 12MP 图取像素就是几十毫秒的量级）；认不出来留在
     * 取景页给一句提示——相机还开着，换一张、直接对准机器都行。骑行模式下**先过一遍
     * [EbikeQr.parseScannedCarNum]**：从相册挑图很容易挑到无关截图，那种情况踢回骑行页
     * 再报「未识别到有效车号」，等于把用户刚打开的面板关掉，不如就地重选。
     * 原文模式不解析（U净 机身码归服务端识别），解出文本即回传。
     */
    private fun scanFromGallery(uri: Uri) {
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) { rawTextFromGallery(uri) }
            val result = if (rawMode) raw else raw?.let(EbikeQr::parseScannedCarNum)
            if (result == null) {
                showNotice(
                    lifecycleScope,
                    snackbar,
                    if (rawMode) {
                        "这张图里没认出二维码，换一张试试"
                    } else {
                        "这张图里没认出车身二维码，换一张试试"
                    },
                    NoticeTone.Warning,
                )
            } else {
                finishWithResult(result)
            }
        }
    }

    /** 相册图 → 二维码原文；认不出（不是码 / 拍糊 / 读不出来）返回 null。IO 线程调用。 */
    private fun rawTextFromGallery(uri: Uri): String? {
        val bitmap = readSampledBitmap(uri) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return EbikeQr.decodeFromPixels(pixels, width, height)
    }

    /**
     * 相册图 → Bitmap，长边先降到 [GALLERY_MAX_EDGE]。**别整张解码**：12MP 照片的 ARGB
     * 数组就是 48MB，而二维码只要模块够清楚就行。`inSampleSize` 只认 2 的幂，这里选到
     * 刚好不超上限的那一档；尺寸探针拿不到尺寸、或流打不开（provider 出错）都返回 null。
     */
    private fun readSampledBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds 下 decodeStream 恒返回 null：这里只看探针有没有填上尺寸
        runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    /** 让长边落在 [GALLERY_MAX_EDGE] 之内的最小 2 的幂（[BitmapFactory.Options.inSampleSize] 的粒度）。 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > GALLERY_MAX_EDGE) sample *= 2
        return sample
    }

    /**
     * 把结果文本当作一次「扫码结果」返回（`CaptureManager` 的回传口径不变）：
     * 骑行模式是车号，原文模式是二维码原文（U净 扫码复用，见类注释）。
     *
     * `SourceData` 只为满足 [BarcodeResult] 的构造器：`resultIntent` 只读 `Result` 的
     * 文本 / 格式 / 字节，像素那一段不参与回传（也不申请 barcodeImagePath）。
     */
    private fun finishWithResult(text: String) {
        val scanned = BarcodeResult(
            Result(text, null, null, BarcodeFormat.QR_CODE),
            SourceData(ByteArray(1), 1, 1, 0, 0),
        )
        setResult(RESULT_OK, CaptureManager.resultIntent(scanned, null))
        finish()
    }
}

/** 取景页：预览 + 底部一行（相册 · 提示 · 手电筒），与主流扫码器同一套摆法。 */
@Composable
private fun ScanScreen(
    scanner: DecoratedBarcodeView,
    prompt: String?,
    hasFlash: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onPickImage: () -> Unit,
    snackbar: SnackbarHostState,
) {
    Box(Modifier.fillMaxSize()) {
        // 预览是真实 View（TextureView）：交给 AndroidView 装进 Compose 树
        AndroidView(factory = { scanner }, modifier = Modifier.fillMaxSize())
        ScanGalleryButton(
            onClick = onPickImage,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = ScanButtonMargin, bottom = ScanButtonMargin),
        )
        prompt?.takeIf { it.isNotBlank() }?.let {
            // 先让开系统栏（库自带的 status 视图没让，正好被导航条盖住），再落在按钮行的中线上：
            // 「+14dp」= (48dp 按钮高 − 20dp 文字行盒) / 2，两边的水平内边距给两枚按钮留位
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = ScanButtonMargin + 14.dp)
                    .padding(horizontal = ScanButtonSize * 2),
            )
        }
        if (hasFlash) {
            ScanTorchButton(
                torchOn = torchOn,
                onClick = onToggleTorch,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = ScanButtonMargin, bottom = ScanButtonMargin),
            )
        }
        // 提示抬到按钮行之上：Snackbar 默认贴窗口底，正好压住这一行
        AppSnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = ScanButtonSize + ScanButtonMargin + 12.dp),
        )
    }
}

/** 手电筒开关：圆形深底 + 白色图标，开着时整枚换主题主色（一眼看出当前状态）。 */
@Composable
private fun ScanTorchButton(
    torchOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = {
            haptics.tap()
            onClick()
        },
        modifier = modifier
            .size(ScanButtonSize)
            .clip(CircleShape)
            .background(if (torchOn) scheme.primary else ScanButtonScrim),
    ) {
        Icon(
            imageVector = HugeIcons.Flashlight,
            contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
            tint = if (torchOn) scheme.onPrimary else Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 相册入口：带文字的胶囊（只放一枚图标认不出这是「从相册选图」）。 */
@Composable
private fun ScanGalleryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(ScanButtonScrim)
            .clickable {
                haptics.tap()
                onClick()
            }
            .height(ScanButtonSize)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.Image01,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "相册",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
