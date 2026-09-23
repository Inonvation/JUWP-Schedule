package edu.jxslu.schedule.data.ykt

import edu.jxslu.schedule.domain.YktKeyboard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一卡通登录与取码编排（DESIGN §4.19）。
 *
 * - token **仅内存缓存**（70 天有效期也不落盘，换更小的泄露面）；401 时重登一次；
 * - 无自动重试：失败直接抛分类异常（[YktException]），UI 给对应文案；
 * - 取码每次进页最多一批（反复调用由 UI 层节流，这里不拦）；
 * - 每次登录顺带跑 [YktKeyboard.looksLikeSampleInvariant] 协议自检。
 */
class YktRepository(private val client: YktClient) {

    companion object {
        /** 流水分页大小（服务端尊重 size；100/页时 726 条全量约 8 页，进页首屏 1 页覆盖近 2 个月）。 */
        const val TURNOVER_PAGE_SIZE = 100

        /** 充值 feeitemid（frontInfo.getFrontConfig.recharge，2026-09-21 实测）。 */
        const val FEE_ITEM_ID_RECHARGE = "401"

        /** 微信充值渠道（getpayinfo 的 payList 单条：CAMPUSCARD/「微信充值」payid=63）。 */
        const val PAY_TYPE_ID_WECHAT = "63"
        const val PAY_TYPE_WECHAT = "CAMPUSCARD"
    }

    /** 验证登录可用并返回 token（不缓存）——设置页「开启即验证」与登录共用。 */
    suspend fun loginForToken(username: String, password: String): YktToken {
        val token = doLogin(username, password)
        return YktToken(token, expiresIn = 0)
    }

    /** 取 CARD 账户 + 一批付款码（内部自动登录；[cachedToken] 存在则先试它）。 */
    suspend fun fetchPayCode(
        username: String,
        password: String,
        cachedToken: String? = null,
    ): YktBarcodeData {
        cachedToken?.let { token ->
            val data = tryLoadBarcodes(token)
            if (data != null) return data
        }
        val token = doLogin(username, password)
        return tryLoadBarcodes(token)
            ?: throw YktException.Protocol("登录成功但取码失败（非凭证问题），请稍后重试")
    }

    /** 登录并把 token 塞进内存缓存；返回 token 供上层复用（本对象内的 cached）。 */
    suspend fun login(username: String, password: String): String = doLogin(username, password)

    /** 取一批付款码（token 生命周期完全归本仓库：内存缓存 + 401 重登一次）。 */
    suspend fun payCodes(username: String, password: String): YktBarcodeData {
        cachedToken?.let { token ->
            val data = tryLoadBarcodes(token)
            if (data != null) return data
        }
        val fresh = doLogin(username, password)
        return tryLoadBarcodes(fresh)
            ?: throw YktException.Protocol("登录成功但取码失败（非凭证问题），请稍后重试")
    }

    /** 卡余额列表（DESIGN §4.19）：queryCard → data.card[]；401 重登一次。 */
    suspend fun cards(username: String, password: String): List<YktCard> {
        suspend fun load(t: String): List<YktCard>? {
            val raw = client.get("/berserker-app/ykt/tsm/queryCard", token = t)
            if (raw.httpCode == 401) return null
            val env = parse(raw)
            if (env.code != 200) {
                throw YktException.Protocol("取余额失败：${env.messageOrBlank.ifBlank { env.code.toString() }}")
            }
            return YktModels.cardsFrom(env.data)
        }
        cachedToken?.let { token ->
            load(token)?.let { return it }
        }
        val fresh = doLogin(username, password)
        return load(fresh) ?: throw YktException.Protocol("登录成功但取余额失败")
    }

    /**
     * 电子账户充值目标与余额（`queryCard?scene=recharge` 的 `accinfo[]` 首项，
     * DESIGN §3.10 账户口径）。`second` = 电子账户余额（**单位分**，独立钱包；
     * 2026-09-23 实测 codebarPayinfo 的 ACCOUNT 行只是正式卡镜像，不能当电子账户余额）。
     * 没有电子账户行返回 null。
     */
    suspend fun rechargeAccountDetail(username: String, password: String): Pair<String, Long>? {
        val token = cachedToken ?: doLogin(username, password)
        val raw = client.get("/berserker-app/ykt/tsm/queryCard?scene=recharge", token = token)
        if (raw.httpCode == 401) return null
        val env = parse(raw)
        if (env.code != 200) return null
        return YktModels.electricAccountFrom(env.data)
    }

    /** 电子账户 type（`<account>-000` 形态）；充值下单的 `yktcard` 参数用。 */
    suspend fun electricAccountType(username: String, password: String): String? =
        rechargeAccountDetail(username, password)?.first

    /** 账户列表（`codebarPayinfo` 的 CARD/ACCOUNT 行，DESIGN §3.10 账户口径）。 */
    suspend fun payAccounts(username: String, password: String): List<YktPayAccount> {
        suspend fun load(t: String): List<YktPayAccount>? {
            val raw = client.get("/berserker-app/ykt/tsm/codebarPayinfo", token = t)
            if (raw.httpCode == 401) return null
            val env = parse(raw)
            if (env.code != 200) {
                throw YktException.Protocol("取账户失败：${env.messageOrBlank.ifBlank { env.code.toString() }}")
            }
            return YktModels.payAccountsFrom(env.data)
        }
        cachedToken?.let { token -> load(token)?.let { return it } }
        val fresh = doLogin(username, password)
        return load(fresh) ?: throw YktException.Protocol("登录成功但取账户失败")
    }

    // ------------------------------------------------------------------
    // 充值（DESIGN §4.19「充值」；用户主动触发，无自动充值）
    // ------------------------------------------------------------------

    /**
     * 创建充值订单并返回官方收银台 URL（App 用浏览器打开完成支付）。
     *
     * 链路：`queryCard?scene=recharge` 取卡（**account 是 6 位数字卡号，不是学号**，
     * 2026-09-21 实测——`yktcard` 字段必须用查询返回的 account）→
     * 组表单（`feeitemid=401`、`tranamt` 元浮点、`yktcard=account`…）→
     * [edu.jxslu.schedule.domain.YktRechargeSign.signed] 签名 →
     * `POST /charge/order/thirdOrder` → 302 Location 即收银台 URL。
     *
     * 返回值额外带**付款前基线**（该卡余额与卡号），到账判定要用；取值口径与调用点的
     * 余额展示一致（同一次 queryCard 的 `cardBalanceFen`），见 [YktRechargeStart]。
     *
     * thirdOrder 成功分支的响应形态已实测（302 Location，见下）；同时保留
     * JSON/HTML 兜底。签名密钥/字段是前端公开常量，平台改版会失效——失败统一
     * 给「平台可能已改版」口径。
     */
    suspend fun rechargeCreate(
        username: String,
        password: String,
        /** 金额（元），两位小数内；服务端口径即元，不乘 100。 */
        yuan: String,
        /**
         * 充值目标账户（DESIGN §3.10 账户口径）：`account-000` 形态 = 电子账户；
         * null = 正式卡（`yktcard` 传 6 位卡号，2026-09-21 实测口径）。
         */
        targetAccount: String? = null,
    ): YktRechargeStart {
        val token = cachedToken ?: doLogin(username, password)

        // [1] 充值场景的卡（scene=recharge）：取第一张非挂失卡；account = 6 位卡号
        val cardsRaw = client.get("/berserker-app/ykt/tsm/queryCard?scene=recharge", token = token)
        if (cardsRaw.httpCode == 401) {
            throw YktException.Credential("登录状态已失效，请重新打开本页")
        }
        val cardsEnv = parse(cardsRaw)
        if (cardsEnv.code != 200) {
            throw YktException.Protocol("取卡信息失败：${cardsEnv.messageOrBlank.ifBlank { cardsEnv.code.toString() }}")
        }
        val card = YktModels.cardsFrom(cardsEnv.data).firstOrNull { it.lostflag == null || it.lostflag == "0" }
            ?: throw YktException.Protocol("没有可充值的卡账户（可能已挂失或冻结）")
        val account = card.account
        // 付款前基线随下单结果一起交给上层持久化（到账判定用，见 YktRechargeStart）
        fun started(order: YktRechargeOrder) = YktRechargeStart(
            order = order,
            cardBalanceBeforeFen = card.cardBalanceFen,
            cardAccount = account,
        )

        // [2] 组表单 + 签名（字段与前端 confirm() 一致；appid/密钥是前端公开常量。
        //     appid 业务字段必须带——缺了服务端会 302 到无 orderid 的错误页，2026-09-21 真机实测）
        // 下单统一走 `/blade-pay/pay`（paystep=0，2026-09-23 实测）：
        // thirdOrder 在夜间时段会 500「异常了」（服务端时间闸门），blade-pay 全天可用。
        // 正式卡 yktcard=6位卡号；电子账户 yktcard=accinfo type（<account>-000 形态）。
        // ⚠️ blade-pay 下单**不带 yktcard 字段**（带了支付一步反而报「未获取到要充值的卡号」，
        // 服务端按会话取充值目标——与 thirdOrder 的语义不同）。
        val form = mapOf(
            "feeitemid" to FEE_ITEM_ID_RECHARGE,
            "tranamt" to yuan,
            "flag" to "choose",
            "source" to "app",
            "paystep" to "0",
            "synAccessSource" to "h5",
        )
        val raw = client.postSigned(
            "/blade-pay/pay",
            token,
            edu.jxslu.schedule.domain.YktRechargeSign.signed(form),
            referer = YktClient.BASE + "/payment/",
        )

        // 成功 = JSON 带 orderid（2026-09-23 实测）。302 Location 分支保留作平台改版兜底。
        val location = raw.location
        if (raw.httpCode in 300..399 && location != null) {
            val orderId = orderIdFromUrl(location)
                ?: throw YktException.Protocol(
                    "下单重定向未带订单号（跳转至 ${location.substringBefore('?')}，平台可能已改版）",
                )
            payDirect(orderId, token, yktcard = (targetAccount ?: account))?.let { return started(it) }
            return started(YktRechargeOrder.Cashier(orderId = orderId, cashierUrl = location))
        }
        val orderId = extractOrderId(raw.text, raw.httpCode)
            ?: throw YktException.Protocol(
                if (raw.httpCode in 200..299) "下单未返回订单号（平台可能已改版）"
                else "下单失败（HTTP ${raw.httpCode}，平台可能已改版）",
            )
        // 直拉微信：paystep=2（CAMPUSCARD 渠道免密）→ checkmweb → weixin:// 拉起微信。
        // 服务端按「会话内最近一次下单」取充值目标，**这里不能传 yktcard**（传了报
        // 「未获取到要充值的卡号」，2026-09-23 夜间实测——与白天成功形态一致）。
        //
        // **paystep=2 被服务端拒绝（「未获取到要充值的卡号」等）= 大概率是平台服务时间闸门**
        // （2026-09-23 夜间实测：白天同一请求返回 paysubmit，夜间一律 400）。此时**不降级
        // 跳浏览器**——用户要求：服务时间外就在 App 内提示，不跳网站。
        val payDirectResult = runCatching { payDirect(orderId, token) }
        val payAttempt = payDirectResult.getOrNull()
        if (payAttempt != null) return started(payAttempt)
        // payDirect 失败分两类：
        // - 业务拒绝（Protocol，服务端 msg 如「未获取到要充值的卡号」）= 服务时间闸门，
        //   转 NotInServiceTime，UI 在 App 内提示，**不跳浏览器**（2026-09-23 拍板）；
        // - 网络/结构异常（null 或 Network）：兜底打开收银台。
        when (val err = payDirectResult.exceptionOrNull()) {
            is YktException.Protocol -> throw YktException.NotInServiceTime(
                "当前不在充值服务时间内：${err.message}",
            )

            null -> return started(
                YktRechargeOrder.Cashier(orderId = orderId, cashierUrl = buildCashierUrl(orderId, token)),
            )

            else -> throw err
        }
    }

    /**
     * 直拉微信链路（等效收银台「立即付款」，2026-09-21 实测，全部只发起支付不扣款——
     * 扣款只发生在用户在微信内确认之后）：
     *
     * 1. `POST /blade-pay/pay`（表单 + SIGN + `synjones-auth` **header**，缺 header 报
     *    「未获取到用户信息」）：`paytypeid=63/paytype=CAMPUSCARD/paystep=2/orderid/redirect_url`；
     *    响应 `data.paysubmit` = HTML form，action = `wx.tenpay.com/.../checkmweb?prepay_id=…`；
     * 2. GET checkmweb（**Referer 必须为商户域名**，微信硬校验）→ 200 HTML 中间页；
     * 3. 中间页含 `weixin://wap/pay?prepayid%3D…` → `ACTION_VIEW` 直接拉起微信。
     *
     * 任一环失败返回 null（上层降级打开收银台 URL，不阻断充值）。
     */
    private suspend fun payDirect(orderId: String, token: String, yktcard: String? = null): YktRechargeOrder.WechatPay? {
        // [1] 发起支付（免密渠道；若平台日后开启密码，这一步会报错 → 走收银台兜底）。
        // yktcard 必带：服务端按它定位充值目标（正式卡=6位卡号；电子账户=<account>-000 形态），
        // 缺了报「未获取到要充值的卡号」（2026-09-23 实测）。
        val payForm = buildMap {
            put("paytypeid", PAY_TYPE_ID_WECHAT)
            put("paytype", PAY_TYPE_WECHAT)
            put("paystep", "2")
            put("orderid", orderId)
            yktcard?.let { put("yktcard", it) }
            put("redirect_url", "${YktClient.BASE}/payment/?name=result")
            put("synAccessSource", "h5")
        }
        val payRaw = runCatching {
            client.postFormAuth(
                "/blade-pay/pay",
                edu.jxslu.schedule.domain.YktRechargeSign.signed(payForm),
                referer = YktClient.BASE + "/payment/",
                token = token,
            )
        }.getOrNull() ?: return null   // 网络层失败：交上层走收银台兜底
        val payEnv = runCatching { parse(payRaw) }.getOrNull()
        if (payEnv == null) return null
        if (payEnv.code != 200) {
            // 服务端业务拒绝（夜间时间闸门等）：带原话抛出，上层识别后 App 内提示
            throw YktException.Protocol(
                payEnv.messageOrBlank.ifBlank { "支付被拒绝（code=${payEnv.code}）" },
            )
        }
        val paysubmit = ((payEnv.data as? JsonObject)?.get("paysubmit") as? JsonPrimitive)?.content
            ?: return null
        // [2] checkmweb（Referer=商户域名，微信硬校验；followRedirects(false) 也无妨——中间页是 200 HTML）
        val action = Regex("action=\"([^\"]+)\"").find(paysubmit)?.groupValues?.get(1)
            ?: return null
        val mid = runCatching {
            client.get(
                path = "",
                absoluteUrl = action,
                referer = "${YktClient.BASE}/payment/",
            )
        }.getOrNull() ?: return null
        if (mid.httpCode != 200) return null
        // [3] 中间页抓 weixin:// 拉起链接（HTML 实体未转义，原文即 weixin://wap/pay?prepayid%3D…）
        val wechatUrl = Regex("weixin://wap/pay\\?[^\"'\\s]+").find(mid.text)?.value
            ?: return null
        return YktRechargeOrder.WechatPay(orderId = orderId, wechatUrl = wechatUrl)
    }

    /** 删除未支付订单（用户取消支付时的兜底；失败静默——收银台侧也会超时关闭）。 */
    suspend fun rechargeCancel(orderId: String): Boolean {
        val form = edu.jxslu.schedule.domain.YktRechargeSign.signed(mapOf("orderid" to orderId))
        return runCatching {
            val raw = client.postFormPlain(
                "/charge/order/deleteOrder",
                form,
                referer = YktClient.BASE + "/payment/",
            )
            raw.httpCode in 200..299
        }.getOrDefault(false)
    }

    /** 官方收银台 URL 兜底拼法（正常路径 = 服务端 302 Location 直发，不走这里）。 */
    private fun buildCashierUrl(orderId: String, token: String): String {
        val q = listOf(
            "synjones-auth" to token,
            "orderid" to orderId,
        ).joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        return "${YktClient.BASE}/payment/?$q"
    }

    /** 从 URL（302 Location）提取 orderid 参数。 */
    private fun orderIdFromUrl(url: String): String? =
        Regex("[?&]orderid=([A-Za-z0-9_-]{4,64})").find(url)?.groupValues?.get(1)

    /** 从下单响应提取 orderid：JSON data 三形态 + HTML 兜底。 */
    private fun extractOrderId(text: String, httpCode: Int): String? {
        // JSON 形态
        runCatching { parse(YktClient.Raw(httpCode, text)) }.getOrNull()?.let { env ->
            (env.data as? JsonObject)?.let { d ->
                sequenceOf("orderid", "orderId").forEach { k ->
                    (d[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
                }
                ((d["order"] as? JsonObject))?.let { o ->
                    (o["orderid"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        // HTML 兜底：orderid=xxx / name="orderid" value="xxx"
        Regex("orderid[=:\"'\\s]+([A-Za-z0-9_-]{6,64})").find(text)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * 消费流水分页（DESIGN §4.19）。**不带任何时间参数**——该校后端对
     * `timeFrom`/`timeTo`（campus-card H5 前端发的参数）不识别：单独传被忽略、
     * 组合传直接清零（2026-09-20 实测矩阵），无参 = 按时间倒序的全量流水
     * （`size` 被服务端尊重，实测 100/页、726 条约 10 个月）。月份过滤由上层客户端完成。
     */
    suspend fun turnover(
        username: String,
        password: String,
        page: Int,
        pageSize: Int = TURNOVER_PAGE_SIZE,
    ): YktTurnoverPage {
        suspend fun load(t: String): YktTurnoverPage? {
            val qs = "size=" + pageSize + "&current=" + page
            val raw = client.get("/berserker-search/search/personal/turnover?$qs", token = t)
            if (raw.httpCode == 401) return null
            val env = parse(raw)
            if (env.code != 200) {
                throw YktException.Protocol("取流水失败：${env.messageOrBlank.ifBlank { env.code.toString() }}")
            }
            return YktModels.turnoverPageFrom(env.data)
        }
        cachedToken?.let { token ->
            load(token)?.let { return it }
        }
        val fresh = doLogin(username, password)
        return load(fresh) ?: throw YktException.Protocol("登录成功但取流水失败")
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private val loginMutex = Mutex()

    /** 登录链路：取键盘 → 字形映射 → 构造密文 → OAuth token。全程互斥（防并发双登录）。 */
    private suspend fun doLogin(username: String, password: String): String = loginMutex.withLock {
        // [1] 安全键盘
        val kbRaw = client.get("/berserker-secure/keyboard?type=Number&order=1")
        val kbEnv = parse(kbRaw)
        if (kbEnv.code != 200) throw YktException.Protocol("取安全键盘失败：${kbEnv.messageOrBlank.ifBlank { kbEnv.code.toString() }}")
        val kb = YktModels.keyboardFrom(kbEnv.data)

        // [2] 协议自检：服务端样板 password == kb + "$1$" + uuid（零成本改版探测器）
        if (!YktKeyboard.looksLikeSampleInvariant(kb.numberKeyboard, kb.uuid, kb.samplePassword)) {
            throw YktException.Protocol("登录协议已变更（键盘样板自检不过），请更新 App")
        }

        // [3] 字形映射（未知哈希/非双射在这里硬失败）
        val mapping = YktKeyboard.buildMapping(kb.numberKeyboardImage, kb.numberKeyboard)

        // [4] 密文 + 提交（表单字段与前端 axios 拦截器一致）
        val passwordField = try {
            YktKeyboard.buildPasswordField(password, mapping, kb.uuid)
        } catch (e: IllegalArgumentException) {
            throw YktException.Credential("登录密码只支持数字（安全键盘仅映射 0-9）")
        }
        val form = mapOf(
            "username" to username,
            "password" to passwordField,
            "grant_type" to "password",
            "scope" to "all",
            "loginFrom" to "h5",
            "logintype" to "sno",
            "device_token" to "h5",
            "synAccessSource" to "h5",
        )
        val tokenRaw = client.postForm("/berserker-auth/oauth/token", form)
        val tokenEnv = runCatching { parse(tokenRaw) }.getOrElse { e ->
            // HTTP 400 + 非 JSON 体 = 凭证错（服务端返回 Bad credentials 文本页）
            if (tokenRaw.httpCode == 400) {
                throw YktException.Credential("学号或密码错误")
            }
            throw e
        }
        if (tokenRaw.httpCode == 400) {
            throw YktException.Credential(
                tokenEnv.messageOrBlank.ifBlank { "学号或密码错误" },
            )
        }
        // OAuth2 成功响应的 token 字段在顶层而非 data 里
        val accessToken = YktModels.accessTokenFromTopLevel(tokenRaw.text)
        if (accessToken == null) {
            when (tokenEnv.code) {
                8001 -> throw YktException.MultiAccount(
                    "该学号绑定了多个账号，请先在网页端选择默认账号",
                    tokenEnv.data?.toString(),
                )
                8002, 8003 -> throw YktException.NeedCaptcha(tokenEnv.code)
                else -> throw YktException.Credential(
                    tokenEnv.messageOrBlank.ifBlank { "登录失败（${tokenEnv.code}）" },
                )
            }
        }
        cachedToken = accessToken
        accessToken
    }

    /** 内存 token 缓存（进程级；仅本类写）。 */
    @Volatile
    private var cachedToken: String? = null

    /** 用给定 token 试取码；401/失败返回 null（调用方重登），业务异常照抛。 */
    private suspend fun tryLoadBarcodes(token: String): YktBarcodeData? {
        val accountsRaw = client.get("/berserker-app/ykt/tsm/codebarPayinfo", token = token)
        if (accountsRaw.httpCode == 401) return null
        val accountsEnv = parse(accountsRaw)
        if (accountsEnv.code != 200) return null
        val card = YktModels.payAccountsFrom(accountsEnv.data).firstOrNull { it.code == "CARD" }
            ?: throw YktException.Protocol("没有找到卡账户（CARD），无法取付款码")

        // query 用 urlencode 拼接（口径同 Python 侧 urlencode(params)）。
        // 注意不能用 "path".toHttpUrl()：它要求绝对 URL（带 scheme），相对路径会直接
        // 抛 "Expected URL scheme 'http' or 'https' but no scheme was found"。
        val query = listOf(
            "account" to card.account,
            "payacc" to card.payacc,
            "paytype" to card.paytype,
        ).joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val barcodeRaw = client.get("/berserker-app/ykt/tsm/batchGetBarCodeGet?$query", token = token)
        if (barcodeRaw.httpCode == 401) return null
        val barcodeEnv = parse(barcodeRaw)
        if (barcodeEnv.code != 200) {
            throw YktException.Protocol("取付款码失败：${barcodeEnv.messageOrBlank.ifBlank { barcodeEnv.code.toString() }}")
        }
        val data = YktModels.barcodeFrom(barcodeEnv.data)
            ?: throw YktException.Protocol("付款码响应结构异常")
        if (data.barcode.isEmpty()) {
            throw YktException.Protocol("服务端未返回付款码：${data.errmsg.orEmpty().ifBlank { "无数据" }}")
        }
        return data
    }

    private fun parse(raw: YktClient.Raw): YktEnvelope =
        runCatching { YktModels.parseEnvelope(raw.text) }
            .getOrElse { throw YktException.Protocol("响应不是合法 JSON（HTTP ${raw.httpCode}）") }
}
