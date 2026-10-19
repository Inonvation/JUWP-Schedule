package edu.jxslu.schedule.ui.wenqu

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.wenqu.WenquActivity
import edu.jxslu.schedule.domain.WenquRules
import edu.jxslu.schedule.startActivityOutsideApp
import edu.jxslu.schedule.ui.common.DialogWindowFadeEffect
import edu.jxslu.schedule.ui.common.ImeAwareModalBottomSheet
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01

/**
 * 活动详情弹层（DESIGN §3.28）。
 *
 * 两个入口共用它：**活动列表**点一张卡、**记录页**点一条记录。
 * 数据来源：入口那条**先顶着**（弹层立刻有内容），`volunteer/detail/{id}` 回来再补齐——
 * 工时与活动群号只有详情接口有，[detailLoading] 为 true 时下面挂一条读取提示。
 * 详情失败时调用方会保留入口那条数据，弹层不会空掉。
 *
 * [recordBadge] 非 null = **记录视角**：徽章换成「我的报名状态」（如「已完成」）而不是活动状态
 * （记录看的是「我这条怎么样了」），并去掉「我的状态」行（徽章已经说了）。
 *
 * **报名 / 取消报名**（2026-10-11 起本应用不再是纯只读，DESIGN §3.28 / §4.45）：
 * 底部动作行的主按钮按状态三选一（报名 / 取消报名 / 去微信报名），两个写操作都**先过二次确认**
 * （[WenquActionConfirmDialog]）再交给调用方的 [onApply] / [onCancel]；进行中按钮转圈防连点，
 * 失败原因留在弹层里（[actionError]）——**不飘页面级提醒**，那会被弹层盖住。
 *
 * **负责人姓名 / 电话会展示**（2026-10-08 用户要求）：它们是平台公开给登录学生的活动联系人信息，
 * 只在弹层里展示、可长按复制；**不进日志、不进持久化、不进测试夹具与文档**
 * （这个仓库是公开的）。`attendance_*`（考勤人）模型里没声明，不会出现在这里。
 */
@Composable
fun WenquActivitySheet(
    activity: WenquActivity,
    detailLoading: Boolean,
    onDismiss: () -> Unit,
    recordBadge: String? = null,
    /** 详情取不到（平台回 `detail: null` / 网络失败）：弹层内挂一行说明，**不弹提醒**。 */
    detailUnavailable: Boolean = false,
    /** 复制电话 / 群号 / 活动名后的回执（走页面的统一提示）。 */
    onNotice: (String) -> Unit = {},
    /** **活动视角**的报名闸门原因（`WenquRules.applyBlockReason`；空 = 可报名）。 */
    applyBlockReason: String = "",
    /** 要不要给「取消报名」入口（活动视角 = 已报名 + 招募中；记录视角 = 平台 `canCancel`）。 */
    canCancel: Boolean = false,
    /** 报名 / 取消进行中（按钮转圈 + 防连点）。 */
    busy: Boolean = false,
    /** 报名 / 取消失败的原因，挂在动作行上方——见下方「为什么不飘页面级提醒」。 */
    actionError: String? = null,
    onApply: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    /**
     * **盯这条**（2026-10-15，DESIGN §4.45「盯指定活动」）：非 null 才显示按钮；
     * 且只在「还会再放名额」的状态出现（[WenquRules.canPin]：报名尚未开始 / 名额已满）——
     * 已结束 / 进行中 / 已报名等状态盯了也不会再有动静（2026-10-16 修，用户报）。
     * 记录视角（`WenquRecordsScreen`）不传——盯一条「我参加过的」没有意义。
     */
    onPin: (() -> Unit)? = null,
    /** 这条活动**已预报名**时的报名开始时刻（0 = 没排）；非 0 显示「已预报名」状态行。 */
    preregOpensAt: Long = 0L,
    /**
     * **预报名**（2026-10-16，DESIGN §4.45；用户拍板）：非 null 才显示按钮，
     * 且只在「报名尚未开始」（[WenquRules.APPLY_NOT_STARTED]）时出现。
     * 记录视角不传（记录里的活动谈不上「还没开始报名」）。
     */
    onRequestPrereg: (() -> Unit)? = null,
    /** 取消预报名（已排上时状态行点它取消）。 */
    onCancelPrereg: (() -> Unit)? = null,
) {
    // 底部动作行的「关掉再做一件事」（DESIGN §3.28，2026-10-11）：
    // 弹层是比页面高一层的独立窗口，页面级提示会被它盖住——所以先关弹层（退场动画走完
    // 再落 onDismiss），再复制 / 拉起微信，回执才看得见。null = 没请求关闭。
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 报名 / 取消的二次确认（DESIGN §3.28，2026-10-11）：写操作前必须过一道确认。
    var confirmKind by remember { mutableStateOf<SheetConfirm?>(null) }
    // 「预报名」的二次确认（2026-10-16）：开启后到点自动提交，必须过一次确认。
    var preregConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    ImeAwareModalBottomSheet(onDismiss = onDismiss, pendingDismiss = afterDismiss) {
        WenquActivityDetailContent(
            activity = activity,
            detailLoading = detailLoading,
            badge = recordBadge ?: WenquRules.statusLabel(activity.status),
            badgeColor = if (recordBadge != null) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            } else {
                wenquStatusColor(activity.status)
            },
            showAppliedRow = recordBadge == null,
            footer = if (recordBadge != null) RECORD_FOOTER else ACTIVITY_FOOTER,
            detailUnavailable = detailUnavailable,
            onNotice = onNotice,
            // 动作行默认只给**活动视角**；记录视角在平台说「这条能取消」时才补一个取消入口。
            // 放在滚动内容的末尾（不钉在底部）：弹层内容是可滚的，钉底要么垫键盘高度、
            // 要么在弹层里套 weight——那套在这套主题里没有先例，别在这里开个头。
            showActions = recordBadge == null || canCancel,
            applyBlockReason = applyBlockReason,
            canCancel = canCancel,
            busy = busy,
            actionError = actionError,
            onRequestApply = { confirmKind = SheetConfirm.APPLY },
            onRequestCancel = { confirmKind = SheetConfirm.CANCEL },
            onCopyName = { afterDismiss = { onNotice("活动名已复制") } },
            onOpenWechat = { afterDismiss = { openWechatForSignup(context, onNotice) } },
            onPin = onPin,
            preregOpenAt = preregOpensAt,
            preregEligible = onRequestPrereg != null &&
                applyBlockReason == WenquRules.APPLY_NOT_STARTED,
            onRequestPreregClick = { preregConfirm = true },
            onCancelPrereg = onCancelPrereg,
        )
    }
    if (preregConfirm) {
        val openAt = WenquRules.stampMillis(activity.applyStartTime)
        WenquPreregConfirmDialog(
            activityName = activityName(activity),
            opensAtText = openAt
                ?.let { WenquRules.preregOpenLabel(it, System.currentTimeMillis()) }
                .orEmpty(),
            onConfirm = {
                preregConfirm = false
                onRequestPrereg?.invoke()
            },
            onDismiss = { preregConfirm = false },
        )
    }
    confirmKind?.let { kind ->
        WenquActionConfirmDialog(
            kind = kind,
            activityName = activityName(activity),
            timeText = WenquRules.timeRange(activity.startTime, activity.endTime),
            onConfirm = {
                confirmKind = null
                if (kind == SheetConfirm.APPLY) onApply?.invoke() else onCancel?.invoke()
            },
            onDismiss = { confirmKind = null },
        )
    }
}

/** 二次确认的两件事（报名 / 取消报名）。 */
private enum class SheetConfirm { APPLY, CANCEL }

/**
 * 报名 / 取消报名的二次确认弹窗（DESIGN §3.28）。
 *
 * 文案刻意**只陈述事实**：报名 / 取消都会**直接提交到问渠平台**（本应用不再是只读展示），
 * 所以要把「这一步会改平台数据」说清楚，别让用户以为是本地操作。
 */
@Composable
private fun WenquActionConfirmDialog(
    kind: SheetConfirm,
    activityName: String,
    timeText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isApply = kind == SheetConfirm.APPLY
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isApply) "报名确认" else "取消报名") },
        text = {
            DialogWindowFadeEffect()
            Column {
                Text(
                    text = activityName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (timeText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (isApply) {
                        "报名会直接提交到「问渠」平台。报名后如需取消，可再打开本条活动取消（以平台规则为准）。"
                    } else {
                        "取消后本条报名会从平台移除，如需再次参加请重新报名。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (isApply) "确认报名" else "确认取消") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (isApply) "再想想" else "保留报名") }
        },
    )
}

/**
 * 「预报名」二次确认（DESIGN §3.28 / §4.45，2026-10-16；用户拍板）。
 *
 * 说清三件事：这是**自动提交**（本应用唯一一处）、会重试但有限次、结果通知 / 随时可取消。
 */
@Composable
private fun WenquPreregConfirmDialog(
    activityName: String,
    opensAtText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预报名确认") },
        text = {
            DialogWindowFadeEffect()
            Column {
                Text(
                    text = activityName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (opensAtText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "报名时间 $opensAtText",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "确认后本应用会代替你在开放时刻自动提交报名：从开始前 " +
                        "${WenquRules.PREREG_TRY_BEFORE_MS / 1000} 秒起，" +
                        "最多试 ${WenquRules.PREREG_MAX_ATTEMPTS} 发，成功或失败都会通知你；" +
                        "活动时间有变化会自动顺延。" +
                        "平台有时提前几秒放行，自动提交就是为了吃到这几秒；期间随时可在本页取消。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("开启预报名") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("再想想") } },
    )
}

/**
 * 详情弹层底部的动作行（DESIGN §3.28）。
 *
 * 主按钮按**这条活动当前的状态**三选一（2026-10-11，本应用不再是纯只读）：
 * - 已报名且平台允许取消（[canCancel]）→ **取消报名**（过二次确认）；
 * - 还能报名（[WenquRules.applyBlockReason] 回空）→ **报名**（过二次确认）；
 * - 不能报名（已满 / 已截止 / 已结束 / 被限制）→ 按钮禁用，原因写在动作行上方；
 * - 其余（进行中 / 已结束且没报名）→ 保持原样给「去微信报名」，只递手不代报名。
 *
 * 次按钮永远是「复制活动名」——报名 / 取消都由本应用做了，但活动名复制这个出口
 * （去小程序里搜、贴给同学）仍然有用。
 */
@Composable
private fun WenquActivityActions(
    activity: WenquActivity,
    applyBlockReason: String,
    canCancel: Boolean,
    busy: Boolean,
    onRequestApply: () -> Unit,
    onRequestCancel: () -> Unit,
    onCopyName: () -> Unit,
    onOpenWechat: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                clipboard.setText(AnnotatedString(activityName(activity)))
                onCopyName()
            },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) { Text("复制活动名") }

        when {
            canCancel -> Button(
                onClick = onRequestCancel,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "取消中…" else "取消报名") }

            // 报过名但平台不让取消（如活动已结束）：给一枚禁用的「已报名」，别写「报名」
            activity.applied -> Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) { Text("已报名") }

            WenquRules.canApply(applyBlockReason) -> Button(
                onClick = onRequestApply,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text(if (busy) "报名中…" else "报名") }

            // 不能报名：按钮留着但禁用，原因在动作行上方那行（别让按钮凭空消失）
            applyBlockReason.isNotBlank() -> Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.weight(1f),
            ) { Text("报名") }

            else -> Button(
                onClick = {
                    clipboard.setText(AnnotatedString(activityName(activity)))
                    onOpenWechat()
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("去微信报名") }
        }
    }
}

/** 复制 / 引导用的活动名：标题空就退到项目名，再空退到「志愿服务活动」。 */
private fun activityName(activity: WenquActivity): String =
    activity.title.ifBlank { activity.projectName.ifBlank { "志愿服务活动" } }

/**
 * 拉起微信并给一句引导（App 不代报名）。
 *
 * 走 [edu.jxslu.schedule.startActivityOutsideApp]：微信是 singleTask、永远开不进本 task，
 * 从它返回时二级页的右推入过渡会被重放（用户报过「界面跳动」），这个出口会先换静止过渡。
 * 只做一级兜底——这里只是「递到微信首页」，没有扫一扫那种直达诉求。
 */
private fun openWechatForSignup(context: android.content.Context, onNotice: (String) -> Unit) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
    if (launch == null) {
        onNotice("没检测到微信；活动名已复制，可到小程序「问渠」里报名")
        return
    }
    runCatching {
        context.startActivityOutsideApp(launch)
        onNotice("活动名已复制，请在微信小程序「问渠」里报名")
    }.onFailure {
        onNotice("打不开微信；活动名已复制，请手动打开小程序报名")
    }
}

private const val ACTIVITY_FOOTER =
    "报名 / 取消报名会直接提交到「问渠」平台；签到、提交材料等其它操作请到微信小程序内完成。"

private const val RECORD_FOOTER =
    "本条记录与工时以「问渠」平台为准；如需申诉或修改，请到微信小程序内联系活动单位。"

/**
 * 详情正文：活动视角（活动列表点卡片）与记录视角（记录页点记录）共用同一份。
 *
 * [badge] / [badgeColor] 由调用方给——活动视角是活动状态，记录视角是「我的报名状态」。
 * [showAppliedRow] 只对活动视角为 true：记录视角的徽章已经说明了我的状态。
 */
@Composable
private fun WenquActivityDetailContent(
    activity: WenquActivity,
    detailLoading: Boolean,
    badge: String,
    badgeColor: Color,
    showAppliedRow: Boolean,
    footer: String,
    detailUnavailable: Boolean,
    onNotice: (String) -> Unit,
    showActions: Boolean = false,
    applyBlockReason: String = "",
    canCancel: Boolean = false,
    busy: Boolean = false,
    actionError: String? = null,
    onRequestApply: () -> Unit = {},
    onRequestCancel: () -> Unit = {},
    onCopyName: () -> Unit = {},
    onOpenWechat: () -> Unit = {},
    onPin: (() -> Unit)? = null,
    /** 已预报名的报名开始时刻（>0 显示状态行；见主入口的 [preregOpensAt]）。 */
    preregOpenAt: Long = 0L,
    /** 满足「报名尚未开始」+ 调用方提供了回调时才给「预报名」按钮。 */
    preregEligible: Boolean = false,
    onRequestPreregClick: (() -> Unit)? = null,
    onCancelPrereg: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 字段多，小屏或大字号下要能滚
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier
                        .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            val score = WenquRules.hoursOf(activity.score)
            if (score > 0.0) {
                Text(
                    text = "计 ${WenquRules.formatHours(score)} 工时",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = activity.title.ifBlank { activity.projectName.ifBlank { "志愿服务活动" } },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (activity.projectName.isNotBlank()) {
            Text(
                text = activity.projectName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        DetailRow("单位", activity.unit)
        DetailRow("地点", activity.address)
        DetailRow("活动时间", WenquRules.timeRange(activity.startTime, activity.endTime))
        DetailRow("报名时间", WenquRules.timeRange(activity.applyStartTime, activity.applyEndTime))
        DetailRow("招募人数", activity.peopleNum.takeIf { it > 0 }?.toString().orEmpty())
        DetailRow("已报名", activity.joinNum.takeIf { it > 0 }?.toString().orEmpty())
        if (showAppliedRow) {
            DetailRow("我的状态", if (activity.applied) "已报名" else "")
        }
        // 负责人姓名 / 电话：平台公开给登录学生的活动联系人，点行复制（比长按更好发现）
        DetailRow("负责人", activity.leaderName)
        DetailRow("负责人电话", activity.leaderPhone, copyable = true, onNotice = onNotice)
        DetailRow("活动群号", activity.groupId, copyable = true, onNotice = onNotice)
        // 群号有些活动是「报名后才给」的（用户口径）：先报名、再进群，别反过来
        if (activity.groupId.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(84.dp))
                Text(
                    text = "请确保在小程序报名成功后再进群",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f).padding(bottom = 6.dp),
                )
            }
        }

        if (activity.content.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "活动内容",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = activity.content,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (detailLoading) {
            LoadingHint(
                title = "正在读取详情",
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
        } else if (detailUnavailable) {
            // 取不到完整详情不是错误：上面已经有入口那条（列表 / 记录）的信息了。
            // 一行说明就够，**不飘白色提醒**（2026-10-08 用户报）。
            Spacer(Modifier.height(12.dp))
            Text(
                text = "这条活动的完整详情暂时取不到（工时 / 群号可能缺失），以上是列表里的信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }

        Spacer(Modifier.height(18.dp))
        Text(
            text = footer,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )

        // 底部动作行：看完知道下一步去哪（报名 / 取消 / 去微信）
        if (showActions) {
            // 不能报名时把原因写在按钮上方——按钮禁用但不让它凭空消失，用户才知道为什么。
            // 「已报名」不算「不能报名」的原因（按钮已经写着已报名了），别重复。
            if (!canCancel && !activity.applied && applyBlockReason.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "暂不能报名：$applyBlockReason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            // 报名 / 取消失败：留在弹层里说清原因，**不飘页面级提醒**（弹层会把它盖住）
            if (!actionError.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = actionError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // 「预报名」（2026-10-16）：报名还没开始的活动的自动化出口，放在动作区最上方
            // （「盯这条」是等名额，这里是到点自动抢）。
            if (preregOpenAt > 0L) {
                if (onCancelPrereg != null) {
                    OutlinedButton(
                        onClick = onCancelPrereg,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    ) {
                        Text(
                            "已预报名 · " + WenquRules.preregOpenLabel(
                                preregOpenAt,
                                System.currentTimeMillis(),
                            ) + " 自动抢（点此取消）",
                        )
                    }
                }
            } else if (preregEligible && onRequestPreregClick != null) {
                OutlinedButton(
                    onClick = onRequestPreregClick,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) { Text("预报名 · 准点自动抢") }
            }

            // 「盯这条」（2026-10-15；2026-10-16 修口径）：只有**还会再放名额**的状态才给——
            // 报名还没开始（等开放）或名额已满（等空位）。已结束 / 进行中 / 已报名 / 被限制
            // 等状态盯了也不会再有动静，不给入口（此前无条件显示，2026-10-16 用户报）。
            // 放在主动作行**上方**——它不是主路径（主路径仍是报名），但比复制更靠近主路径。
            if (onPin != null && WenquRules.canPin(applyBlockReason)) {
                OutlinedButton(
                    onClick = onPin,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                ) {
                    Text(
                        if (applyBlockReason == WenquRules.APPLY_NOT_STARTED) {
                            "盯这条 · 开始报名时提醒我"
                        } else {
                            "盯这条 · 名额空出时提醒我"
                        },
                    )
                }
            }
            WenquActivityActions(
                activity = activity,
                applyBlockReason = applyBlockReason,
                canCancel = canCancel,
                busy = busy,
                onRequestApply = onRequestApply,
                onRequestCancel = onRequestCancel,
                onCopyName = onCopyName,
                onOpenWechat = onOpenWechat,
            )
        }
    }
}

/**
 * key-value 行。空值直接不显示——平台有整格不输出的时候。
 *
 * [copyable] = true 时**整行可点即复制**（电话 / 群号），行尾一枚淡淡的复制图标当提示；
 * 行高抬到 44dp 保命中区。比「只能长按选中」好发现得多（2026-10-08 用户要的交互细节）。
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    copyable: Boolean = false,
    onNotice: (String) -> Unit = {},
) {
    if (value.isBlank()) return
    val clipboard = LocalClipboardManager.current
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (copyable) Modifier.heightIn(min = 44.dp) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = copyable, onClickLabel = "复制$label") {
                clipboard.setText(AnnotatedString(value))
                haptics.tap()
                onNotice("已复制$label")
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (copyable) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = HugeIcons.Copy01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
