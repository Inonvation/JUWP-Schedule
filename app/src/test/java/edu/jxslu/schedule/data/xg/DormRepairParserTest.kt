package edu.jxslu.schedule.data.xg

import edu.jxslu.schedule.domain.DormRepairRules
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报修列表解析（DESIGN §4.49）。fixture 全是合成数据，**不含任何真实姓名/学号/房号**；
 * 个人字段的取值故意写成「不应出现」，用来钉住解析层不把它们带进模型。
 */
class DormRepairParserTest {

    private val config = """
        [
          {"id":88,"compt":"selectbox","fields":[{"label":"当前状态","values":[]}]},
          {"id":87,"compt":"editinput","fields":[{"label":"宿舍信息","values":[]}]},
          {"id":77,"compt":"editinput","fields":[{"label":"宿舍","values":[]}]},
          {"id":81,"compt":"editinput","fields":[{"label":"故障类型","values":[]}]},
          {"id":78,"compt":"editinput","fields":[{"label":"详情描述","values":[]}]},
          {"id":76,"compt":"dateinput","fields":[{"label":"最终完成时间","values":[]}]},
          {"id":1,"compt":"contact","fields":[{"label":"姓名","values":[]}]},
          {"id":2,"compt":"editinput","fields":[{"label":"学号","values":[]}]}
        ]
    """.trimIndent()

    private val flow = """
        [
          {"cardType":1,"title":"楼栋学生干部","nodeUniqueId":"101",
           "userList":[{"uname":"某审批人"},{"uname":"另一审批人"}]},
          {"cardType":1,"title":"宿管科下发维修工单","nodeUniqueId":"102",
           "userList":[{"uname":"某老师"}]},
          {"cardType":2,"title":"抄送老师","nodeUniqueId":"103","userList":[{"uname":"某老师"}]}
        ]
    """.trimIndent()

    private val pending = """
        {
          "id":1001,
          "formId":278625,
          "customTitle":"宿舍报修",
          "aprvStatusType":"待处理",
          "aprvStatusTypeId":1,
          "inserttime":1700000000000,
          "updatetime":1700000001000,
          "currentAprvUser":"某审批人",
          "currentApproveUserList":[
            {"aprvUserName":"某审批人","nodeUniqueId":"101"}
          ],
          "flowUser":${JsonPrimitive(flow)},
          "aprvSummary":[{"key":"姓名","value":"PERSONAL_SHOULD_NOT_APPEAR"},{"key":"学号","value":"PERSONAL_ID_SHOULD_NOT_APPEAR"}],
          "formData":[
            {"id":88,"compt":"selectbox","fields":[{"values":[{"val":"待处理"}]}]},
            {"id":87,"compt":"editinput","fields":[{"values":[{"val":"某栋101"}]}]},
            {"id":81,"compt":"editinput","fields":[{"values":[{"val":"水电维修-灯-"}]}]},
            {"id":78,"compt":"editinput","fields":[{"values":[{"val":"灯不亮"}]}]},
            {"id":1,"compt":"contact","fields":[{"values":[{"uname":"PERSONAL_SHOULD_NOT_APPEAR"}]}]}
          ]
        }
    """.trimIndent()

    private val completed = """
        {
          "id":1000,
          "formId":278625,
          "customTitle":"宿舍报修",
          "aprvStatusType":"已通过",
          "aprvStatusTypeId":2,
          "inserttime":1690000000000,
          "updatetime":1690000900000,
          "currentApproveUserList":[],
          "flowUser":${JsonPrimitive(flow)},
          "formData":[
            {"id":88,"compt":"selectbox","fields":[{"values":[{"val":"已完成"}]}]},
            {"id":87,"compt":"editinput","fields":[{"values":[{"val":"某栋102"}]}]},
            {"id":76,"compt":"dateinput","fields":[{"values":[{"val":"2026-10-19 10:30"}]}]}
          ]
        }
    """.trimIndent()

    private fun response(records: String): String = """
        {"success":true,"data":{"formsConfig":${JsonPrimitive(config)},"formsUserList":[$records]}}
    """.trimIndent()

    @Test
    fun `parses pending record and keeps labels out of personal fields`() {
        val records = DormRepairParser.parse(response(pending))
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(1001L, record.formUserId)
        assertEquals(278625L, record.formId)
        assertEquals("宿舍报修", record.title)
        assertEquals("待处理", record.approvalStatus)
        assertEquals(1, record.approvalStatusId)
        assertEquals("待处理", record.repairStatus)
        assertEquals("某栋101", record.dorm)
        assertEquals("水电维修-灯", record.fault)
        assertEquals("灯不亮", record.description)
        assertEquals(1700000000000L, record.submittedAt)
        assertEquals(listOf("某审批人"), record.currentApprovers)
        assertFalse(DormRepairRules.isFinished(record))

        val first = record.flowNodes.first()
        assertEquals("楼栋学生干部", first.title)
        assertTrue(first.current)
        assertEquals(listOf("某审批人", "另一审批人"), first.approvers)
        assertEquals(2, record.flowNodes.size) // 抄送节点不进流程条

        // 个人字段连影子都不该出现在落盘模型里
        val encoded = Json.encodeToString(record)
        assertFalse(encoded.contains("PERSONAL_SHOULD_NOT_APPEAR"))
        assertFalse(encoded.contains("PERSONAL_ID_SHOULD_NOT_APPEAR"))
    }

    @Test
    fun `parses completed record and final completion time`() {
        val records = DormRepairParser.parse(response(completed))
        val record = records.single()
        assertEquals("已完成", record.repairStatus)
        assertEquals(2, record.approvalStatusId)
        assertTrue(DormRepairRules.isFinished(record))
        assertTrue(record.finishedAt != null)
        assertFalse(record.flowNodes.any { it.current })
    }

    @Test
    fun `sorts by inserttime descending`() {
        val records = DormRepairParser.parse(response("$completed,$pending"))
        assertEquals(listOf(1001L, 1000L), records.map { it.formUserId })
    }

    @Test
    fun `empty list is authoritative empty`() {
        val body = """{"success":true,"data":{"formsConfig":"[]","formsUserList":[]}}"""
        assertTrue(DormRepairParser.parse(body).isEmpty())
    }

    @Test
    fun `bad json throws`() {
        assertThrows(IllegalStateException::class.java) {
            DormRepairParser.parse("<html>login</html>")
        }
    }

    @Test
    fun `success false throws with server message`() {
        val e = assertThrows(IllegalStateException::class.java) {
            DormRepairParser.parse("""{"success":false,"msg":"没有权限"}""")
        }
        assertEquals("没有权限", e.message)
    }

    @Test
    fun `unknown labels degrade without crashing`() {
        val body = """
            {"success":true,"data":{"formsConfig":"[]","formsUserList":[
              {"id":7,"inserttime":1,"aprvStatusType":"待处理","aprvStatusTypeId":1}
            ]}}
        """.trimIndent()
        val record = DormRepairParser.parse(body).single()
        assertEquals("", record.dorm)
        assertEquals("", record.fault)
        assertNull(record.finishedAt)
        // 没有 formsConfig 时按 label 取字段全部落空；审批状态仍照实保留
        assertEquals("", record.repairStatus)
        assertEquals("待处理", record.approvalStatus)
    }
}
