package ru.capjack.csi.core.server._test

import ru.capjack.csi.core.common.ChannelProcessor
import ru.capjack.csi.core.common.InternalChannel
import ru.capjack.tool.io.InputByteBuffer

object EmptyInternalChannel : InternalChannel {
	override val id: Any = 0
	
	override fun useProcessor(processor: ChannelProcessor) {
	}
	
	override fun useProcessor(processor: ChannelProcessor, activityTimeoutSeconds: Int) {
	}
	
	override fun closeWithMarker(marker: Byte) {
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
}