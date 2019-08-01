package ru.capjack.csi.core.client.stubs

import ru.capjack.csi.core.client.Client
import ru.capjack.csi.core.client.ClientDisconnectReason
import ru.capjack.csi.core.client.ClientHandler
import ru.capjack.csi.core.client.ConnectionRecoveryHandler
import ru.capjack.csi.core.client.internal.DummyConnectionRecoveryHandler
import ru.capjack.tool.io.ArrayByteBuffer
import ru.capjack.tool.io.InputByteBuffer
import ru.capjack.tool.io.readToArray
import kotlin.concurrent.thread

open class StubClientHandler(val client: Client) : ClientHandler {
	override fun handleMessage(message: InputByteBuffer) {
		when (message.readByte()) {
			0x00.toByte() -> client.disconnect()
			
			0x01.toByte() -> client.sendMessage(message.readByte())
			0x02.toByte() -> client.sendMessage(message.readToArray())
			0x03.toByte() -> client.sendMessage(ArrayByteBuffer(message))
			
			0x11.toByte() -> message.readByte().also { thread { client.sendMessage(it) } }
			0x12.toByte() -> message.readToArray().also { thread { client.sendMessage(it) } }
			0x13.toByte() -> message.readToArray().also { thread { client.sendMessage(ArrayByteBuffer(it)) } }
			
			0x04.toByte() -> Thread.sleep(message.readInt().toLong())
			
			0x05.toByte() -> throw RuntimeException("Client error")
			
			0x06.toByte() -> Unit
			
			0x07.toByte() -> {
				Thread.sleep(message.readInt().toLong())
				client.disconnect()
			}
			
			else          -> throw RuntimeException("Bad message")
		}
	}
	
	override fun handleDisconnect(reason: ClientDisconnectReason) {
	}
	
	override fun handleConnectionLost(): ConnectionRecoveryHandler {
		return DummyConnectionRecoveryHandler()
	}
	
	override fun handleServerShutdownTimeout(millis: Int) {
	}
}