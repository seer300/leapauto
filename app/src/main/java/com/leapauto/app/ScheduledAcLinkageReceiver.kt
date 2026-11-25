package com.leapauto.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class ScheduledAcLinkageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCHEDULED_AC_LINKAGE) return
        val vin = intent.getStringExtra(EXTRA_VIN) ?: return
        val store = SessionStore(context)
        val plan = loadPlan(store, vin)
        ScheduledAcScheduler.schedule(context, vin, plan)

        val session = store.load()
        val pending = ExternalControlRequest.create(session, COMMAND)
        if (vin == session.selectedVin && pending.isCurrent(session) && plan.enabled &&
            ScheduledAcLinkagePolicy.isTodayScheduled(plan.days) && linkageCommands(plan).isNotEmpty()
        ) {
            ControlRequestActivity.notifyPrompt(context, pending)
        }
    }

    companion object {
        const val ACTION_SCHEDULED_AC_LINKAGE = "com.leapauto.app.action.SCHEDULED_AC_LINKAGE"
        const val EXTRA_VIN = "extra_vin"
        internal const val COMMAND = "scheduled_ac_linkage"

        internal fun execute(context: Context, request: ExternalControlRequest, pin: String) {
            val store = SessionStore(context)
            val session = store.load()
            if (request.command != COMMAND || !request.isCurrent(session) || !OperationPin.isValid(pin)) return
            val plan = loadPlan(store, request.vin)
            if (!plan.enabled || !ScheduledAcLinkagePolicy.isTodayScheduled(plan.days)) return
            val api = LeapmotorApi(session)
            try {
                linkageCommands(plan).forEachIndexed { index, command ->
                    if (index > 0) Thread.sleep(1200)
                    if (!request.isCurrent(store.load()) || loadPlan(store, request.vin) != plan) return
                    api.sendControl(Commands.build(command), pin)
                }
                showResult(context, "定时空调联动指令已提交")
            } catch (error: Exception) {
                showResult(context, if (OperationPasswordErrorPolicy.isPasswordError(error)) {
                    OperationPasswordErrorPolicy.ERROR_PROMPT_MESSAGE
                } else {
                    "定时空调联动未完成"
                })
            }
        }

        internal fun linkageCommands(plan: ScheduledAcPlan): List<String> = buildList {
            if (plan.closeWindows) add("windowClose")
            if (plan.closeSunshade) add("sunshadeClose")
            if (plan.driverHeating) add("driverSeatHeating_2") else if (plan.driverVent) add("driverSeatVentilation_2")
            if (plan.passengerHeating) add("passengerSeatHeating_2") else if (plan.passengerVent) add("passengerSeatVentilation_2")
            if (plan.leftRearHeating) add("leftRearSeatHeating_2") else if (plan.leftRearVent) add("leftRearSeatVentilation_2")
            if (plan.rightRearHeating) add("rightRearSeatHeating_2") else if (plan.rightRearVent) add("rightRearSeatVentilation_2")
            if (plan.steeringWheelHeating) add("steeringWheelHeating_2")
        }

        private fun showResult(context: Context, message: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        internal fun loadPlan(store: SessionStore, vin: String): ScheduledAcPlan = ScheduledAcPlan(
            enabled = store.loadScheduledAcEnabled(vin),
            startTime = store.loadScheduledAcStartTime(vin),
            days = store.loadScheduledAcDays(vin),
            temperatureC = store.loadScheduledAcTemperature(vin),
            windLevel = store.loadScheduledAcWindLevel(vin),
            mode = ScheduledAcMode.fromName(store.loadScheduledAcMode(vin)),
            circle = AirCircle.entries.firstOrNull { it.name == store.loadScheduledAcCircle(vin) } ?: AirCircle.INNER,
            windshieldDefogging = store.loadScheduledAcDefrost(vin),
            closeWindows = store.loadScheduledAcCloseWindows(vin),
            closeSunshade = store.loadScheduledAcCloseSunshade(vin),
            driverHeating = store.loadScheduledAcDriverHeating(vin),
            driverVent = store.loadScheduledAcDriverVent(vin),
            passengerHeating = store.loadScheduledAcPassengerHeating(vin),
            passengerVent = store.loadScheduledAcPassengerVent(vin),
            leftRearHeating = store.loadScheduledAcLeftRearHeating(vin),
            leftRearVent = store.loadScheduledAcLeftRearVent(vin),
            rightRearHeating = store.loadScheduledAcRightRearHeating(vin),
            rightRearVent = store.loadScheduledAcRightRearVent(vin),
            steeringWheelHeating = store.loadScheduledAcSteeringWheelHeating(vin)
        )

    }
}
