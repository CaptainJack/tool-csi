package ru.capjack.csi.core.client.internal

import ru.capjack.csi.core.OutgoingMessage
import ru.capjack.csi.core.client.ClientDisconnectReason
import ru.capjack.tool.io.FramedInputByteBuffer

internal class NothingInternalClientProcessor : InternalClientProcessor {
	override fun processInput(delegate: ConnectionDelegate, buffer: FramedInputByteBuffer): Boolean {
		throw UnsupportedOperationException()
	}
	
	override fun processDisconnect(reason: ClientDisconnectReason) {
		throw UnsupportedOperationException()
	}
	
	override fun processLoss() {
		throw UnsupportedOperationException()
	}
	
	override fun sendMessage(message: OutgoingMessage) {
		throw UnsupportedOperationException()
	}
}