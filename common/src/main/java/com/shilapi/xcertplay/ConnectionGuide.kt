package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.Iap2HandshakeStage

/** UI decisions use protocol states, never keywords in an already translated sentence. */
internal object ConnectionGuide {
    enum class Action { HOTSPOT, BLUETOOTH, PERMISSIONS, RETRY }
    data class State(val title: Int, val detail: Int, val action: Action? = null, val pauseRetry: Boolean = false)

    fun state(status: CarPlayStatus, wireless: Boolean, automaticHotspot: Boolean = false): State = when (status) {
        CarPlayStatus.StartingHotspot -> State(R.string.link_hotspot,
            if (automaticHotspot) R.string.auto_hotspot_creating else R.string.link_hotspot_check)
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady,
        CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission ->
            State(R.string.link_preparing, R.string.link_auth_preparing)
        is CarPlayStatus.HotspotReady, CarPlayStatus.WaitingForPairedIphone ->
            State(R.string.link_phone, R.string.link_phone_check)
        CarPlayStatus.ConnectingBluetooth -> State(R.string.link_bluetooth, R.string.link_phone_check)
        CarPlayStatus.RunningWireless, CarPlayStatus.RunningControl -> handshake(Iap2HandshakeStage.IDENTIFYING)
        is CarPlayStatus.HandshakeProgress -> handshake(status.stage).let {
            if (automaticHotspot && status.stage == Iap2HandshakeStage.WIFI_CREDENTIALS_SENT)
                it.copy(detail = R.string.auto_hotspot_sent) else it
        }
        is CarPlayStatus.HandshakeTimedOut -> handshake(status.stage).copy(
            detail = R.string.link_handshake_timeout, action = Action.RETRY, pauseRetry = true)
        CarPlayStatus.WirelessActive ->
            State(R.string.link_waiting_picture, R.string.link_accept_carplay)
        CarPlayStatus.AttachingNetwork -> if (wireless)
            State(R.string.link_hotspot, R.string.link_starting_receiver)
            else State(R.string.link_usb_network, R.string.link_usb_channel_hint)
        CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone ->
            State(R.string.link_usb, R.string.lynk_usb_quick_hint)
        CarPlayStatus.RequestingIphonePermission ->
            State(R.string.link_usb_permission_needed, R.string.link_usb_permission_prompt)
        CarPlayStatus.WaitingForReenumeration, CarPlayStatus.SelectingConfiguration ->
            State(R.string.link_usb_switching, R.string.link_usb_channel_hint)
        CarPlayStatus.OpeningDataPaths -> State(R.string.link_usb_opening, R.string.link_usb_channel_hint)
        CarPlayStatus.Pairing -> State(R.string.link_usb_trust, R.string.link_usb_trust_hint)
        CarPlayStatus.ConnectingControl -> State(R.string.link_usb_service, R.string.link_usb_channel_hint)
        CarPlayStatus.ControlEnded -> State(R.string.link_no_response,
            if (wireless) R.string.link_wait_help else R.string.link_usb_hint, Action.RETRY, true)
        is CarPlayStatus.Failed -> failure(status.message).let {
            if (!wireless && it.title == R.string.link_failed) it.copy(detail = R.string.link_usb_hint) else it
        }
    }

    private fun handshake(stage: Iap2HandshakeStage): State = when (stage) {
        Iap2HandshakeStage.IDENTIFYING -> State(R.string.link_identifying, R.string.link_identifying_hint)
        Iap2HandshakeStage.AUTHENTICATING -> State(R.string.link_authenticating, R.string.link_authenticating_hint)
        Iap2HandshakeStage.WAITING_FOR_CARPLAY -> State(R.string.link_waiting_phone, R.string.link_accept_carplay)
        Iap2HandshakeStage.WIFI_CREDENTIALS_SENT -> State(R.string.link_wifi_sent, R.string.link_wifi_sent_hint)
        Iap2HandshakeStage.START_REQUESTED -> State(R.string.link_start_sent, R.string.link_accept_carplay)
    }

    // Backend messages remain in English. Unknown errors retain their exact redacted details in
    // the report/dialog; they are not described as authentication failures without evidence.
    fun failure(message: String): State {
        val text = message.lowercase(java.util.Locale.ROOT)
        return when {
            "usb configuration could not be verified" in text || "usb configuration changed before ncm" in text || "usb driver handoff" in text ->
                State(R.string.link_usb_mode_failed, R.string.link_usb_mode_blocked_hint, Action.RETRY, true)
            "lockdown pairing" in text || "passwordprotected" in text ->
                State(R.string.link_usb_trust, R.string.link_usb_trust_hint, Action.RETRY, true)
            "carplay usb configuration" in text ->
                State(R.string.link_usb_mode_failed, R.string.link_usb_hint, Action.RETRY, true)
            "identification" in text || "control session readiness" in text ->
                State(R.string.link_identifying, R.string.link_handshake_timeout, Action.RETRY, true)
            "usb permission" in text ->
                State(R.string.link_usb_permission_needed, R.string.link_usb_permission_fix, Action.RETRY, true)
            "bluetooth restore pending" in text || "bluetooth suspension failed" in text || "bluetooth recovery journal" in text ->
                State(R.string.link_bluetooth_failed, R.string.auto_bluetooth_restore_failed, Action.BLUETOOTH, true)
            "location mode" in text -> State(R.string.link_permission_needed, R.string.auto_hotspot_location, Action.PERMISSIONS, true)
            ("localonlyhotspot" in text || "local_only_hotspot" in text) && ("incompatible" in text || "existing shared" in text) ->
                State(R.string.link_hotspot_failed, R.string.auto_hotspot_conflict, Action.HOTSPOT, true)
            "localonlyhotspot" in text || "local_only_hotspot" in text ->
                State(R.string.link_hotspot_failed, R.string.auto_hotspot_failed, Action.HOTSPOT, true)
            "hotspot" in text -> State(R.string.link_hotspot_failed, R.string.link_hotspot_fix, Action.HOTSPOT, true)
            "permission" in text || "denied" in text ->
                State(R.string.link_permission_needed, R.string.link_permission_fix, Action.PERMISSIONS, true)
            "mfi" in text || "certificate" in text || "authentication" in text ->
                State(R.string.link_auth_failed, R.string.link_auth_fix, Action.RETRY, true)
            "no longer paired" in text || "bluetooth adapter" in text || "bluetooth is not enabled" in text ->
                State(R.string.link_bluetooth_failed, R.string.link_bluetooth_fix, Action.BLUETOOTH, true)
            "rfcomm" in text || "socket" in text || "bluetooth" in text ->
                State(R.string.link_bluetooth_failed, R.string.link_bluetooth_fix, Action.BLUETOOTH)
            else -> State(R.string.link_failed, R.string.link_wait_help, Action.RETRY)
        }
    }
}
