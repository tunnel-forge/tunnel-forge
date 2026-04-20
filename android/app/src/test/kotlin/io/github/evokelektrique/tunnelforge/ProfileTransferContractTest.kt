package io.github.evokelektrique.tunnelforge

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTransferContractTest {

    @Test
    fun methodChannel() {
        assertEquals(
            "io.github.evokelektrique.tunnelforge/profileTransfer",
            ProfileTransferContract.METHOD_CHANNEL,
        )
    }

    @Test
    fun transferPayloadKeys() {
        assertEquals("consumePendingTransfers", ProfileTransferContract.CONSUME_PENDING_TRANSFERS)
        assertEquals("onIncomingTransfer", ProfileTransferContract.ON_INCOMING_TRANSFER)
        assertEquals("type", ProfileTransferContract.ARG_TYPE)
        assertEquals("data", ProfileTransferContract.ARG_DATA)
        assertEquals("message", ProfileTransferContract.ARG_MESSAGE)
        assertEquals("source", ProfileTransferContract.ARG_SOURCE)
        assertEquals("tfpJson", ProfileTransferContract.TYPE_TFP_JSON)
        assertEquals("tfUri", ProfileTransferContract.TYPE_TF_URI)
        assertEquals("error", ProfileTransferContract.TYPE_ERROR)
    }
}
