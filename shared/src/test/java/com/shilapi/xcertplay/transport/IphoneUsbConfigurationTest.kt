package com.shilapi.xcertplay.transport

import android.hardware.usb.*
import android.os.Parcelable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.Executor

/** Replay configuration/claim results, exercising the Android 9 host and NCM open paths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [ConfigurationConnectionShadow::class])
class IphoneUsbConfigurationTest {
    private lateinit var device: UsbDevice
    private lateinit var config5: UsbConfiguration
    private lateinit var config6: UsbConfiguration

    @Before fun setUp() {
        ConfigurationReplay.reset()
        config5 = configuration(5, controlId = 2)
        config6 = configuration(6, controlId = 3)
        device = ReflectionHelpers.callConstructor(UsbDevice::class.java,
            string("/dev/bus/usb/001/002"), int(0x05ac), int(4776), int(0), int(0), int(0),
            string("Apple"), string("iPhone"), string("1.0"), string("test-only"))
        setArray(device, "setConfigurations", arrayOf(audioConfiguration(), configuration(4, null), config5, config6))
    }

    @Test fun activeConfiguration5IsReusedAndPassedThroughRealUsbMuxSession() {
        ConfigurationReplay.activeId = 5
        val result = openHost()
        assertTrue(result is IphoneUsbHost.Iap2SessionResult.Connected)
        val session = (result as IphoneUsbHost.Iap2SessionResult.Connected).session
        try {
            assertEquals(5, session.configuration!!.id)
            assertEquals(listOf("get", "claim:1"), ConfigurationReplay.events)
            val function = NcmFunctionDiscovery.find(session.configuration!!)!!
            assertEquals(2, function.control.id)
            assertEquals(3, function.data.id)
            NcmUsbBridge.open(connection(), function, session.configuration!!.id).close()
            assertTrue(ConfigurationReplay.events.containsAll(listOf("claim:2", "claim:3", "alt:3/1")))
            assertFalse(ConfigurationReplay.events.any { it.startsWith("set:") })
        } finally { session.close() }
    }

    @Test fun incompatibleActiveConfigurationIsSwitchedAndReadBackBeforeClaim() {
        ConfigurationReplay.activeId = 4
        val result = openHost() as IphoneUsbHost.Iap2SessionResult.Connected
        try {
            assertEquals(6, result.session.configuration!!.id)
            assertEquals(listOf("get", "set:6", "get", "claim:1"), ConfigurationReplay.events)
        } finally { result.session.close() }
    }

    @Test fun failedSetDoesNotClaimInterfacesFromAnInactiveConfiguration() {
        ConfigurationReplay.activeId = 4
        ConfigurationReplay.setResult = false
        ConfigurationReplay.applySet = false
        val result = openHost() as IphoneUsbHost.Iap2SessionResult.Failed
        assertTrue(result.error.message!!.contains("set=false active=4"))
        assertEquals(listOf("get", "set:6", "get", "close"), ConfigurationReplay.events)
    }

    @Test fun successReturnWithoutActualConfigurationChangeIsRejected() {
        ConfigurationReplay.activeId = 4
        ConfigurationReplay.applySet = false
        assertTrue(openHost() is IphoneUsbHost.Iap2SessionResult.Failed)
        assertFalse(ConfigurationReplay.events.any { it.startsWith("claim:") })
    }

    @Test fun falseSetReturnCanBeAcceptedOnlyWhenReadBackConfirmsUsableConfiguration() {
        ConfigurationReplay.activeId = 4
        ConfigurationReplay.setResult = false
        val result = openHost() as IphoneUsbHost.Iap2SessionResult.Connected
        try { assertEquals(6, result.session.configuration!!.id) } finally { result.session.close() }
    }

    @Test fun shortOrFailedGetCannotProveAConfigurationIsActive() {
        for (length in listOf(0, -1)) {
            ConfigurationReplay.reset()
            ConfigurationReplay.getLength = length
            assertTrue(openHost() is IphoneUsbHost.Iap2SessionResult.Failed)
            assertFalse(ConfigurationReplay.events.any { it.startsWith("claim:") })
            assertEquals("close", ConfigurationReplay.events.last())
        }
    }

    @Test fun ncmRejectsConfigurationChangeWithoutResettingDeviceOrClaiming() {
        ConfigurationReplay.activeId = 5
        assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
            NcmUsbBridge.open(connection(), NcmFunctionDiscovery.find(config6)!!, 6)
        }
        assertEquals(listOf("get", "close"), ConfigurationReplay.events)
    }

    @Test fun failedNcmClaimClosesConnectionAndPreservesSpecificFailure() {
        ConfigurationReplay.activeId = 6
        ConfigurationReplay.failedClaim = 3
        val diagnostics = mutableListOf<String>()
        val error = assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
            NcmUsbBridge.open(connection(), NcmFunctionDiscovery.find(config6)!!, 6, diagnostics::add)
        }
        assertTrue(error.message!!.contains("NCM interface 3"))
        assertEquals(listOf("get", "claim:3", "close"), ConfigurationReplay.events)
        assertTrue(diagnostics.any { it.contains("control claim iface=3/0 result=false") })
    }

    @Test fun failedDataClaimReleasesControlAndClosesConnection() {
        ConfigurationReplay.activeId = 6
        ConfigurationReplay.failedClaim = 4
        assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
            NcmUsbBridge.open(connection(), NcmFunctionDiscovery.find(config6)!!, 6)
        }
        assertEquals(listOf("get", "claim:3", "claim:4", "release:3", "close"), ConfigurationReplay.events)
    }

    @Test fun macStringIndexIsScopedToConfigurationInterfaceAndAlternateSetting() {
        fun descriptors(config: Int, alt: Int, macIndex: Int) = byteArrayOf(
            9, 2, 22, 0, 1, config.toByte(), 0, 0x80.toByte(), 50,
            9, 4, 2, alt.toByte(), 0, 2, 0x0d, 0, 0,
            4, 0x24, 0x0f, macIndex.toByte(),
        )
        val raw = descriptors(4, 0, 9) + descriptors(5, 1, 10) + descriptors(5, 0, 11)
        assertEquals(11, NcmUsbBridge.ethernetMacStringIndex(raw, 2, 5, 0))
        assertEquals(9, NcmUsbBridge.ethernetMacStringIndex(raw, 2, 4, 0))
        assertNull(NcmUsbBridge.ethernetMacStringIndex(raw, 2, 6, 0))
        assertNull(NcmUsbBridge.ethernetMacStringIndex(raw, 3, 5, 0))
    }

    @Test fun carAudioConfiguration2StaysUnclaimedAndPreservesTheNativeFailureReason() {
        ConfigurationReplay.activeId = 2
        val diagnostics = mutableListOf<String>()
        var sets = 0
        val error = assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
            IphoneUsbConfiguration.select(connection(), device, config6, diagnostics::add) { _, target ->
                sets++
                assertEquals(6, target.id)
                UsbConfigurationAccess.Result(false, 16, "usbfs")
            }
        }
        assertEquals(1, sets)
        assertTrue(error.message!!.contains("active=2 error=EBUSY"))
        assertFalse(ConfigurationReplay.events.any { it.startsWith("claim:") })
        assertTrue(diagnostics.any { it.contains("backend=usbfs errno=16 error=EBUSY") })
    }

    @Test fun driverQueryUsesOnlyCurrentConfigurationAndDeduplicatesAlternateSettings() {
        val requested = mutableListOf<Int>()
        val lines = UsbConfigurationAccess.driverSnapshot(connection(), audioConfiguration()) { _, id ->
            requested.add(id)
            if (id == 2) "usbfs" else "snd-usb-audio"
        }
        assertEquals(listOf(0, 1, 2), requested)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.contains("config=2") })
        assertTrue(lines.last().endsWith("result=usbfs"))
        assertTrue(ConfigurationReplay.events.isEmpty())
    }

    @Test fun busyHandoffIsAcceptedOnlyAfterReadingBackAUsableConfiguration() {
        for (activeAfterHandoff in listOf(2, 6)) {
            ConfigurationReplay.activeId = 2
            val select = {
                IphoneUsbConfiguration.select(connection(), device, config6, recoverBusy = { before, target ->
                    assertEquals(2, before.id)
                    assertEquals(6, target.id)
                    ConfigurationReplay.activeId = activeAfterHandoff
                    true
                }) { _, _ -> UsbConfigurationAccess.Result(false, 16, "usbfs") }
            }
            if (activeAfterHandoff == 6) assertEquals(6, select().id)
            else assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { select() }
        }
    }

    @Test fun permissionFailureNeverTriggersDriverHandoff() {
        ConfigurationReplay.activeId = 2
        assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
            IphoneUsbConfiguration.select(connection(), device, config6, recoverBusy = { _, _ ->
                fail("Driver handoff must not run for EACCES")
                false
            }) { _, _ -> UsbConfigurationAccess.Result(false, 13, "usbfs") }
        }
    }

    @Test fun ncmClosesDataConnectionBeforeReleasingRecoveryLeaseOnlyOnce() {
        var releases = 0
        val ncm = NcmUsbBridge.open(connection(), NcmFunctionDiscovery.find(config6)!!, 6,
            afterClose = {
                assertEquals("close", ConfigurationReplay.events.last())
                releases++
            })
        ncm.close()
        ncm.close()
        assertEquals(1, releases)
    }

    private fun audioConfiguration(): UsbConfiguration {
        val config = ReflectionHelpers.callConstructor(UsbConfiguration::class.java,
            int(2), string("audio"), int(0x80), int(50))
        setArray(config, "setInterfaces", arrayOf(
            usbInterface(0, 0, 1, 1, 0), usbInterface(1, 0, 1, 2, 0),
            usbInterface(1, 1, 1, 2, 0), usbInterface(2, 0, 3, 0, 0)))
        return config
    }

    private fun openHost(): IphoneUsbHost.Iap2SessionResult {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(UsbManager::class.java)
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        val host = IphoneUsbHost(context, manager, IphoneUsbMatcher.appleVendor())
        // The actual LYNK resource override is checked separately in LynkOsNProfileTest.
        ReflectionHelpers.setField(host, "verifyConfiguration", true)
        var result: IphoneUsbHost.Iap2SessionResult? = null
        host.openIap2UsbSessionAsync(device, Executor { it.run() }) { result = it }
        return result!!
    }

    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, device))

    private fun configuration(id: Int, controlId: Int?): UsbConfiguration {
        val config = ReflectionHelpers.callConstructor(UsbConfiguration::class.java,
            int(id), string("configuration"), int(0x80), int(50))
        val interfaces = mutableListOf(usbInterface(1, 0, 0xff, 0xfe, 2, 0x04, 0x85))
        if (controlId != null) {
            interfaces.add(usbInterface(controlId, 0, 2, 0x0d, 0))
            interfaces.add(usbInterface(controlId + 1, 1, 0x0a, 0, 0, 0x06, 0x88))
        }
        if (id == 6) interfaces.add(usbInterface(2, 0, 0xff, 0xfd, 1))
        setArray(config, "setInterfaces", interfaces.toTypedArray())
        return config
    }

    private fun usbInterface(id: Int, alt: Int, cls: Int, sub: Int, proto: Int, vararg addresses: Int): UsbInterface {
        val result = ReflectionHelpers.callConstructor(UsbInterface::class.java,
            int(id), int(alt), string("interface"), int(cls), int(sub), int(proto))
        val endpoints = addresses.map { address -> ReflectionHelpers.callConstructor<UsbEndpoint>(
            UsbEndpoint::class.java, int(address), int(2), int(512), int(0)) }
        setArray(result, "setEndpoints", endpoints.toTypedArray())
        return result
    }

    private fun setArray(target: Any, method: String, values: Array<out Parcelable>) {
        ReflectionHelpers.callInstanceMethod<Void>(target, method,
            ClassParameter.from(Array<Parcelable>::class.java, values))
    }
    private fun int(value: Int) = ClassParameter.from(Int::class.javaPrimitiveType, value)
    private fun string(value: String) = ClassParameter.from(String::class.java, value)
}

object ConfigurationReplay {
    val events = mutableListOf<String>()
    var activeId = 6
    var setResult = true
    var applySet = true
    var getLength = 1
    var failedClaim: Int? = null
    fun reset() {
        events.clear()
        activeId = 6
        setResult = true
        applySet = true
        getLength = 1
        failedClaim = null
    }
}

@Implements(UsbDeviceConnection::class)
class ConfigurationConnectionShadow {
    @Implementation fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int,
        buffer: ByteArray, length: Int, timeout: Int): Int {
        assertEquals(0x80, requestType)
        assertEquals(0x08, request)
        assertEquals(0, value)
        assertEquals(0, index)
        assertEquals(1, length)
        assertEquals(1000, timeout)
        ConfigurationReplay.events.add("get")
        buffer[0] = ConfigurationReplay.activeId.toByte()
        return ConfigurationReplay.getLength
    }
    @Implementation fun setConfiguration(configuration: UsbConfiguration): Boolean {
        ConfigurationReplay.events.add("set:${configuration.id}")
        if (ConfigurationReplay.applySet) ConfigurationReplay.activeId = configuration.id
        return ConfigurationReplay.setResult
    }
    @Implementation fun claimInterface(intf: UsbInterface, force: Boolean): Boolean {
        assertTrue(force)
        ConfigurationReplay.events.add("claim:${intf.id}")
        return intf.id != ConfigurationReplay.failedClaim
    }
    @Implementation fun setInterface(intf: UsbInterface): Boolean {
        ConfigurationReplay.events.add("alt:${intf.id}/${intf.alternateSetting}")
        return true
    }
    @Implementation fun releaseInterface(intf: UsbInterface): Boolean {
        ConfigurationReplay.events.add("release:${intf.id}")
        return true
    }
    @Implementation fun getRawDescriptors(): ByteArray = byteArrayOf()
    @Implementation fun close() { ConfigurationReplay.events.add("close") }
}
