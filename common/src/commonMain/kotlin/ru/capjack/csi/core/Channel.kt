package ru.capjack.csi.core

import ru.capjack.tool.io.InputByteBuffer

interface Channel {
	val id: Any
	
	fun send(data: Byte)
	
	fun send(data: ByteArray)
	
	fun send(data: InputByteBuffer)
	
	fun close()
}