package ru.capjack.csi.core.client.internal

import ru.capjack.tool.io.InputByteBuffer

internal class DummyConnectionDelegate() : ConnectionDelegate {
	
	override val connectionId: Any = "dummy"
	
	override fun setProcessor(processor: ConnectionProcessor) {
	}
	
	override fun send(data: Byte) {
	}
	
	override fun send(data: ByteArray) {
	}
	
	override fun send(data: InputByteBuffer) {
		data.skipRead()
	}
	
	override fun close() {
	}
	
	override fun terminate() {
	}
	
	override fun deferInput() {
	}
}