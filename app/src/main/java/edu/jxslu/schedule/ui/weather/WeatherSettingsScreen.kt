package edu.jxslu.schedule.ui.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.weather.WeatherApi
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.me.MeViewModel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cloudy
import me.rerere.hugeicons.stroke.SunCloud01

/**
 * 我的 → 通用设置 → 功能 → 天气（DESIGN §3.29）。
 *
 * **默认关**：关着时今日页顶栏不占位、一次网络请求都不发；开启后顶栏日期下面出现
 * 一行 emoji + 一句话，点那行进天气详情页。
 *
 * 开关写 [MeViewModel]（DataStore `weather_enabled`），与其它功能开关同一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherSettingsScreen(
    onBack: () -> Unit,
    /** 查看天气详情（开关关着时也有入口，先看再决定开不开）。 */
    onOpenWeather: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val enabled by viewModel.weatherEnabled.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("天气") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = "今日页提示") {
                SettingSwitchRow(
                    title = "在今日页显示天气",
                    subtitle = "顶栏日期下一行显示天气与提醒，点它看详情",
                    checked = enabled,
                    onCheckedChange = viewModel::setWeatherEnabled,
                    icon = HugeIcons.Cloudy,
                )
                SettingItem(
                    title = "查看天气",
                    subtitle = "实时状态 · 今天与明天提醒 · 未来 7 天",
                    icon = HugeIcons.SunCloud01,
                    onClick = onOpenWeather,
                )
                Text(
                    text = "提示按时间换口径：18:00 前讲今天（要不要带伞、注不注意防晒），" +
                        "18:00 起讲明天，方便提前准备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            SettingsSection(title = "数据") {
                Text(
                    text = "天气数据来自 Open-Meteo（CC BY 4.0）。查询点固定为校区" +
                        "（${WeatherApi.CAMPUS_LABEL}）：不读手机定位，也不上传任何个人或设备信息。" +
                        "预报仅供参考。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
        }
    }
}
