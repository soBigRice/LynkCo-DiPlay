package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/** Resolve the active configuration before claiming either USBMUX or NCM. */
internal object IphoneUsbConfiguration {
    fun select(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        preferred: UsbConfiguration,
        onDiagnostic: (String) -> Unit = {},
        recoverBusy: ((UsbConfiguration, UsbConfiguration) -> Boolean)? = null,
        selectConfiguration: (UsbDeviceConnection, UsbConfiguration) -> UsbConfigurationAccess.Result =
            UsbConfigurationAccess::select,
    ): UsbConfiguration {
        val before = activeId(connection)
        onDiagnostic("USB configuration before=${before ?: "unavailable"} preferred=${preferred.id}")
        compatibleConfiguration(device, before)?.let {
            // Re-selecting a working configuration resets alternate settings and can fail when
            // an OEM driver already owns another interface. Reuse the phone's current layout.
            onDiagnostic("USB configuration reuse=${it.id}")
            return it
        }
        val result = selectConfiguration(connection, preferred)
        val selected = result.selected
        val after = activeId(connection)
        onDiagnostic("USB configuration set=${preferred.id} result=$selected active=${after ?: "unavailable"}")
        onDiagnostic("USB configuration backend=${result.backend} errno=${result.errno ?: "unavailable"} " +
            "error=${UsbConfigurationAccess.errorLabel(result.errno)}")
        if (!selected) {
            val activeConfiguration = (0 until device.configurationCount).map(device::getConfiguration)
                .firstOrNull { it.id == after }
            UsbConfigurationAccess.driverSnapshot(connection, activeConfiguration).forEach(onDiagnostic)
            if (result.errno == 16 && device.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID &&
                before == after && activeConfiguration != null && recoverBusy != null) {
                val recovered = recoverBusy(activeConfiguration, preferred)
                val recoveredId = activeId(connection)
                onDiagnostic("USB configuration handoff=$recovered active=${recoveredId ?: "unavailable"}")
                if (recovered) compatibleConfiguration(device, recoveredId)?.let { return it }
                throw IphoneUsbException.DeviceUnavailable(
                    "USB driver handoff could not be verified: requested=${preferred.id} active=${recoveredId ?: "unavailable"}")
            }
        }
        return compatibleConfiguration(device, after)
            ?: throw IphoneUsbException.DeviceUnavailable(
                "USB configuration could not be verified: requested=${preferred.id} " +
                    "set=$selected active=${after ?: "unavailable"} " +
                    "error=${UsbConfigurationAccess.errorLabel(result.errno)}",
            )
    }

    fun requireActive(
        connection: UsbDeviceConnection,
        expectedId: Int,
        onDiagnostic: (String) -> Unit = {},
    ) {
        // Never SET_CONFIGURATION on the second fd: USBMUX has already claimed its interface.
        val actual = activeId(connection)
        onDiagnostic("USB NCM configuration expected=$expectedId active=${actual ?: "unavailable"}")
        if (actual != expectedId) {
            throw IphoneUsbException.DeviceUnavailable(
                "USB configuration changed before NCM: expected=$expectedId active=${actual ?: "unavailable"}",
            )
        }
    }

    private fun compatibleConfiguration(device: UsbDevice, id: Int?): UsbConfiguration? =
        (0 until device.configurationCount).map(device::getConfiguration).firstOrNull { configuration ->
            configuration.id == id &&
                IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                    ?.let(IphoneCarPlayConfiguration::usbMuxEndpoints) != null &&
                NcmFunctionDiscovery.find(configuration) != null
        }

    internal fun activeId(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        // USB standard GET_CONFIGURATION, device recipient. A descriptor list alone does not
        // reveal the active configuration; libusb uses this same one-byte request on Linux.
        val transferred = connection.controlTransfer(0x80, 0x08, 0, 0, value, value.size, 1_000)
        return if (transferred == 1) value[0].toInt() and 0xff else null
    }
}
