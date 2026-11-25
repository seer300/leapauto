package com.leapauto.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/** 桌面插件控车：前台服务 + 后台线程执行，结果通过通知反馈。 */
class ControlService : Service() {

    companion object {
        const val EXTRA_COMMAND = "command"
        private const val CHANNEL_ID = "widget_control"
        private const val FGS_NOTIF_ID = 1001
        private const val RESULT_NOTIF_ID = 1002
        // 1s initial delay + ten 500ms intervals gives the vehicle telemetry
        // endpoint a six-second bounded window to reflect a trunk command.
        private const val TRUNK_TELEMETRY_MAX_ATTEMPTS = 11

        @Volatile private var lastSensitiveCommand: String? = null
        @Volatile private var lastSensitiveEpochMs: Long = 0L

        internal const val EXTRA_ALLOW_BLUETOOTH = "allow_bluetooth"
        internal const val EXTRA_PROMPT_RECEIVER = "prompt_receiver"
        internal const val SHOW_PIN_PROMPT = 1
        private const val EXTRA_SUBMISSION_ID = "submission_id"
        private val pendingPin = java.util.concurrent.atomic.AtomicReference<PinSubmission?>()

        private data class PinSubmission(val id: String, val request: ExternalControlRequest, val pin: String)

        internal fun submitPin(context: android.content.Context, request: ExternalControlRequest, pin: String) {
            require(OperationPin.isValid(pin))
            val submission = PinSubmission(java.util.UUID.randomUUID().toString(), request, pin)
            check(pendingPin.compareAndSet(null, submission))
            val intent = Intent(context, ControlService::class.java).putExtra(EXTRA_SUBMISSION_ID, submission.id)
            try {
                context.startForegroundService(intent)
            } catch (error: Exception) {
                pendingPin.compareAndSet(submission, null)
                throw error
            }
            val submissionId = submission.id
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                pendingPin.get()?.takeIf { it.id == submissionId }?.let { pendingPin.compareAndSet(it, null) }
            }, ExternalControlRequest.EXPIRY_MS)
        }

        private val commandInFlight = AtomicBoolean(false)
        fun isCommandInFlight(): Boolean = commandInFlight.get()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "桌面插件控车", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FGS_NOTIF_ID, notification("控车中..."))
        @Suppress("DEPRECATION")
        val promptReceiver = intent?.getParcelableExtra<android.os.ResultReceiver>(EXTRA_PROMPT_RECEIVER)
        val allowBluetooth = intent?.getBooleanExtra(EXTRA_ALLOW_BLUETOOTH, false) == true
        val submissionId = intent?.getStringExtra(EXTRA_SUBMISSION_ID)
        val submission = pendingPin.get()?.takeIf { submissionId == it.id &&
            pendingPin.compareAndSet(it, null) }
        val command = if (submissionId != null) submission?.request?.command else intent?.getStringExtra(EXTRA_COMMAND)
        if (command == null) {
            promptReceiver?.send(0, null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // 方案 A：桌面小组件高敏感操作 3 秒双击确认防误触机制
        val store = SessionStore(this)
        val verificationEnabled = store.loadWidgetSensitiveActionVerificationEnabled()
        if (submission == null && WidgetControlSecurity.requiresVerification(command, verificationEnabled)) {
            val now = System.currentTimeMillis()
            val confirmed = WidgetControlSecurity.isDoubleClickConfirmed(
                lastCommand = lastSensitiveCommand,
                lastEpochMs = lastSensitiveEpochMs,
                currentCommand = command,
                currentEpochMs = now
            )
            if (!confirmed) {
                lastSensitiveCommand = command
                lastSensitiveEpochMs = now
                val commandLabel = when (command) {
                    "trunkOpen" -> "开启后备箱"
                    else -> "控车"
                }
                val hint = "${WidgetControlSecurity.HINT_DOUBLE_CLICK_PREFIX}$commandLabel"
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this@ControlService, hint, android.widget.Toast.LENGTH_SHORT).show()
                }
                store.setWidgetBusyCommand(null)
                ControlWidget.showControlStatus(this, hint)
                promptReceiver?.send(0, null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            } else {
                lastSensitiveCommand = null
                lastSensitiveEpochMs = 0L
            }
        }

        if (!commandInFlight.compareAndSet(false, true)) {
            store.setWidgetBusyCommand(null)
            ControlWidget.showControlStatus(this, "已有控车指令执行中")
            notifyResult("已有控车指令执行中")
            promptReceiver?.send(0, null)
            return START_NOT_STICKY
        }
        // 按钮已具备忙碌转圈动效，执行过程中不再展示“控车中…”文本覆盖，保持桌面时间与车内温度常驻
        Thread {
            val store = SessionStore(this)
            var pinRequest: ExternalControlRequest? = null
            try {
                val session = store.load()
                val snapshot = store.loadWidgetSnapshot(session.selectedVin)
                when (store.widgetAccess(snapshot, session)) {
                    SessionStore.WidgetAccess.CONTROL -> Unit
                    SessionStore.WidgetAccess.NO_SESSION -> throw ApiException("请打开 App 登录")
                }
                if (session.oldAuth == null || session.newAuth == null) {
                    throw ApiException("未登录，请先打开 App 登录")
                }
                val isWinOpen = snapshot?.windowOpen == true
                val effectiveCommand = if (submission == null) WidgetWindowTogglePolicy.resolveCommand(command, isWinOpen) else command
                val isLockCmd = effectiveCommand == "lock" || effectiveCommand == "unlock"
                val bleRuntime = com.leapauto.app.bluetooth.BleKeyRuntime.get(this)
                val bleController = bleRuntime.controller
                if (submission == null && allowBluetooth && isLockCmd && bleController.state.canControl) {
                    store.setWidgetBusyCommand(effectiveCommand)
                    ControlWidget.refreshData(this)
                    val isLock = effectiveCommand == "lock"
                    val action = if (isLock) com.leapauto.app.bluetooth.BleLockAction.LOCK else com.leapauto.app.bluetooth.BleLockAction.UNLOCK
                    val appContext = applicationContext
                    val started = java.util.concurrent.FutureTask {
                        val before = bleController.state
                        bleController.control(action)
                        bleController.state !== before && bleController.state.pendingAction == action
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post(started)
                    val sent = try {
                        started.get(1500L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: java.util.concurrent.TimeoutException) {
                        started.cancel(false)
                        false
                    }

                    // 等待车端蓝牙物理回执确认 (车端通常在 100ms 内极速回执，最多等待 1500ms)
                    var bleConfirmed = false
                    val startWait = System.currentTimeMillis()
                    while (sent && System.currentTimeMillis() - startWait < 1500L) {
                        Thread.sleep(50L)
                        val currentState = bleController.state
                        if (currentState.confirmedAction == action) {
                            bleConfirmed = true
                            break
                        }
                        if (currentState.phase == com.leapauto.app.bluetooth.BleConnectionPhase.FAILED) {
                            break
                        }
                    }

                    if (bleConfirmed) {
                        store.updateWidgetLockState(session.selectedVin, locked = isLock)
                        if (!isLock) {
                            com.leapauto.app.trip.TripRecordStore(appContext).markUnlockDeparture(session.selectedVin)
                        }
                        com.leapauto.app.tiles.TilePromptHelper.requestTilesUpdate(appContext)
                        val text = if (isLock) "蓝牙上锁成功" else "蓝牙解锁成功"
                        ControlWidget.showControlStatus(appContext, text, locked = isLock)
                        notifyResult(text)
                        // 异步静默上报蓝牙控锁记录 (对齐官方 uploadRecords 审计上报)
                        Thread {
                            try {
                                LeapmotorApi(session).uploadBluetoothRecord(action)
                            } catch (_: Exception) {}
                        }.start()
                        // 阶梯轮询 (1.2s ➔ 2.0s ➔ 2.5s) 拉取车身 1298 物理门锁信号完成闭环
                        listOf(1200L, 2000L, 2500L).forEach { delayMs ->
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                ControlWidget.enqueueCommandSync(appContext)
                            }, delayMs)
                        }
                        if (isLock) {
                            Thread {
                                try {
                                    Thread.sleep(ParkingAnomalyPolicy.POST_LOCK_CHECK_DELAY_MS)
                                    val currentSession = store.load()
                                    if (currentSession.oldAuth != null && currentSession.selectedVin == session.selectedVin) {
                                        val api = LeapmotorApi(session)
                                        val latest = api.getVehicleState()
                                        ParkingAnomalyNotificationManager.notifyIfNeeded(
                                            appContext,
                                            store,
                                            session.selectedCarType,
                                            latest
                                        )
                                    }
                                } catch (_: Exception) {}
                            }.start()
                        }
                        return@Thread
                    }
                }
                if (submission == null) {
                    pinRequest = ExternalControlRequest.create(session, effectiveCommand)
                    return@Thread
                }
                if (!submission.request.isCurrent(session)) throw ApiException("控车请求已失效，请重新发起")
                store.setWidgetBusyCommand(effectiveCommand)
                ControlWidget.refreshData(this)
                val pin = submission.pin
                val api = LeapmotorApi(session)
                val cmd = Commands.build(effectiveCommand)
                val result = api.sendControl(cmd, pin)
                store.save(session)
                var text = WidgetControlResultText.accepted(effectiveCommand)
                // A successful POST without a result message ID is not vehicle-side confirmation.
                var executionConfirmed = false
                if (result.msgID.isNotBlank()) {
                    for (i in 0 until ControlResultPollingPolicy.widgetMaxAttempts) {
                        Thread.sleep(ControlResultPollingPolicy.delayBeforeAttempt(i))
                        val resp = api.queryControlResult(result.msgID)
                        store.save(session)
                        if (resp.opt("result")?.toString() == "0") {
                            executionConfirmed = true
                            val state = resp.optString("state", resp.optString("status", ""))
                            text = WidgetControlResultText.completed(effectiveCommand, state)
                            break
                        }
                    }
                }
                var commandAccepted = result.msgID.isBlank() || executionConfirmed
                if (commandAccepted && (command == "trunkOpen" || command == "trunkClose")) {
                    val targetState = if (command == "trunkOpen") TrunkState.OPEN else TrunkState.CLOSED
                    val telemetryState = refreshTrunkStateUntilTarget(store, session, targetState)
                    if (telemetryState == targetState) {
                        executionConfirmed = true
                        text = WidgetControlResultText.completed(command)
                    } else {
                        // A result poll only means the gateway accepted the command;
                        // do not claim completion until the vehicle telemetry agrees.
                        executionConfirmed = false
                        commandAccepted = false
                        text = WidgetControlResultText.awaiting(command)
                        // The vehicle may publish the new trunk state just after
                        // this bounded confirmation window. Let the widget worker
                        // perform one independent follow-up network refresh.
                        ControlWidget.enqueueCommandSync(this)
                    }
                }
                if (command == "sentryOn" || command == "sentryOff") {
                    val targetEnabled = requireNotNull(SentryModeControlPolicy.targetEnabled(command))
                    val telemetryEnabled = refreshSentryStateUntilTarget(store, session, targetEnabled)
                    if (SentryModeControlPolicy.isConfirmed(targetEnabled, telemetryEnabled)) {
                        executionConfirmed = true
                        text = WidgetControlResultText.completed(command)
                    } else {
                        // Same policy as the app main screen: a result poll is not
                        // vehicle-side confirmation; wait for telemetry agreement.
                        executionConfirmed = false
                        commandAccepted = false
                        text = WidgetControlResultText.awaiting(command)
                        ControlWidget.enqueueCommandSync(this)
                    }
                }
                val confirmedLockedState = when (effectiveCommand) {
                    "lock" -> true
                    "unlock" -> {
                        com.leapauto.app.trip.TripRecordStore(this).markUnlockDeparture(session.selectedVin)
                        false
                    }
                    else -> null
                }
                if (confirmedLockedState != null) {
                    store.updateWidgetLockState(session.selectedVin, locked = confirmedLockedState)
                    com.leapauto.app.tiles.TilePromptHelper.requestTilesUpdate(this)
                }
                val confirmedAcState =
                    WidgetAcMapper.confirmedStateForCommand(effectiveCommand, executionConfirmed)
                if (confirmedAcState != null) {
                    store.updateWidgetAcState(session.selectedVin, confirmedAcState)
                }
                if (effectiveCommand == "windowClose") {
                    store.updateWidgetWindowState(session.selectedVin, windowOpen = false)
                } else if (effectiveCommand == "windowOpen" || effectiveCommand == "windowVent") {
                    store.updateWidgetWindowState(session.selectedVin, windowOpen = true)
                }
                if (confirmedLockedState != null || effectiveCommand.startsWith("window") || effectiveCommand.startsWith("trunk")) {
                    ControlWidget.enqueueCommandSync(this)
                }
                ControlWidget.showControlStatus(this, text, confirmedAcState, locked = confirmedLockedState)
                notifyResult(text)

                if (ParkingAnomalyPolicy.shouldCheck(command, commandAccepted)) {
                    Thread {
                        try {
                            Thread.sleep(ParkingAnomalyPolicy.POST_LOCK_CHECK_DELAY_MS)
                            val currentSession = store.load()
                            if (currentSession.oldAuth != null &&
                                currentSession.newAuth != null &&
                                currentSession.generation == session.generation &&
                                currentSession.selectedVin == session.selectedVin
                            ) {
                                val latest = api.getVehicleState()
                                ParkingAnomalyNotificationManager.notifyIfNeeded(
                                    this@ControlService,
                                    store,
                                    session.selectedCarType,
                                    latest
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }.start()
                }
            } catch (e: Exception) {
                val error = if (OperationPasswordErrorPolicy.isPasswordError(e)) {
                    OperationPasswordErrorPolicy.ERROR_PROMPT_MESSAGE
                } else {
                    e.message ?: "控车失败"
                }
                ControlWidget.showControlStatus(this, error)
                notifyResult(error)
            } finally {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    store.setWidgetBusyCommand(null)
                    ControlWidget.refreshData(applicationContext)
                    commandInFlight.set(false)
                    com.leapauto.app.tiles.TilePromptHelper.requestTilesUpdate(applicationContext)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    val request = pinRequest
                    if (request == null) {
                        promptReceiver?.send(0, null)
                    } else {
                        promptReceiver?.send(SHOW_PIN_PROMPT, request.toBundle())
                    }
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    /** Persists a complete, VIN-scoped widget snapshot after a command refresh. */
    private fun saveWidgetSnapshotFromStatus(
        store: SessionStore,
        session: Session,
        status: org.json.JSONObject
    ): TrunkState {
        val config = store.loadVehicleConfig(session.selectedVin, session.selectedCarType)
        val displayStatus = status
        val resolvedPowerType = VehiclePowerTypeResolver.fromStatus(
            displayStatus, config.powerType, session.selectedCarType
        )
        val powerType = resolvedPowerType.toStatusPowerType()
        val trunkState = TrunkStateMapper.fromSignal(displayStatus.opt("bbcmBackDoorStatus"))
        val sentryEnabled = WidgetSentryMapper.state(displayStatus)
        val openWindows = WidgetStatusMapper.openWindowLabels(displayStatus, session.selectedCarType)
        val windowOpen = openWindows.isNotEmpty()
        val now = System.currentTimeMillis()
        store.saveWidgetSnapshot(
            vin = session.selectedVin,
            carType = session.selectedCarType,
            range = VehicleStatusMapper.widgetRange(displayStatus, session.selectedCarType, powerType) ?: "--",
            soc = VehicleStatusMapper.electricSocPercent(displayStatus)
                ?: VehicleStatusMapper.soc(displayStatus),
            fuelSoc = VehicleStatusMapper.fuelSocPercent(displayStatus),
            updated = ControlWidget.formatUpdatedTime(),
            powerType = resolvedPowerType,
            electricRange = VehicleStatusMapper.electricRemainingRange(displayStatus),
            fuelRange = VehicleStatusMapper.fuelRemainingRange(displayStatus),
            electricTotalRange = VehicleStatusMapper.electricTotalRange(displayStatus),
            fuelTotalRange = VehicleStatusMapper.fuelTotalRange(displayStatus),
            statusLabel = WidgetStatusMapper.label(displayStatus, session.selectedCarType).orEmpty(),
            locked = WidgetStatusMapper.locked(displayStatus),
            acEnabled = WidgetAcMapper.state(displayStatus),
            chargingPower = ChargeStatus.power(displayStatus),
            chargeState = ChargeStatus.state(displayStatus),
            chargeSoc = ChargeStatus.socPercent(displayStatus.opt("soc")),
            chargeRemainTime = ChargeStatus.remainingTime(displayStatus.opt("chargeRemainTime")),
            capturedAt = now,
            lastSuccessAt = now,
            sessionGeneration = session.generation,
            driving = WidgetStatusMapper.isDriving(displayStatus),
            trunkState = trunkState,
            sentryEnabled = sentryEnabled,
            windowOpen = windowOpen
        )
        store.save(session)
        return trunkState
    }

    /**
     * The command result can arrive before signal/info/query reflects it. Keep
     * the widget bound to the last confirmed direction while giving telemetry a
     * short, bounded window to catch up.
     */
    private fun refreshTrunkStateUntilTarget(
        store: SessionStore,
        originalSession: Session,
        targetState: TrunkState
    ): TrunkState {
        var lastKnownState = TrunkState.UNKNOWN
        for (attempt in 0 until TRUNK_TELEMETRY_MAX_ATTEMPTS) {
            Thread.sleep(ControlResultPollingPolicy.delayBeforeAttempt(attempt))
            val currentSession = store.load()
            if (currentSession.oldAuth == null ||
                currentSession.newAuth == null ||
                currentSession.generation != originalSession.generation ||
                currentSession.selectedVin != originalSession.selectedVin
            ) {
                return lastKnownState
            }
            val observed = runCatching {
                val latest = LeapmotorApi(currentSession).getVehicleState()
                saveWidgetSnapshotFromStatus(store, currentSession, latest)
            }.getOrElse { TrunkState.UNKNOWN }
            if (observed != TrunkState.UNKNOWN) {
                lastKnownState = observed
            }
            if (observed == targetState) {
                return observed
            }
        }
        return lastKnownState
    }

    /**
     * Confirms the sentry direction against vehicle telemetry, mirroring the
     * app main screen policy. Returns the latest observed sentry state so the
     * widget snapshot can be updated even before the target is reached.
     */
    private fun refreshSentryStateUntilTarget(
        store: SessionStore,
        originalSession: Session,
        targetEnabled: Boolean
    ): Boolean? {
        var lastKnownEnabled: Boolean? = null
        for (attempt in 0 until TRUNK_TELEMETRY_MAX_ATTEMPTS) {
            Thread.sleep(ControlResultPollingPolicy.delayBeforeAttempt(attempt))
            val currentSession = store.load()
            if (currentSession.oldAuth == null ||
                currentSession.newAuth == null ||
                currentSession.generation != originalSession.generation ||
                currentSession.selectedVin != originalSession.selectedVin
            ) {
                return lastKnownEnabled
            }
            val observed = runCatching {
                val latest = LeapmotorApi(currentSession).getVehicleState()
                val sentryEnabled = WidgetSentryMapper.state(latest)
                saveWidgetSnapshotFromStatus(store, currentSession, latest)
                sentryEnabled
            }.getOrNull()
            if (observed != null) {
                lastKnownEnabled = observed
            }
            if (SentryModeControlPolicy.isConfirmed(targetEnabled, observed)) {
                return observed
            }
        }
        return lastKnownEnabled
    }

    private fun notification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            RESULT_NOTIF_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("零跑遥控")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    private fun notifyResult(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(RESULT_NOTIF_ID, notification(text))
    }

}
