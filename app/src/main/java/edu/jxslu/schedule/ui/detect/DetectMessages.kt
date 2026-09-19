package edu.jxslu.schedule.ui.detect

import edu.jxslu.schedule.data.jw.JwDetectRunner

/**
 * 检测结果 → 用户可读文案。三个入口（导入弹层「检测课表更新」、设置页「立即检测」、
 * 更新课表页空态按钮）共用同一套，保证同一结果在哪儿都是同一句话。
 */
internal fun detectOutcomeMessage(outcome: JwDetectRunner.Outcome): String = when (outcome) {
    is JwDetectRunner.Outcome.NoDiff ->
        if (outcome.baselineCreated) {
            "已建立教务基线，下次检测开始报告课表变化"
        } else {
            "教务课表无变化"
        }
    is JwDetectRunner.Outcome.DiffFound -> "检测到课表变化"
    is JwDetectRunner.Outcome.Skipped -> outcome.reason
    is JwDetectRunner.Outcome.Failed -> "检测失败：${outcome.message}"
}
