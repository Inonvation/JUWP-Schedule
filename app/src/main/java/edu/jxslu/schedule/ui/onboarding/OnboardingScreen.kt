package edu.jxslu.schedule.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.session.CasEnsureResult
import edu.jxslu.schedule.data.session.CasSession
import edu.jxslu.schedule.data.session.CredentialVault
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.ui.common.AppCard
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 引导步骤（DESIGN §3.16）。顺序即流程，[Done] 是终点。 */
private enum class Step(val index: Int, val title: String) {
    Welcome(0, "欢迎"),
    Jw(1, "学校统一认证"),
    Ykt(2, "一卡通 · 电费"),
    Qiekj(3, "胖乖开水"),
    Done(4, "完成"),
}

private const val TOTAL_STEPS = 4

/**
 * 首次配置引导（DESIGN §3.16 / §4.27）。
 *
 * 五屏：欢迎 → 学校统一认证 → 一卡通·电费 → 胖乖开水 → 完成。**每一步都能跳过**，
 * 跳过的只是那一步的凭据，不挡后面的步骤，也不挡进主界面。
 *
 * 第 2 步用原生表单收密码，是整个 App 里唯一明确告诉用户「我们会保存这个密码」的地方
 * —— 因为「会话失效后自动续登」必须要有密码才做得到（WebView 登录拿不到密码）。
 */
/**
 * @param startAtJw 直接落在「学校统一认证」那一步。给**重复进入**用：状态卡显示
 *   「登录状态已失效」时点进去就是要改密码，没必要让用户再走一遍欢迎页。
 *   首启由 `MainActivity` 拉起时用默认值（从欢迎页开始）。
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OnboardingScreen(onFinish: () -> Unit, startAtJw: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Graph.displayPrefs(context) }
    val vault = remember { Graph.credentialVault(context) }
    val cas = remember { Graph.casSession(context) }
    val snackbar = remember { SnackbarHostState() }

    var step by remember { mutableStateOf(if (startAtJw) Step.Jw else Step.Welcome) }
    var jwOk by remember { mutableStateOf(false) }
    var yktOk by remember { mutableStateOf(false) }
    var qiekjOk by remember { mutableStateOf(false) }

    /** 完成或跳过都写标记——只有「走完了」才算看过，后面不再打扰。 */
    fun finish() {
        scope.launch {
            prefs.setOnboardingSeen()
            onFinish()
        }
    }

    fun next(target: Step) {
        step = target
    }

    // 返回键 = 跳过本步，**不退出 App**：首启误触返回直接退掉会让人以为程序崩了
    // 直接进来改密码的：返回 = 退出，不是「跳过本步」——用户本来就不在配置流程里
    BackHandler(enabled = !startAtJw && step != Step.Welcome) {
        step = when (step) {
            Step.Jw -> Step.Ykt
            Step.Ykt -> Step.Qiekj
            Step.Qiekj -> Step.Done
            else -> Step.Done
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets.statusBars,
                title = { Text(step.title) },
                actions = {
                    if (step != Step.Done) {
                        TextButton(onClick = { finish() }) { Text("跳过") }
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step != Step.Welcome && step != Step.Done) {
                StepProgress(step)
            }
            when (step) {
                Step.Welcome -> WelcomeStep(onNext = { next(Step.Jw) })
                Step.Jw -> JwStep(
                    cas = cas,
                    vault = vault,
                    scope = scope,
                    // 重复进入（只改密码）时这一步做完就收窗，不再拖着用户走后面几屏
                    onDone = { jwOk = true; if (startAtJw) finish() else next(Step.Ykt) },
                    onSkip = { if (startAtJw) finish() else next(Step.Ykt) },
                )
                Step.Ykt -> YktStep(
                    vault = vault,
                    scope = scope,
                    onDone = { yktOk = true; next(Step.Qiekj) },
                    onSkip = { next(Step.Qiekj) },
                )
                Step.Qiekj -> QiekjStep(
                    scope = scope,
                    onDone = { qiekjOk = true; next(Step.Done) },
                    onSkip = { next(Step.Done) },
                )
                Step.Done -> DoneStep(
                    jwOk = jwOk,
                    yktOk = yktOk,
                    qiekjOk = qiekjOk,
                    onFinish = { finish() },
                )
            }
        }
    }
}

@Composable
private fun StepProgress(step: Step) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "第 ${step.index} 步 / 共 $TOTAL_STEPS 步",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text("欢迎使用水贝贝", style = MaterialTheme.typography.headlineSmall)
    Text(
        "接下来用几步把学校账号配好。配过之后，课表、成绩、一卡通、电费都不用再手动登录。",
        style = MaterialTheme.typography.bodyMedium,
    )
    IntentCard(
        title = "这一步会做什么",
        lines = listOf(
            "· 学号与统一认证密码加密存在本机（Android Keystore），只用于学校系统登录",
            "· 不进入云备份、不上传到任何第三方服务器",
            "· 随时可以在「我的」页退出登录并清除",
        ),
    )
    Text(
        "每一步都可以跳过，之后在「我的」页补上；跳过的功能会提示未配置。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("开始配置") }
}

@Composable
private fun JwStep(
    cas: CasSession,
    vault: CredentialVault,
    scope: CoroutineScope,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }

    fun submit() {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            notice = NoticeFeedback("请填入学号和统一认证密码", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            // 用户重新输入 = 重新开始计数：先清零闸门，避免上一次的失败次数把这次顶掉
            cas.onCredentialsUpdated()
            val result = try {
                cas.tryLogin(user, password)
            } catch (e: Exception) {
                CasEnsureResult.Failed(e.message ?: "登录异常")
            }
            busy = false
            when (result) {
                is CasEnsureResult.Ready -> {
                    vault.saveCas(user, password)
                    // 顺手补学籍卡的姓名 / 班级（DESIGN §3.3）：走到「完成」页时「我的」页
                    // 已经有名字和班级，不用先导一次成绩。失败静默——它是锦上添花，
                    // 不该挡住引导的下一步。
                    runCatching { Graph.profileSync(context).syncOnce(force = true) }
                    onDone()
                }
                CasEnsureResult.Suspended -> notice = NoticeFeedback(
                    "密码连续错误，已暂停自动登录。可以跳过这步，稍后在「我的」页重新填写。",
                    NoticeTone.Error,
                )
                CasEnsureResult.NoCredential -> notice = NoticeFeedback("请填入学号和密码", NoticeTone.Warning)
                is CasEnsureResult.NeedsManualLogin -> notice = NoticeFeedback(
                    "${result.message}。请跳过这步，导入课表时在页面上手动登录一次即可。",
                    NoticeTone.Warning,
                )
                is CasEnsureResult.Failed -> notice = NoticeFeedback(result.message, NoticeTone.Error)
            }
        }
    }

    Text("学校统一认证", style = MaterialTheme.typography.titleMedium)
    Text(
        "教务、学工（报修 / 请假）、盖章成绩单共用这一套账号。",
        style = MaterialTheme.typography.bodyMedium,
    )
    IntentCard(
        title = "为什么要填密码",
        lines = listOf(
            "· 保存后，登录状态过期时 App 会自己重新登录，不用你再输一遍",
            "· 密码加密存本机、不进日志、不进云备份；不填则每次都要手动登录",
        ),
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it.trim() },
        label = { Text("学号") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("统一认证密码") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    notice?.let { InlineNoticeRow(it.text, it.tone) }
    Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "正在验证…" else "验证并保存")
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("先跳过") }
}

@Composable
private fun YktStep(
    vault: CredentialVault,
    scope: CoroutineScope,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var username by remember { mutableStateOf(vault.readCas()?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }

    fun submit() {
        val user = username.trim()
        if (user.isEmpty() || password.isEmpty()) {
            notice = NoticeFeedback("请填入学号和查询密码", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            try {
                // 与「我的 → 校园卡」开启流程同一序列：先真登一次，再查一次账户
                Graph.yktRepository(context).loginForToken(user, password)
                Graph.yktCredentialStore(context).save(user, password)
                Graph.displayPrefs(context).setCampusCardEnabled(true)
                busy = false
                onDone()
            } catch (e: YktException.NeedCaptcha) {
                busy = false
                notice = NoticeFeedback(
                    "平台要求图形验证码：请先在浏览器里登录一次一卡通，再回来重试",
                    NoticeTone.Error,
                )
            } catch (e: YktException.MultiAccount) {
                busy = false
                notice = NoticeFeedback("该学号绑定了多个账号，请先在网页端选择默认账号", NoticeTone.Error)
            } catch (e: Exception) {
                busy = false
                notice = NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
            }
        }
    }

    Text("一卡通 · 寝室电费", style = MaterialTheme.typography.titleMedium)
    Text(
        "余额、付款码、消费流水、寝室电费共用这一份凭证（学号 + 查询密码，不是统一认证密码）。",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it.trim() },
        label = { Text("学号") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // 与「我的 → 校园卡」设置页同一句话：账号是学号，默认密码通常为身份证后六位。
    // 只支持数字是平台约束（一卡通登录走服务端下发的数字安全键盘，见 §4.19）。
    Text(
        "账号为学号，默认密码通常为身份证后六位（仅支持数字密码）。改过就用改后的。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it.filter { c -> c.isDigit() } },
        label = { Text("查询密码") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    notice?.let { InlineNoticeRow(it.text, it.tone) }
    Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(if (busy) "正在验证…" else "验证并开启")
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("先跳过") }
}

@Composable
private fun QiekjStep(
    scope: CoroutineScope,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { Graph.qiekj(context) }
    var phone by remember { mutableStateOf(repo.readPhone().orEmpty()) }
    var code by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    // 两种登录方式二选一：短信验证码（默认）/ 粘贴已有 Token。
    var tokenMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<NoticeFeedback?>(null) }
    // 发送验证码的冷却起点：和开水页同一档 60 秒，避免连点把平台短信额度撞穿。
    var codeSentAt by remember { mutableStateOf(0L) }

    fun sendCode() {
        val p = phone.trim()
        val elapsed = System.currentTimeMillis() - codeSentAt
        if (elapsed < 60_000) {
            notice = NoticeFeedback(
                "验证码已发送，请 ${(60 - elapsed / 1000).toInt()} 秒后再试",
                NoticeTone.Warning,
            )
            return
        }
        if (p.length != 11) {
            notice = NoticeFeedback("请输入 11 位手机号", NoticeTone.Warning)
            return
        }
        sending = true
        scope.launch {
            try {
                repo.sendCode(p)
                codeSentAt = System.currentTimeMillis()
                notice = NoticeFeedback("验证码已发送", NoticeTone.Success)
            } catch (e: Exception) {
                notice = NoticeFeedback(e.message ?: "验证码发送失败", NoticeTone.Error)
            }
            sending = false
        }
    }

    fun submitPhone() {
        val p = phone.trim()
        if (p.length != 11 || code.isBlank()) {
            notice = NoticeFeedback("请填手机号和验证码", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            try {
                repo.login(p, code)
                // 记住手机号，下次进来直接填好（与开水页登录成功后的处理一致）
                repo.savePhone(p)
                repo.queryBalance()
                busy = false
                onDone()
            } catch (e: Exception) {
                busy = false
                notice = NoticeFeedback(e.message ?: "登录失败", NoticeTone.Error)
            }
        }
    }

    fun submitToken() {
        val token = tokenInput.trim()
        if (token.isBlank()) {
            notice = NoticeFeedback("请输入 Token", NoticeTone.Warning)
            return
        }
        busy = true
        scope.launch {
            try {
                // 与开水页同一序列：先落盘再查一次余额，余额查得通即 token 有效
                repo.saveToken(token)
                repo.validateToken()
                busy = false
                onDone()
            } catch (e: Exception) {
                // 校验没过就把刚存进去的清掉，否则会留一个无效 token，后续请求一路 401
                runCatching { repo.logout() }
                busy = false
                notice = NoticeFeedback(e.message ?: "Token 无效或已过期", NoticeTone.Error)
            }
        }
    }

    Text("胖乖开水", style = MaterialTheme.typography.titleMedium)
    if (tokenMode) {
        Text(
            "粘贴已从其他渠道拿到的 Token 即可，不用再收短信。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "手机号登录会使旧 Token 失效；两者选一个就行。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        notice?.let { InlineNoticeRow(it.text, it.tone) }
        Button(onClick = { submitToken() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "正在校验…" else "验证并保存")
        }
        OutlinedButton(
            onClick = { tokenMode = false; notice = null },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("改用手机号登录") }
    } else {
        Text(
            "开水用手机号 + 短信验证码登录，和学校账号无关。登录一次之后会记住，token 过期时才需要再收一次短信。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() }.take(11) },
            label = { Text("手机号") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() } },
                label = { Text("验证码") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { sendCode() },
                enabled = !sending && phone.length == 11,
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                Text(if (sending) "发送中" else "发送验证码")
            }
        }
        notice?.let { InlineNoticeRow(it.text, it.tone) }
        Button(onClick = { submitPhone() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "正在登录…" else "登录并保存")
        }
        OutlinedButton(
            onClick = { tokenMode = true; notice = null },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Token 登录") }
    }
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("先跳过") }
}

@Composable
private fun DoneStep(
    jwOk: Boolean,
    yktOk: Boolean,
    qiekjOk: Boolean,
    onFinish: () -> Unit,
) {
    Text("配置完成", style = MaterialTheme.typography.headlineSmall)
    IntentCard(
        title = "当前状态",
        lines = listOf(
            "· 学校统一认证：${if (jwOk) "已配置" else "未配置（导入课表时手动登录）"}",
            "· 一卡通 · 电费：${if (yktOk) "已配置" else "未配置"}",
            "· 胖乖开水：${if (qiekjOk) "已配置" else "未配置"}",
        ),
    )
    Text(
        "没配置的项随时能在「我的」页补上，那里也能看到每个平台的登录状态。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("进入水贝贝") }
}

/** 说明卡：一段标题 + 若干行，引导里反复用到的形态。 */
@Composable
private fun IntentCard(title: String, lines: List<String>) {
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}
