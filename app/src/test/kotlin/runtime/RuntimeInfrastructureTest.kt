package runtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import runtime.di.AgentContainer

class RuntimeInfrastructureTest {

    @Before
    fun setup() {
        RuntimeStateManager.reset()
    }

    // GEN-017 Identity System Test
    @Test
    fun testIdentityGeneration() {
        val id1 = Identity.nextId("comp")
        val id2 = Identity.nextId("comp")
        assertTrue(id1.startsWith("comp-"))
        assertTrue(id1 != id2)
    }

    // GEN-030 & GEN-INIT-1 Initialization Sequence & State Flow Test
    @Test
    fun testInitializationSequence() {
        val container = AgentContainer()
        val results = container.appInitializer.initialize()

        assertTrue(results.all { it is AppInitializer.InitStepResult.Success })
        assertEquals(AgentState.IDLE, RuntimeStateManager.currentState.value)
    }

    // GEN-NS-1 NervousSystem Conflated vs Lossless Buffered Channels Test
    @Test
    fun testNervousSystemChannels() = runBlocking {
        // Conflated Status Signals: emit multiple, observe flow
        val ping1 = StatusSignal.HealthPing(timestamp = 1000L, memoryAvailableMb = 4096L)
        val ping2 = StatusSignal.HealthPing(timestamp = 2000L, memoryAvailableMb = 4000L)
        NervousSystem.emitStatus(ping1)
        NervousSystem.emitStatus(ping2)

        val receivedStatus = NervousSystem.statusFlow().first()
        assertNotNull(receivedStatus)

        // Lossless Critical Event
        val criticalEvt = CriticalEvent.ActionExecuted(actionId = "act-1", toolName = "system_info", success = true)
        NervousSystem.emitCriticalEvent(criticalEvt)

        val receivedCritical = NervousSystem.criticalEventFlow().first()
        assertEquals(criticalEvt, receivedCritical)
    }

    // GEN-031/032 Error Model Test
    @Test
    fun testRuntimeErrorModel() {
        val err = AgentRuntimeError.ConfigurationError("Missing param")
        assertEquals("ERR_CONFIG", err.code)
        assertEquals("Missing param", err.message)
    }
}
