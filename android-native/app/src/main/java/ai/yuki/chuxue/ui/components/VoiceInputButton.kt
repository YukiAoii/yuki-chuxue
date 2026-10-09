package ai.yuki.chuxue.ui.components

import ai.yuki.chuxue.ui.icon.YukiIcons
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 语音输入圆钮 —— 把说的话转成文字，**填进输入框**，然后走原来那条发送路径。
 *
 * ## ⚠️ 为什么是"语音转文字"，不是"语音消息"
 * 这个 App 只有一个上游（DeepSeek），而**它不收音频** —— 录一段声音发出去没有任何消费方。
 * 所以这里做的是**输入**：说 → 文字 → 你还能改 → 再发。录音按钮因此**没有做**
 *（一个按下去不会有结果的按钮，比没有按钮更糟）。
 *
 * ## ⚠️ 设备/ROM 不支持就**什么都不画**
 * 各厂商对 `SpeechRecognizer` 的支持差别很大（有的根本没有识别服务、有的被裁剪过）。
 * 所以进来先问 `isRecognitionAvailable()`，为 false 直接 `return` —— 于是这个按钮
 * 在那些设备上**根本不存在**，而不是"存在但按不动"。
 * （⚠️ 还需要 Manifest 里的 `<queries>` 声明，否则 Android 11+ 上这个判断**恒为 false** 且不报错。）
 *
 * ## ⚠️ 权限是"用时才问"
 * 一个以打字为主的 App 不该在安装时就索要麦克风。所以权限**动态申请**，
 * 用户第一次点语音时才弹 —— 被拒之后每次点都会再问一次（系统自己的节流会接管）。
 *
 * ## ⚠️ 生命周期
 * `SpeechRecognizer` 持有系统服务连接，必须在离开页面时 `destroy()`（见 [DisposableEffect]）——
 * 不释放会一直占着麦克风，而且**不会报错**，只表现为"别的 App 录不了音"。
 *
 * ⚠️ 本机无 adb / 无模拟器：**识别往返与权限流程均未经真机验证**。
 */
@Composable
fun VoiceInputButton(
    enabled: Boolean,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val available = remember(context) {
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    }
    val recognizer = remember(context) {
        if (!available) null
        else runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
    }

    // 不支持 / 创建失败 → 什么都不画（见类注释）
    if (recognizer == null) return

    var listening by remember { mutableStateOf(false) }

    DisposableEffect(recognizer) {
        onDispose { runCatching { recognizer.destroy() } }
    }

    val start: () -> Unit = {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                listening = false
            }

            // ⚠️ 任何错误（没听清 / 超时 / 服务不可用）都只是**安静地收工**：
            // 语音是"锦上添花"的输入方式，失败不该弹一个错误去打断打字的人
            override fun onError(error: Int) {
                listening = false
            }

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                listening = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onResult(text)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            // 跟随系统语言：用户说中文就该按中文识别，不要写死
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        listening = true
        runCatching { recognizer.startListening(intent) }.onFailure { listening = false }
    }

    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) start() }

    RoundIconButton(
        icon = YukiIcons.Mic,
        contentDescription = if (listening) "正在听" else "语音输入",
        enabled = enabled,
        busy = listening,
        onClick = {
            // ⚠️ 实时查权限，**不要**用 remember 缓存的结果：
            // 用户刚授权完、下一轮重组时那个缓存还是 false，会导致"授了权却还要再点一次"
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) start() else askPermission.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = modifier,
    )
}
