package edu.jxslu.schedule.data.qiekj

import edu.jxslu.schedule.domain.DiagnosisResult

/**
 * 开水失败诊断（对齐 light-life DeviceErrorDiagnosis）。
 *
 * 根因：风控/设备类失败（实名认证、位置风控、设备离线、次数用完）不可程序化绕过，
 * 用户需要的是「哪一步、为什么、怎么办」，不是裸错误码。分类按服务端中文文案关键词，
 * 未命中时回退「未知错误 + 原始信息」，保证永远可展示。
 */
object QiekjErrorDiagnosis {

    fun diagnose(code: Int?, message: String?, step: String): DiagnosisResult {
        val raw = buildRawError(code, message)
        val msg = message?.lowercase() ?: ""
        val codeVal = code ?: -1

        val (reason, suggestions) = when {
            // 积分风控：官方 App 内需先勾选「使用积分」并完成实名认证
            msg.contains("积分") && (msg.contains("拦截") || msg.contains("风控") || msg.contains("风险")) ->
                "积分使用权限未开通" to listOf(
                    "去胖乖生活 App → 饮水页面，勾选「使用积分」选项",
                    "按提示完成实名认证后重试",
                )

            msg.contains("认证") || msg.contains("实名") ->
                "需要完成实名认证" to listOf(
                    "在胖乖生活 App 中完成实名认证",
                    "认证完成后等待 5~10 分钟再试",
                )

            // 位置风控：isCheckLocation 未过（code 33003 为该类错误的已知码）
            msg.contains("位置") || msg.contains("location") || codeVal == 33003 ->
                "位置风控拦截" to listOf(
                    "确认手机 GPS 定位已开启",
                    "确认定位权限已授予本应用",
                    "不要使用 VPN 或代理",
                )

            msg.contains("设备") && (msg.contains("离线") || msg.contains("不在线")
                || msg.contains("不可用") || msg.contains("未找到")) ->
                "设备可能离线或断电" to listOf(
                    "检查饮水机电源是否接通",
                    "确认饮水机已联网（指示灯状态）",
                    "尝试在胖乖生活 App 中刷新设备列表",
                )

            msg.contains("使用中") || msg.contains("忙碌") || msg.contains("busy") ->
                "设备正在被其他用户使用" to listOf(
                    "等待当前使用结束后重试",
                    "选择其他可用设备",
                )

            msg.contains("次数") && msg.contains("完") || msg.contains("额度") && msg.contains("完")
                || msg.contains("已达上限") || msg.contains("quota") ->
                "今日可用次数已用完" to listOf(
                    "明天再试",
                    "检查是否有其他可用的免费次数",
                )

            msg.contains("timeout") || msg.contains("超时") || msg.contains("连接") && msg.contains("失败") ->
                "网络连接异常" to listOf(
                    "检查手机网络连接",
                    "切换 Wi-Fi 或移动数据后重试",
                )

            msg.contains("http") && (msg.contains("500") || msg.contains("502") || msg.contains("503")) ->
                "服务端异常" to listOf(
                    "平台服务器可能临时故障",
                    "等待几分钟后重试",
                )

            msg.contains("token") && (msg.contains("过期") || msg.contains("无效")
                || msg.contains("invalid") || msg.contains("expired"))
                || codeVal == 401 || codeVal == 403 ->
                "登录凭证已过期" to listOf(
                    "返回「我的」页面重新登录",
                )

            msg.contains("积分") && msg.contains("不足") || msg.contains("余额") && msg.contains("不足") ->
                "积分余额不足" to listOf(
                    "检查积分余额，或在开水时关闭「使用积分抵扣」",
                )

            else -> null to emptyList()
        }

        return DiagnosisResult(
            primaryReason = reason ?: "未知错误（code: $codeVal）",
            rawError = raw,
            step = step,
            suggestions = suggestions,
        )
    }

    private fun buildRawError(code: Int?, message: String?): String {
        val parts = mutableListOf<String>()
        if (code != null && code != 0) parts.add("错误码: $code")
        if (!message.isNullOrBlank()) parts.add("错误信息: $message")
        return parts.joinToString(" | ").ifBlank { "无详细错误信息" }
    }
}
