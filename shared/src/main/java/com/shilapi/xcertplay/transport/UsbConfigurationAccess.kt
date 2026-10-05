package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDeviceConnection

/** The same SET_CONFIGURATION operation as Android, retaining its otherwise discarded errno. */
internal object UsbConfigurationAccess {
    data class Result(val selected: Boolean, val errno: Int?, val backend: String)

    fun select(connection: UsbDeviceConnection, configuration: UsbConfiguration): Result = try {
        val error = UsbConfigurationNative.setConfiguration(connection.fileDescriptor, configuration.id)
        Result(error == 0, error, "usbfs")
    } catch (_: LinkageError) {
        // Keep the Android path usable on unsupported ABIs; the report states errno is unavailable.
        Result(connection.setConfiguration(configuration), null, "android-api")
    }

    fun driverSnapshot(
        connection: UsbDeviceConnection,
        configuration: UsbConfiguration?,
        readDriver: (Int, Int) -> String = { fd, id -> UsbConfigurationNative.driver(fd, id) },
    ): List<String> {
        if (configuration == null) return listOf("USB driver query unavailable: active descriptors missing")
        return (0 until configuration.interfaceCount).map(configuration::getInterface).distinctBy { it.id }.map { intf ->
            val result = try {
                readDriver(connection.fileDescriptor, intf.id)
            } catch (_: LinkageError) { "reader-unavailable" }
            val safe = result.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,256}")) } ?: "unrecognized"
            "USB driver config=${configuration.id} iface=${intf.id} class=${intf.interfaceClass} result=$safe"
        }
    }

    fun errorLabel(errno: Int?): String = when (errno) {
        null -> "unavailable"
        0 -> "OK"
        1 -> "EPERM"
        5 -> "EIO"
        9 -> "EBADF"
        13 -> "EACCES"
        16 -> "EBUSY"
        19 -> "ENODEV"
        22 -> "EINVAL"
        32 -> "EPIPE"
        61 -> "ENODATA"
        110 -> "ETIMEDOUT"
        else -> "errno-$errno"
    }
}

/** Borrows permission-granted fds. Handoff is conditional; these calls never open, close or reset. */
internal object UsbConfigurationNative {
    init { System.loadLibrary("usb_configuration") }
    external fun setConfiguration(fd: Int, configurationId: Int): Int
    external fun driver(fd: Int, interfaceId: Int): String
    external fun detachAndClaim(fd: Int, interfaceId: Int, expectedDriver: String): Int
    external fun release(fd: Int, interfaceId: Int): Int
    external fun reconnect(fd: Int, interfaceId: Int): Int
}

/** Owns a separate permission-granted connection so closing either data fd cannot prevent cleanup. */
internal class AndroidUsbRecoveryAccess(private val connection: UsbDeviceConnection) : UsbDriverRecovery.Access {
    override fun active() = IphoneUsbConfiguration.activeId(connection)
    override fun driver(id: Int) = UsbConfigurationNative.driver(connection.fileDescriptor, id)
    override fun detachAndClaim(id: Int, expected: String) =
        UsbConfigurationNative.detachAndClaim(connection.fileDescriptor, id, expected)
    override fun release(id: Int) = UsbConfigurationNative.release(connection.fileDescriptor, id)
    override fun select(id: Int) = UsbConfigurationNative.setConfiguration(connection.fileDescriptor, id)
    override fun reconnect(id: Int) = UsbConfigurationNative.reconnect(connection.fileDescriptor, id)
    override fun close() = connection.close()
}
