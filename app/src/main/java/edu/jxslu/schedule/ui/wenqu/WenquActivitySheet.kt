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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
) {
    // 底部动作行的「关掉再做一件事」（DESIGN §3.28，2026-10-11）：
    // 弹层是比页面高一层的独立窗口，页面级提示会被它盖住——所以先关弹层（退场动画走完
    // 再落 onDismiss），再复制 / 拉起微信，回执才看得见。null = 没请求关闭。
    var afterDismiss by remember { mutableStateOf<(() -> Unit)?>(null) }
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
            // 动作行只给**活动视角**：记录视角已经报过名了，「去微信报名」是错的语义。
            // 放在滚动内容的末尾（不钉在底部）：弹层内容是可滚的，钉底要么垫键盘高度、
            // 要么在弹层里套 weight——那套在这套主题里没有先例，别在这里开个头。
            showActions = recordBadge == null,
            onCopyName = { afterDismiss = { onNotice("活动名已复制") } },
            onOpenWechat = { afterDismiss = { openWechatForSignup(context, onNotice) } },
        )
    }
}

/**
 * 详情弹层底部的动作行（DESIGN §3.28，2026-10-11）。
 *
 * 本应用**全程只读**（红线：报名 / 签到 / 提交一律不碰），但「看完之后去哪」得给出来——
 * 否则用户看完就卡住了。两个出口：复制活动名（去小程序里搜）、去微信（App 只递手，
 * 不代报名）。**两个动作都先关弹层**：回执是页面级提示，被弹层盖住等于没给。
 */
@Composable
private fun WenquActivityActions(
    activity: WenquActivity,
    onCopyName: () -> Unit,
    onOpenWechat: () -> Unit,
) {
    val context = LocalContext.current
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
            modifier = Modifier.weight(1f),
        ) { Text("复制活动名") }
        Button(
            onClick = {
                clipboard.setText(AnnotatedString(activityName(activity)))
                onOpenWechat()
            },
            modifier = Modifier.weight(1f),
        ) { Text("去微信报名") }
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
    "报名、签到、提交材料等操作请到微信小程序「问渠」内完成；" +
        "本页只做只读展示，不代表已报名。"

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
    onCopyName: () -> Unit = {},
    onOpenWechat: () -> Unit = {},
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

        // 底部动作行（活动视角）：看完知道下一步去哪——App 只读，但出口得递到手上
        if (showActions) {
            WenquActivityActions(
                activity = activity,
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
