package edu.jxslu.schedule.data.wenqu

import edu.jxslu.schedule.data.qiekj.QiekjJson
import edu.jxslu.schedule.domain.WenquRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问渠响应解析（DESIGN §4.45）。
 *
 * 夹具是**按真实响应结构脱敏改写**的：字段名与类型逐字保留（含平台特有的
 * `status` / `msg`、`total_score` 有时是数字有时是字符串），
 * **但人名与手机号一律换成假值**——真实数据里有他人隐私，不许进仓库（见 backup 注释同款纪律）。
 */
class WenquModelsTest {

    private fun envelope(json: String) = QiekjJson.json.decodeFromString(WenquEnvelope.serializer(), json)

    @Test
    fun `信封成功判定只看 status`() {
        val env = envelope("""{"status":200,"msg":"ok","data":{"a":1}}""")
        env.requireSuccess()
        assertEquals(WenquEnvelope.STATUS_OK, env.status)
    }

    @Test
    fun `会话失效是 410000_且只有它算凭证失效`() {
        val env = envelope("""{"status":410000,"msg":"请登录"}""")
        assertEquals(WenquEnvelope.STATUS_NEED_LOGIN, env.status)
        val thrown = runCatching { env.requireSuccess() }.exceptionOrNull()
        assertTrue(thrown is WenquSessionExpiredException)
    }

    @Test
    fun `业务错误抛普通异常并带上平台文案`() {
        val env = envelope("""{"status":400,"msg":"账号或密码错误"}""")
        val thrown = runCatching { env.requireSuccess() }.exceptionOrNull()
        assertTrue(thrown is WenquApiException)
        assertEquals("账号或密码错误", thrown?.message)
    }

    @Test
    fun `发码成功与失败都是 status 200_只能看 msg`() {
        // 平台实测：成功与「暂无使用次数」都是 status:200，靠 msg 区分
        assertTrue(envelope("""{"status":200,"msg":"发送成功"}""").sendResult())
        assertFalse(envelope("""{"status":200,"msg":"接口请求失败:暂无使用次数"}""").sendResult())
        assertFalse(envelope("""{"status":400,"msg":"手机号格式错误"}""").sendResult())
    }

    @Test
    fun `登录响应取 token 与到期时间`() {
        val env = envelope(
            """{"status":200,"msg":"登录成功","data":{"token":"a.b.c","expires_time":"2026-10-11 19:22:57"}}""",
        )
        val data = env.data.decodeData<WenquLoginData>()
        assertEquals("a.b.c", data?.token)
        assertEquals("2026-10-11 19:22:57", data?.expiresTime)
    }

    @Test
    fun `志愿记录解析_总工时与单条工时`() {
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"total_score":100,"list":[
              {"id":1,"title":"某志愿服务活动","unit":"某学院","start_time":"26-03-28 00:00",
               "end_time":"26-03-29 00:00","score":"4","apply_status_text":"已结束"}
            ]}}
            """.trimIndent(),
        )
        val data = env.data.decodeData<WenquVolunteerData>()
        assertEquals("100", data?.totalScore)
        assertEquals(1, data?.list?.size)
        val record = data!!.list.first()
        assertEquals("某志愿服务活动", record.title)
        assertEquals("某学院", record.unit)
        assertEquals("4", record.score)
        assertEquals("已结束", record.statusText)
    }

    @Test
    fun `总工时是数字也能解析`() {
        val env = envelope("""{"status":200,"msg":"ok","data":{"total_score":100,"list":[]}}""")
        assertEquals("100", env.data.decodeData<WenquVolunteerData>()?.totalScore)
    }

    @Test
    fun `负责人要展示_考勤人不进模型`() {
        // 2026-10-08 用户要求详情显示负责人与电话 → leader_* 声明进模型；
        // attendance_*（考勤人）仍然不声明，ignoreUnknownKeys 下连内存都不进。
        // 夹具一律假名假号（公开仓库，见 AGENTS.md「提交不带个人信息」）。
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"total_score":1,"list":[
              {"id":1,"title":"x","leader_name":"张三","leader_phone":"13800000000",
               "attendance_name":"李四","attendance_phone":"13900000000","content":"y"}
            ]}}
            """.trimIndent(),
        )
        val record = env.data.decodeData<WenquVolunteerData>()!!.list.first()
        assertEquals("张三", record.leaderName)
        assertEquals("13800000000", record.leaderPhone)
        // 考勤人没声明 → 连 toString 里都不该出现
        assertFalse(record.toString().contains("李四"))
        assertFalse(record.toString().contains("13900000000"))
    }

    @Test
    fun `星级证书解析`() {
        val env = envelope(
            """{"status":200,"msg":"ok","data":{"volunteer_score_total":"100","star_level":2,
               "student":{"student_no":"2024000000"},"list":[]}}""",
        )
        val data = env.data.decodeData<WenquStarCertData>()
        assertEquals("100", data?.scoreTotal)
        assertEquals(2, data?.starLevel)
    }

    @Test
    fun `data 缺失或为 null 按空处理_不整批失败`() {
        assertNull(envelope("""{"status":200,"msg":"ok"}""").data.decodeData<WenquVolunteerData>())
        assertNull(envelope("""{"status":200,"msg":"ok","data":null}""").data.decodeData<WenquVolunteerData>())
        assertNull(envelope("""{"status":200,"msg":"ok","data":[]}""").data.decodeData<WenquVolunteerData>())
    }

    @Test
    fun `记录解析_带地点与报名时间`() {
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"total_score":100,"list":[
              {"id":34658,"title":"某志愿服务活动","unit":"某学院","address":"某楼A404-1",
               "apply_start_time":"25-11-25 00:00","apply_end_time":"25-11-28 00:00",
               "start_time":"25-11-26 09:00","end_time":"25-11-26 12:00",
               "content":"物料室整理","score":"3","apply_status_text":"已完成"}
            ]}}
            """.trimIndent(),
        )
        val record = env.data.decodeData<WenquVolunteerData>()!!.list.first()
        assertEquals("某楼A404-1", record.address)
        assertEquals("25-11-25 00:00", record.applyStartTime)
        assertEquals("25-11-28 00:00", record.applyEndTime)
        assertEquals("物料室整理", record.content)
        assertEquals("已完成", record.statusText)
    }

    @Test
    fun `记录可以当活动喂给详情弹层_记录 id 就是活动 id`() {
        // 小程序的 goToDetail 拿记录的 id 去 volunteer_detail?id=，两个接口的 id 是同一个
        val record = WenquVolunteerRecord(
            id = 34658,
            title = "某志愿服务活动",
            unit = "某学院",
            address = "某楼A404-1",
            startTime = "25-11-26 09:00",
            endTime = "25-11-26 12:00",
            applyStartTime = "25-11-25 00:00",
            applyEndTime = "25-11-28 00:00",
            content = "物料室整理",
            score = "3",
            statusText = "已完成",
        )
        val activity = record.asActivity()
        assertEquals(34658L, activity.id)
        assertEquals("某志愿服务活动", activity.title)
        assertEquals("某楼A404-1", activity.address)
        assertEquals("3", activity.score)
        assertEquals("物料室整理", activity.content)
        // 记录视角不映射活动状态：徽章走 apply_status_text，别把「已完成」翻成「已取消 / 已结束」
        assertEquals(WENQU_STATUS_NOT_APPLICABLE, activity.status)
        assertEquals("", WenquRules.statusLabel(activity.status))
    }

    @Test
    fun `自主类记录 id 缺失收成 0_不发注定失败的详情请求`() {
        val env = envelope(
            """{"status":200,"msg":"ok","data":{"total_score":1,"list":[
                 {"id":null,"title":"自主实践","score":"2","apply_status_text":"已完成"}
               ]}}""",
        )
        val record = env.data.decodeData<WenquVolunteerData>()!!.list.first()
        assertEquals(0L, record.id)
        assertEquals(0L, record.asActivity().id)
    }

    @Test
    fun `活动列表解析_状态名额与时间`() {
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"list":[
              {"id":37471,"project_name":"某项目","title":"某志愿服务活动","unit":"某学院",
               "address":"某地","apply_start_time":"26-10-04 12:00","apply_end_time":"26-10-13 12:00",
               "start_time":"26-10-13 12:30","end_time":"26-10-13 16:30",
               "people_num":24,"join_num":24,"status":1,"content":"说明","balance_num":0,"isApply":false}
            ],"qj_count":0,"qx_count":0,"isBlack":false,"stop_apply_reason":""}}
            """.trimIndent(),
        )
        val data = env.data.decodeData<WenquActivityData>()
        assertEquals(1, data?.list?.size)
        val activity = data!!.list.first()
        assertEquals(37471L, activity.id)
        assertEquals("某项目", activity.projectName)
        assertEquals("某志愿服务活动", activity.title)
        assertEquals("某地", activity.address)
        assertEquals(24, activity.peopleNum)
        assertEquals(24, activity.joinNum)
        assertEquals(1, activity.status)
        assertEquals(false, activity.applied)
    }

    @Test
    fun `活动详情解析_工时与群号_群号是裸数字`() {
        // 详情接口的 group_id 平台回**裸数字**（不是字符串），score 同理走宽容串
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"detail":{"id":37471,"title":"x","score":4,
              "group_id":1126956630,"project_name":"p","status":1},
              "apply":null,"qj_count":0,"qx_count":0,"isBlack":false,"stop_apply_reason":""}}
            """.trimIndent(),
        )
        val detail = env.data.decodeData<WenquActivityDetailData>()?.detail
        assertEquals("4", detail?.score)
        assertEquals("1126956630", detail?.groupId)
    }

    @Test
    fun `活动详情缺失时回 null_由调用方给文案`() {
        val env = envelope("""{"status":200,"msg":"ok","data":{"detail":null,"apply":null}}""")
        assertNull(env.data.decodeData<WenquActivityDetailData>()?.detail)
    }

    @Test
    fun `报名限制标记解析_账号级不是活动级`() {
        // isBlack / stop_apply_reason 挂在信封上（每个活动都一样），页面据此挂一条说明
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"list":[],"qj_count":1,"qx_count":2,
              "isBlack":true,"stop_apply_reason":"无故缺席 2 次，已自动拉黑"}}
            """.trimIndent(),
        )
        val data = env.data.decodeData<WenquActivityData>()
        assertTrue(data?.blacklisted == true)
        assertEquals("无故缺席 2 次，已自动拉黑", data?.stopApplyReason)
        assertEquals(2, data?.absentCount)
    }

    @Test
    fun `活动列表与详情都带负责人_考勤人仍然不进模型`() {
        // 与 volunteer/my 同款口径：leader_* 声明（详情要展示），attendance_* 不声明。
        val env = envelope(
            """
            {"status":200,"msg":"ok","data":{"list":[
              {"id":1,"title":"x","leader_name":"张三","leader_phone":"13800000000",
               "attendance_name":"李四","attendance_phone":"13900000000"}
            ]}}
            """.trimIndent(),
        )
        val activity = env.data.decodeData<WenquActivityData>()!!.list.first()
        assertEquals("张三", activity.leaderName)
        assertEquals("13800000000", activity.leaderPhone)
        // 考勤人没声明 → 连 toString 里都不该出现（将来谁把它加回来会立刻红）
        assertFalse(activity.toString().contains("李四"))
        assertFalse(activity.toString().contains("13900000000"))
    }

    @Test
    fun `记录转活动时负责人一并带过去`() {
        // 记录详情在详情接口回来前用记录本身顶着，负责人那一行不该先空一下
        val record = WenquVolunteerRecord(
            id = 1,
            title = "x",
            leaderName = "张三",
            leaderPhone = "13800000000",
        )
        val activity = record.asActivity()
        assertEquals("张三", activity.leaderName)
        assertEquals("13800000000", activity.leaderPhone)
    }
}
