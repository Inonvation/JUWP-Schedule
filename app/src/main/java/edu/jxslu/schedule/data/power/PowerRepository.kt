package edu.jxslu.schedule.data.power

import edu.jxslu.schedule.data.session.LoginTarget
import edu.jxslu.schedule.data.session.SessionStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 电费读表与流水编排（DESIGN §4.24）。
 *
 * - 凭证复用一卡通的 `YktCredentialStore`（学号 + 查询密码；2026-09-23 实测两个平台同一密码）；
 * - token **仅内存缓存**（3599 秒有效期也不落盘），业务码 401 时重登一次；
 * - 无自动重试：失败直接抛分类异常（[PowerException]），UI 给对应文案。
 */
class PowerRepository(
    private val client: PowerClient,
    /**
     * 读数落库（DESIGN §3.13「用电统计」）。**这里是全 App 唯一的写入点**：
     * 只有真的打了一次平台拿到新读数才记，走内存缓存的那次不记（同一份快照重复交出，
     * 落库也会被 `(epochMs, roomId)` 唯一索引挡掉）。
     */
    private val readingStore: PowerReadingStore,
) {

    private var cachedToken: String? = null

    /** 读数缓存（项目详情 + 电表读数）。 */
    private var cachedSnapshot: PowerSnapshot? = null
    private var cachedSnapshotAtMs = 0L

    /** 流水缓存。 */
    private var cachedHistory: List<PowerTurnover>? = null
    private var cachedHistoryAtMs = 0L

    /** 登录并换回 access_token（供深链复用）。 */
    suspend fun login(username: String, password: String): String {
        val raw = client.login(username, password)
        // 401 = 学号或查询密码不对 → 标记平台失效（DESIGN §3.16）。
        // 与 token 过期区分开：那个在 [withToken] 里重登一次，属正常轮换，标了会让
        // 状态卡在每次 token 过期时闪一下「已失效」。
        if (raw.httpCode == 401) credentialFailure("学号或查询密码不对")
        if (raw.httpCode != 200) throw PowerException.Protocol("登录失败：HTTP ${raw.httpCode}")
        val token = PowerModels.parseToken(raw.text)
            ?: throw PowerException.Protocol("登录响应里没有 access_token")
        cachedToken = token
        // 电费与一卡通共用一份凭证，所以清的是同一个平台的标记
        SessionStatus.clearSuspended(LoginTarget.Ykt)
        return token
    }

    /** 凭证不对：标记失效并抛出。电费与一卡通是同一份凭证、同一个状态行。 */
    private fun credentialFailure(message: String): Nothing {
        SessionStatus.markSuspended(LoginTarget.Ykt)
        throw PowerException.Credential(message)
    }

    /**
     * 一次取数：项目详情（含绑定房间）+ 该房间电表读数。
     *
     * [force] 为 false 时先看内存缓存（TTL 见 [CACHE_TTL_MS]）：生活页进页那条路走缓存，
     * 来回切 Tab 不会重复打平台；用户点卡片、充值成功后刷新一律传 true。
     */
    suspend fun snapshot(
        username: String,
        password: String,
        force: Boolean = false,
        source: String = PowerReadingSource.LIFE,
    ): PowerSnapshot {
        if (!force) {
            cachedSnapshot?.takeIf { isFresh(cachedSnapshotAtMs) }?.let { return it }
        }
        val fresh = withToken(username, password) { token ->
            val detail = client.get(
                path = "/charge/feeitem/singleFeeitem",
                token = token,
                params = mapOf("feeitemid" to PowerModels.RECHARGE_FEE_ITEM_ID.toString()),
            )
            val feeItem = PowerModels.parseFeeItem(expectOk(detail, "取电费项目详情"))
            PowerSnapshot(feeItem, readMeter(token, feeItem))
        }
        // 读数落库失败不该影响页面（统计少一条而已，下一次读数会补上）
        runCatching { readingStore.record(fresh.meter, fresh.feeItem.priceYuan, source) }
        cachedSnapshot = fresh
        cachedSnapshotAtMs = System.currentTimeMillis()
        return fresh
    }

    /**
     * 电费流水（充值/退款，按时间升序）。
     *
     * 缓存口径同 [snapshot]：生活页「最近流水」与缴费账单页共用这一份，
     * 从生活页点进账单页不会再多打一条。
     */
    suspend fun history(
        username: String,
        password: String,
        force: Boolean = false,
    ): List<PowerTurnover> {
        if (!force) {
            cachedHistory?.takeIf { isFresh(cachedHistoryAtMs) }?.let { return it }
        }
        val fresh = withToken(username, password) { token ->
            val raw = client.get(
                path = "/charge/turnover/personal_data",
                token = token,
                params = mapOf(
                    "feeitemid" to PowerModels.RECHARGE_FEE_ITEM_ID.toString(),
                    "flag" to "3",
                ),
            )
            PowerModels.parseTurnovers(expectOk(raw, "取电费流水"))
        }
        cachedHistory = fresh
        cachedHistoryAtMs = System.currentTimeMillis()
        return fresh
    }

    /**
     * 「电费充值」深链：平台前端按 URL 里的 `token` 直接登录，落在房间电费缴费页，
     * 支付在网页里完成（一期口径，DESIGN §3.13）。
     */
    suspend fun payPageUrl(username: String, password: String): String {
        val token = cachedToken ?: login(username, password)
        return PowerClient.payPageUrl(token, PowerModels.RECHARGE_FEE_ITEM_ID)
    }

    /** 「缴费账单」深链：同一平台的账单页（按月总支出）。 */
    suspend fun billPageUrl(username: String, password: String): String {
        val token = cachedToken ?: login(username, password)
        return PowerClient.billPageUrl(token)
    }

    /**
     * 电费下单（DESIGN §4.24「电费充值」，2026-09-23 实测）。
     * `feeitemid=181 + tranamt + paystep=0`，签名口径见 [PowerPaySign]。
     * 返回订单号与支付有效期；渠道列表用 [channels] 单独取（`payList` 也随下单返回）。
     */
    suspend fun createOrder(username: String, password: String, yuan: String): PowerOrder =
        withToken(username, password) { token ->
            val raw = client.postSigned(
                "/blade-pay/pay",
                token,
                PowerPaySign.signed(
                    mapOf(
                        "feeitemid" to PowerModels.RECHARGE_FEE_ITEM_ID.toString(),
                        "tranamt" to yuan,
                        "flag" to "choose",
                        "source" to "app",
                        "paystep" to "0",
                        "synAccessSource" to "h5",
                    ),
                ),
            )
            expectOk(raw, "电费下单")
            PowerPayModels.orderFrom(raw.text)
                ?: throw PowerException.Protocol("下单响应里没有 orderid（平台可能已改版）")
        }

    /**
     * 清理全部未支付订单（DESIGN §4.24，2026-09-24；2026-09-24 改为「打开充值弹层时」调用）。
     *
     * 平台**不自动清** `status=0` 的过期单（实测 105 条调试残留一直挂着），同项目未支付单
     * 堆积会让**新下单 500「未知异常」**——这是用户报「余额充足却建不了单」的根因。流程：
     * `GET /charge/order/personal_data?paystatus=0`（只列未支付）→
     * 逐单 `POST /charge/order/deleteOrder`（**JSON body**，表单一律 500）→ 返回成功数。
     *
     * **触发点只有一个**（2026-09-24 收口）：`LifeViewModel.preparePowerRecharge()`——
     * 用户点「电费充值」打开弹层时清一次；下单前会等这次清理结束。未支付单只可能由本流程
     * 产生，所以这一个点足够，不需要刷新时清、也不做后台轮询与 12 小时闸门（一次清理是
     * 1 + N 条请求，多一个触发点就是多一串对第三方平台的请求）。
     */
    suspend fun cancelAllPendingOrders(username: String, password: String): Int =
        withToken(username, password) { token ->
            val listRaw = client.get("/charge/order/personal_data", token, mapOf("paystatus" to "0"))
            val ids = PowerModels.parsePendingOrderIds(expectOk(listRaw, "取未支付订单"))
            ids.count { orderId ->
                runCatching {
                    val delRaw = client.postJson(
                        "/charge/order/deleteOrder",
                        token,
                        buildJsonObject { put("orderid", JsonPrimitive(orderId)) },
                    )
                    PowerModels.codeOf(delRaw.text) == 200
                }.getOrDefault(false)
            }
        }

    /**
     * 农行支付第一步（DESIGN §4.24「农行支付」，2026-09-28 实测打通）：
     * `paystep=2` + 农行渠道 → 服务端回一段自动提交的收银台表单，从中取**手机版**支付链接。
     *
     * 链接由 `PowerBankPayActivity` 的**内嵌 WebView** 打开（2026-09-28 用户拍板）：
     * 页面里就是用户熟悉的那套——手机号 → 短信验证码 → 支付密码。卡号、手机号、验证码、
     * 密码全部只进农行页面，App 不内嵌表单、不读输入、不落盘。
     *
     * **[PowerClient.MOBILE_CASHIER_UA] 不能改**：平台按 UA 分三支，只有微信 UA 给手机版
     * 收银台（`mobile.abchina.com/mpaynew/mpay/index`）；桌面 UA 给 PC 版网页收银台
     * （手机上会被裁、还提示装安全控件），手机浏览器 UA 直接回空的 `code=6230`
     * （`msg` 却写「处理成功」）。
     *
     * 链接里的 TOKEN 是农行侧**一次性会话**，每次要用都重新调这个方法取新的（重开支付页
     * 也走它，别缓存复用）。
     */
    suspend fun bankCashier(username: String, password: String, orderId: String): String =
        withToken(username, password) { token ->
            val raw = client.postSigned(
                "/blade-pay/pay",
                token,
                PowerPaySign.signed(
                    mapOf(
                        "orderid" to orderId,
                        "paystep" to "2",
                        "paytype" to PowerPayChannels.ABC_CODE,
                        "paytypeid" to PowerPayChannels.ABC_ID,
                        "synAccessSource" to "h5",
                    ),
                ),
                userAgent = PowerClient.MOBILE_CASHIER_UA,
            )
            // 6230 = 平台那条空分支（`msg` 却写「处理成功」）：换成人话再抛，别让用户
            // 拿着「处理成功」的原文去猜哪里成功了（本请求已固定微信 UA，走到这里说明
            // 平台又改了分支规则，2026-09-28 实测记录见 PowerClient.MOBILE_CASHIER_UA）。
            if (PowerPayModels.codeOf(raw.text) == 6230) {
                throw PowerException.Protocol("平台没返回收银台链接（code=6230），请稍后重试")
            }
            expectOk(raw, "取农行收银台链接")
            PowerPayModels.bankCashierUrlFrom(raw.text)
                ?: throw PowerException.Protocol("农行收银台链接没拿到（平台可能已改版）")
        }

    /**
     * 第一步支付：`paystep=2` + 电子账户渠道 → 服务端返回 `passwordMap`
     * （键 = uuid，值 = 乱序数字串）与 `ccctype`。UI 用它渲染密码键盘。
     */
    suspend fun payChallenge(
        username: String,
        password: String,
        orderId: String,
    ): PowerPayChallenge = withToken(username, password) { token ->
        val raw = client.postSigned(
            "/blade-pay/pay",
            token,
            PowerPaySign.signed(
                mapOf(
                    "orderid" to orderId,
                    "paystep" to "2",
                    "paytype" to PowerPayChannels.ACCOUNT_CODE,
                    "paytypeid" to PowerPayChannels.ACCOUNT_ID,
                    "synAccessSource" to "h5",
                ),
            ),
        )
        expectOk(raw, "发起电子账户支付")
        // 订单号用下单时那一个：paystep=2 响应里的 orderid 恒为 null（2026-09-24 实测）
        PowerPayModels.challengeFrom(raw.text, requestedOrderId = orderId)
            ?: throw PowerException.Protocol("支付响应里没有 passwordMap（平台可能已改版）")
    }

    /**
     * 第二步支付：带 6 位密文（`passwordMap[uuid][i]` 拼接）+ uuid + ccctype。
     * `code=200` = 受理成功（由查单确认 status=1）；密码错返回 [PowerPayResult.Rejected]。
     *
     * [orderId] 优先于 [challenge] 里的订单号（VM 手上那个是下单时服务端给的，最可信）。
     */
    suspend fun payConfirm(
        username: String,
        password: String,
        challenge: PowerPayChallenge,
        /** 6 位密文（已按 `passwordMap[uuid]` 乱序表替换，见 [PowerPayChallenge.cipherOf]。）。 */
        cipher: String,
        orderId: String? = null,
    ): PowerPayResult = withToken(username, password) { token ->
        val uuid = challenge.passwordMap.keys.firstOrNull()
            ?: throw PowerException.Protocol("支付响应缺 uuid")
        val targetOrderId = orderId?.takeIf { it.isNotBlank() }
            ?: challenge.orderId.takeIf { it.isNotBlank() }
            ?: throw PowerException.Protocol("支付会话缺订单号，请重新下单")
        val form = buildMap {
            put("orderid", targetOrderId)
            put("paystep", "2")
            put("paytype", PowerPayChannels.ACCOUNT_CODE)
            put("paytypeid", PowerPayChannels.ACCOUNT_ID)
            put("password", cipher)
            put("uuid", uuid)
            challenge.accountType?.let { put("ccctype", it) }
            put("synAccessSource", "h5")
        }
        val raw = client.postSigned("/blade-pay/pay", token, PowerPaySign.signed(form))
        val code = PowerPayModels.codeOf(raw.text)
        when (code) {
            200 -> PowerPayResult.Accepted
            else -> PowerPayResult.Rejected(
                PowerPayModels.messageOf(raw.text) ?: "支付失败（code=${code ?: raw.httpCode}）",
            )
        }
    }

    /** 查单（`order.status`：0 待支付 / 1 已完成）。 */
    suspend fun orderStatus(username: String, password: String, orderId: String): Int? =
        withToken(username, password) { token ->
            val raw = client.get(
                "/charge/pay/getpayinfo",
                token,
                mapOf("orderid" to orderId, "userAgent" to "android"),
            )
            expectOk(raw, "查电费订单")
            PowerPayModels.orderStatusFrom(raw.text)
        }

    /** 读电表：`feeitemid` / `type=IEC` / `level` / 场景三键缺一不可（缺了平台只回 500 未知异常）。 */
    private suspend fun readMeter(token: String, feeItem: PowerFeeItem): PowerMeter {
        val scene = feeItem.scene
        if (scene.isEmpty() || feeItem.room == null) {
            throw PowerException.Protocol("项目没给绑定房间，读不了表")
        }
        val form = mutableMapOf(
            "feeitemid" to feeItem.id.toString(),
            "type" to "IEC",
            "level" to scene.size.toString(),
        )
        scene.forEach { form[it.code] = it.id }
        val raw = client.postForm("/charge/feeitem/getThirdData", token, form)
        return PowerModels.parseMeter(expectOk(raw, "读电表"), System.currentTimeMillis())
    }

    /** 业务码 401 = token 过期：重登一次再跑（只重试一次，再失败就抛出去）。 */
    private suspend fun <T> withToken(
        username: String,
        password: String,
        block: suspend (String) -> T,
    ): T {
        val token = cachedToken ?: login(username, password)
        return try {
            block(token)
        } catch (e: PowerException.Credential) {
            block(login(username, password))
        }
    }

    /** HTTP 与业务码双关：401 归凭证类，其余非 200 归协议类。 */
    private fun expectOk(raw: PowerClient.Raw, what: String): String {
        if (raw.httpCode == 401) throw PowerException.Credential("登录状态已失效")
        if (raw.httpCode != 200) throw PowerException.Protocol("$what：HTTP ${raw.httpCode}")
        val code = PowerModels.codeOf(raw.text)
        if (code == 401) throw PowerException.Credential("登录状态已失效")
        if (code != 200) {
            val msg = PowerModels.messageOf(raw.text).orEmpty()
            throw PowerException.Protocol("$what：code=$code $msg".trim())
        }
        return raw.text
    }

    /** 缓存是否还在 TTL 内。 */
    private fun isFresh(cachedAtMs: Long): Boolean =
        cachedAtMs > 0 && System.currentTimeMillis() - cachedAtMs <= CACHE_TTL_MS

    companion object {
        /** 读数与流水的内存缓存时长（DESIGN §4.24「请求节流」）。 */
        private const val CACHE_TTL_MS = 120_000L
    }
}
