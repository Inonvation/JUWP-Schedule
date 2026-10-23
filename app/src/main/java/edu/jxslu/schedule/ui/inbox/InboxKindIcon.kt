package edu.jxslu.schedule.ui.inbox

import androidx.compose.ui.graphics.vector.ImageVector
import edu.jxslu.schedule.domain.InboxKind
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Bicycle
import me.rerere.hugeicons.stroke.BookEdit
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.GraduationScroll
import me.rerere.hugeicons.stroke.HeartHandshake
import me.rerere.hugeicons.stroke.Repair
import me.rerere.hugeicons.stroke.ShoppingBag01
import me.rerere.hugeicons.stroke.ShowerHead
import me.rerere.hugeicons.stroke.Wallet01
import me.rerere.hugeicons.stroke.WalletAdd01
import me.rerere.hugeicons.stroke.WashingMachine

/**
 * 收件箱各来源的行首图标（图标名全部查过本地 JAR 或项目已在用，勿在此替换成没查过的名字）。
 * 收件箱页与今日页收件箱卡共用——两处各写迟早漂。
 */
internal fun inboxKindIcon(kind: String): ImageVector = when (
    runCatching { InboxKind.valueOf(kind) }.getOrDefault(InboxKind.EXAM)
) {
    InboxKind.EXAM -> HugeIcons.Calendar01
    InboxKind.SCORE -> HugeIcons.GraduationScroll
    InboxKind.SCHEDULE_CHANGE -> HugeIcons.Exchange01
    InboxKind.SELECTION_ROUND -> HugeIcons.BookEdit
    InboxKind.POWER_LOW -> HugeIcons.Alert02
    InboxKind.YKT_LOW -> HugeIcons.Wallet01
    InboxKind.WENQU_ACTIVITY -> HugeIcons.HeartHandshake
    InboxKind.DORM_REPAIR -> HugeIcons.Repair
    InboxKind.KVCX_RIDE -> HugeIcons.Bicycle
    InboxKind.WATER_SESSION -> HugeIcons.Droplet
    InboxKind.QZXY_SESSION -> HugeIcons.ShowerHead
    InboxKind.UJING_ORDER -> HugeIcons.WashingMachine
    InboxKind.YKT_RECHARGE -> HugeIcons.WalletAdd01
    InboxKind.YKT_PAYMENT -> HugeIcons.ShoppingBag01
    InboxKind.POWER_RECHARGE -> HugeIcons.Flash
}
