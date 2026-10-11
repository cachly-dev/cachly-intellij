package dev.cachly.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * POST /api/v1/instances/auto answers in two shapes
 * (api/internal/handler/instance_handler.go, AutoProvision). Reading only one
 * of them leaves the other case with an empty instance id — and without an
 * instance id the plugin sends no request at all.
 */
class AutoInstanceIdTest {

    private val id = "8e03addd-a2d9-406e-bcbb-d6d8c938a3d0"

    @Test
    fun `existing instance - nested id`() {
        val json = """{"instance":{"id":"$id","name":"my-brain","tier":"free"},"created":false}"""
        assertEquals(id, CachlyApiClient.parseAutoInstanceId(json))
    }

    @Test
    fun `new instance - flat instance_id`() {
        val json = """{"instance_id":"$id","status":"provisioning","created":true}"""
        assertEquals(id, CachlyApiClient.parseAutoInstanceId(json))
    }

    @Test
    fun `null instance falls back to the flat field`() {
        val json = """{"instance":null,"instance_id":"$id"}"""
        assertEquals(id, CachlyApiClient.parseAutoInstanceId(json))
    }

    @Test
    fun `empty or missing id is no id`() {
        assertNull(CachlyApiClient.parseAutoInstanceId("""{"instance_id":"","created":true}"""))
        assertNull(CachlyApiClient.parseAutoInstanceId("""{"instance":{},"created":false}"""))
        assertNull(CachlyApiClient.parseAutoInstanceId("""{"error":"auto-provision failed"}"""))
    }

    @Test
    fun `garbage is no id, not an exception`() {
        assertNull(CachlyApiClient.parseAutoInstanceId("not json"))
        assertNull(CachlyApiClient.parseAutoInstanceId("[]"))
        assertNull(CachlyApiClient.parseAutoInstanceId(""))
    }
}
