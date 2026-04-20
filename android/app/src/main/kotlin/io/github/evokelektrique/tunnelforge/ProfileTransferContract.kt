package io.github.evokelektrique.tunnelforge

/** Method channel contract for incoming `tf://` links and `.tfp` profile files. */
object ProfileTransferContract {
    const val METHOD_CHANNEL = "io.github.evokelektrique.tunnelforge/profileTransfer"

    const val CONSUME_PENDING_TRANSFERS = "consumePendingTransfers"
    const val ON_INCOMING_TRANSFER = "onIncomingTransfer"

    const val ARG_TYPE = "type"
    const val ARG_DATA = "data"
    const val ARG_MESSAGE = "message"
    const val ARG_SOURCE = "source"

    const val TYPE_TFP_JSON = "tfpJson"
    const val TYPE_TF_URI = "tfUri"
    const val TYPE_ERROR = "error"
}
