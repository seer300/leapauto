package com.leapauto.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.service.quicksettings.TileService
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.leapauto.app.ui.OperationPinDialog
import com.leapauto.app.ui.solidDialogModifier
import com.leapauto.app.ui.theme.LeapAutoTheme
import kotlin.concurrent.thread

/** Brings a user-initiated widget, tile, shortcut or linkage request to the foreground. */
class ControlRequestActivity : ComponentActivity() {
    private var pin by mutableStateOf("")
    private var request by mutableStateOf<ExternalControlRequest?>(null)
    private var shortcutLockCommand by mutableStateOf<String?>(null)
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null) {
            finish()
            return
        }
        val store = SessionStore(this)
        val appearance = store.loadAppearanceMode()
        setContent {
            LeapAutoTheme(darkTheme = appearance.resolvesToDark(isSystemInDarkTheme())) {
                shortcutLockCommand?.let { command ->
                    val label = ShortcutControlPolicy.commandLabel(command)
                    AlertDialog(
                        onDismissRequest = ::finish,
                        modifier = Modifier.solidDialogModifier(),
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        title = { Text("确认控车") },
                        text = { Text("是否执行「$label」？") },
                        confirmButton = {
                            TextButton(onClick = {
                                shortcutLockCommand = null
                                startCommand(command)
                            }) { Text("确定") }
                        },
                        dismissButton = { TextButton(onClick = ::finish) { Text("取消") } }
                    )
                }
                request?.let { pending ->
                    OperationPinDialog(
                        pin = pin,
                        onPinChange = { pin = OperationPin.filterInput(it) },
                        onSubmitPin = { submit(pending) },
                        onCancelPin = ::finish,
                        instruction = if (pending.command == ScheduledAcLinkageReceiver.COMMAND) {
                            "请输入 4 位操控密码，以执行本次定时空调联动。"
                        } else {
                            "请输入 4 位操控密码，以发送本次指令（${Commands.build(pending.command).label}）。"
                        }
                    )
                }
            }
        }
        val notificationRequest = intent.getBundleExtra(EXTRA_REQUEST)?.let(ExternalControlRequest::fromBundle)
        if (notificationRequest != null) {
            showPrompt(notificationRequest)
            return
        }
        val command = intent.getStringExtra(ControlService.EXTRA_COMMAND)
        if (command == null) {
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_FROM_SHORTCUT, false) && command in setOf("lock", "unlock") &&
            com.leapauto.app.bluetooth.BleKeyRuntime.get(this).controller.state.canControl
        ) {
            shortcutLockCommand = command
        } else {
            startCommand(command, allowBluetooth = !intent.getBooleanExtra(EXTRA_FROM_SHORTCUT, false))
        }
    }

    private fun startCommand(command: String, allowBluetooth: Boolean = true) {
        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (isFinishing || isDestroyed) return
                val pending = resultData?.let(ExternalControlRequest::fromBundle)
                if (resultCode == ControlService.SHOW_PIN_PROMPT && pending != null) {
                    showPrompt(pending)
                } else {
                    finish()
                }
            }
        }
        val serviceIntent = Intent(this, ControlService::class.java)
            .putExtra(ControlService.EXTRA_COMMAND, command)
            .putExtra(ControlService.EXTRA_PROMPT_RECEIVER, receiver)
            .putExtra(ControlService.EXTRA_ALLOW_BLUETOOTH, allowBluetooth)
        runCatching { startForegroundService(serviceIntent) }.onFailure {
            showMessage("无法启动控车服务，请重试")
            finish()
        }
    }

    private fun showPrompt(pending: ExternalControlRequest) {
        if (pending.isCurrent(SessionStore(this).load())) {
            request = pending
        } else {
            showMessage("控车请求已失效，请重新发起")
            finish()
        }
    }

    private fun submit(pending: ExternalControlRequest) {
        if (!OperationPin.isValid(pin) || request != pending) return
        val submittedPin = pin
        pin = ""
        request = null
        val appContext = applicationContext
        if (!pending.isCurrent(SessionStore(this).load())) {
            showMessage("控车请求已失效，请重新发起")
        } else if (pending.command == ScheduledAcLinkageReceiver.COMMAND) {
            thread { ScheduledAcLinkageReceiver.execute(appContext, pending, submittedPin) }
        } else {
            runCatching { ControlService.submitPin(this, pending, submittedPin) }
                .onFailure { showMessage("无法启动控车服务，请重试") }
        }
        finish()
    }

    override fun onStop() {
        pin = ""
        super.onStop()
    }

    private fun showMessage(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_FROM_SHORTCUT = "from_shortcut"
        private const val EXTRA_REQUEST = "control_request"
        private const val CHANNEL_ID = "control_pin_prompt"
        private const val NOTIFICATION_ID = 1711

        internal fun commandIntent(context: Context, command: String, fromShortcut: Boolean = false) =
            Intent(context, ControlRequestActivity::class.java)
                .putExtra(ControlService.EXTRA_COMMAND, command)
                .putExtra(EXTRA_FROM_SHORTCUT, fromShortcut)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        @SuppressLint("StartActivityAndCollapseDeprecated")
        internal fun launchFromTile(tile: TileService, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                tile.startActivityAndCollapse(
                    PendingIntent.getActivity(tile, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                tile.startActivityAndCollapse(intent)
            }
        }

        internal fun notifyPrompt(context: Context, pending: ExternalControlRequest) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "控车密码确认", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val intent = Intent(context, ControlRequestActivity::class.java)
                .putExtra(EXTRA_REQUEST, pending.toBundle())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val click = PendingIntent.getActivity(
                context, NOTIFICATION_ID, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("定时空调联动")
                .setContentText("点击输入 4 位操控密码，确认发送本次指令")
                .setContentIntent(click)
                .setTimeoutAfter(ExternalControlRequest.EXPIRY_MS)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
