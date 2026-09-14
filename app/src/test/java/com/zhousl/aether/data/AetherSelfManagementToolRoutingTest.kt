package com.zhousl.aether.data

import org.junit.Assert.assertTrue
import org.junit.Test

class AetherSelfManagementToolRoutingTest {
    @Test
    fun scheduledTaskToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_scheduled_task_manage"))
    }

    @Test
    fun piExtensionToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("aether_extension_manage"))
    }

    @Test
    fun deviceCapabilityToolIsRoutedByPiHostExecutor() {
        assertTrue(AetherToolExecutor.supports("qing_device_manage"))
        // Sessions stored before the rename still call the old name.
        assertTrue(AetherToolExecutor.supports("aether_device_manage"))
    }
}
