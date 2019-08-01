package ru.capjack.csi.core.client.internal

import ru.capjack.csi.core.Connection
import ru.capjack.csi.core.ConnectionCloseReason
import ru.capjack.csi.core.ConnectionHandler
import ru.capjack.csi.core.OutgoingMessage
import ru.capjack.csi.core.OutgoingMessageBuffer
import ru.capjack.csi.core.ProtocolFlag
import ru.capjack.csi.core.client.ClientAcceptor
import ru.capjack.csi.core.client.ClientDisconnectReason
import ru.capjack.csi.core.client.ClientHandler
import ru.capjack.csi.core.client.ConnectionAcceptor
import ru.capjack.csi.core.client.ConnectionProducer
import ru.capjack.csi.core.client.ConnectionRecoveryHandler
import ru.capjack.tool.io.FramedInputByteBuffer
import ru.capjack.tool.io.InputByteBuffer
import ru.capjack.tool.io.putInt
import ru.capjack.tool.io.readToArray
import ru.capjack.tool.lang.alsoIf
import ru.capjack.tool.lang.make
import ru.capjack.tool.logging.Logger
import ru.capjack.tool.logging.ownLogger
import ru.capjack.tool.logging.trace
import ru.capjack.tool.logging.wrap
import ru.capjack.tool.utils.concurrency.LivingWorker
import ru.capjack.tool.utils.concurrency.ScheduledExecutor
import ru.capjack.tool.utils.concurrency.accessOrExecuteOnLive
import ru.capjack.tool.utils.concurrency.executeOnLive
import ru.capjack.tool.utils.concurrency.withCapture

internal class InternalClientImpl(
	private val executor: ScheduledExecutor,
	private val connectionProducer: ConnectionProducer,
	private var delegate: ConnectionDelegate,
	private var sessionId: ByteArray,
	private val activityTimeoutMillis: Int
) : InternalClient, ConnectionProcessor {
	
	private val logger: Logger = ownLogger.wrap { "[${worker.alive.make("${delegate.connectionId}", "dead")}] $it" }
	private val worker = LivingWorker(executor, ::syncHandleError)
	
	private var processor: InternalClientProcessor = NothingInternalClientProcessor()
	
	private var lastReceivedMessageId = 0
	private var lastReceivedMessageIdSend = false
	private var lastReceivedMessageIdPackage = ByteArray(5).apply { set(0, ProtocolFlag.MESSAGE_RECEIVED) }
	
	private val outgoingMessages = OutgoingMessageBuffer()
	
	
	override fun accept(acceptor: ClientAcceptor) {
		logger.trace { "Accept" }
		worker.execute {
			syncSetProcessor(MessagingProcessor(acceptor.acceptSuccess(this)))
		}
	}
	
	override fun processInput(delegate: ConnectionDelegate, buffer: FramedInputByteBuffer): Boolean {
		logger.trace { "Process input ${buffer.readableSize}B" }
		
		val captured = worker.withCapture {
			if (this.delegate != delegate) {
				return false
			}
			while (worker.alive && buffer.readable) {
				if (!processor.processInput(delegate, buffer)) {
					return false
				}
			}
			if (worker.alive) {
				syncSendLastReceivedMessageId()
			}
		}
		
		if (captured) {
			return worker.alive
		}
		
		delegate.deferInput()
		
		return false
	}
	
	override fun processLoss(delegate: ConnectionDelegate) {
		logger.trace { "Schedule lost" }
		
		worker.executeOnLive {
			if (this.delegate == delegate) {
				processor.processLoss()
			}
		}
	}
	
	override fun sendMessage(data: Byte) {
		logger.trace { "Schedule send message of 1B" }
		
		worker.accessOrExecuteOnLive {
			syncSendMessage(outgoingMessages.add(data))
		}
	}
	
	override fun sendMessage(data: ByteArray) {
		logger.trace { "Schedule send message of ${data.size}B" }
		
		worker.accessOrExecuteOnLive {
			syncSendMessage(outgoingMessages.add(data))
		}
	}
	
	override fun sendMessage(data: InputByteBuffer) {
		logger.trace { "Schedule send message of ${data.readableSize}B" }
		
		if (worker.alive) {
			if (worker.accessible) {
				syncSendMessage(outgoingMessages.add(data))
			}
			else {
				data.readToArray().also { bytes ->
					worker.execute {
						if (worker.alive) {
							syncSendMessage(outgoingMessages.add(bytes))
						}
					}
				}
			}
		}
	}
	
	override fun disconnect() {
		logger.trace { "Schedule disconnect" }
		
		worker.executeOnLive {
			syncSendLastReceivedMessageId()
			syncDisconnect(ClientDisconnectReason.CLOSE)
		}
	}
	
	private fun syncSetProcessor(processor: InternalClientProcessor) {
		logger.trace { "Use processor ${processor::class.simpleName}" }
		this.processor = processor
	}
	
	private fun syncSendMessage(message: OutgoingMessage) {
		processor.sendMessage(message)
	}
	
	private fun syncSendLastReceivedMessageId() {
		if (lastReceivedMessageIdSend) {
			logger.trace { "Send last received message $lastReceivedMessageId" }
			
			lastReceivedMessageIdSend = false
			lastReceivedMessageIdPackage.putInt(1, lastReceivedMessageId)
			delegate.send(lastReceivedMessageIdPackage)
		}
	}
	
	private fun syncDisconnect(reason: ClientDisconnectReason) {
		logger.trace { "Disconnect by $reason" }
		
		worker.die()
		
		processor.processDisconnect(reason)
		
		delegate.close()
		delegate = DummyConnectionDelegate()
		syncSetProcessor(NothingInternalClientProcessor())
		
		outgoingMessages.clear()
	}
	
	private fun syncHandleError(t: Throwable) {
		logger.error("Uncaught exception", t)
		if (worker.alive) {
			syncDisconnect(ClientDisconnectReason.CLIENT_ERROR)
		}
	}
	
	
	///
	
	private enum class MessagingInputState {
		MESSAGE_ID,
		MESSAGE_BODY,
		MESSAGE_RECEIVED,
		SHUTDOWN_TIMEOUT
	}
	
	private inner class MessagingProcessor(private val handler: ClientHandler) : AbstractInputProcessor(),
		InternalClientProcessor {
		
		private var active = true
		private val activeChecker = executor.repeat(activityTimeoutMillis / 2, ::checkActivity)
		
		private var inputState = MessagingInputState.MESSAGE_ID
		private var inputMessageId = 0
		
		override fun sendMessage(message: OutgoingMessage) {
			logger.trace { "Send message id ${message.id}" }
			delegate.send(message.data)
		}
		
		override fun processInput(delegate: ConnectionDelegate, buffer: FramedInputByteBuffer): Boolean {
			active = true
			return super.processInput(delegate, buffer)
		}
		
		override fun processInputFlag(delegate: ConnectionDelegate, flag: Byte): Boolean {
			return when (flag) {
				ProtocolFlag.MESSAGE                 -> {
					inputState = MessagingInputState.MESSAGE_ID
					switchToBody()
					true
				}
				ProtocolFlag.MESSAGE_RECEIVED        -> {
					inputState = MessagingInputState.MESSAGE_RECEIVED
					switchToBody()
					true
				}
				ProtocolFlag.PING                    -> {
					true
				}
				ProtocolFlag.SERVER_SHUTDOWN_TIMEOUT -> {
					inputState = MessagingInputState.SHUTDOWN_TIMEOUT
					switchToBody()
					true
				}
				else                                 ->
					super.processInputFlag(delegate, flag)
			}
		}
		
		override fun processInputBody(delegate: ConnectionDelegate, buffer: FramedInputByteBuffer): Boolean {
			return when (inputState) {
				MessagingInputState.MESSAGE_ID       -> processInputMessageId(buffer)
				MessagingInputState.MESSAGE_BODY     -> processInputMessageBody(buffer.frameView)
				MessagingInputState.MESSAGE_RECEIVED -> processInputMessageReceived(buffer)
				MessagingInputState.SHUTDOWN_TIMEOUT -> processInputShutdownTimeout(buffer)
			}
		}
		
		private fun processInputMessageId(buffer: InputByteBuffer): Boolean {
			return buffer.isReadable(4).alsoIf {
				inputMessageId = buffer.readInt()
				inputState = MessagingInputState.MESSAGE_BODY
			}
		}
		
		private fun processInputMessageBody(frameBuffer: InputByteBuffer): Boolean {
			return frameBuffer.readable.alsoIf {
				logger.trace { "Receive message $inputMessageId" }
				lastReceivedMessageId = inputMessageId
				lastReceivedMessageIdSend = true
				switchToFlag()
				try {
					handler.handleMessage(frameBuffer)
				}
				catch (t: Throwable) {
					syncHandleError(t)
				}
			}
		}
		
		private fun processInputMessageReceived(buffer: InputByteBuffer): Boolean {
			return buffer.isReadable(4).alsoIf {
				val messageId = buffer.readInt()
				logger.trace { "Sent message $messageId delivered" }
				outgoingMessages.clearTo(messageId)
				switchToFlag()
			}
		}
		
		private fun processInputShutdownTimeout(buffer: InputByteBuffer): Boolean {
			return buffer.isReadable(4).alsoIf {
				val timeoutMillis = buffer.readInt()
				inputState = MessagingInputState.MESSAGE_ID
				switchToFlag()
				try {
					handler.handleServerShutdownTimeout(timeoutMillis)
				}
				catch (t: Throwable) {
					syncHandleError(t)
				}
			}
		}
		
		override fun processInputClose(reason: ConnectionCloseReason) {
			val disconnectReason = when (reason) {
				ConnectionCloseReason.CLOSE                    -> ClientDisconnectReason.CLOSE
				ConnectionCloseReason.SERVER_SHUTDOWN          -> ClientDisconnectReason.SERVER_SHUTDOWN
				ConnectionCloseReason.ACTIVITY_TIMEOUT_EXPIRED -> ClientDisconnectReason.CONNECTION_LOST
				ConnectionCloseReason.CONCURRENT               -> ClientDisconnectReason.CONCURRENT
				ConnectionCloseReason.PROTOCOL_BROKEN          -> ClientDisconnectReason.PROTOCOL_BROKEN
				ConnectionCloseReason.SERVER_ERROR             -> ClientDisconnectReason.SERVER_ERROR
				else                                           -> {
					logger.error("Unexpected close reason $reason")
					ClientDisconnectReason.PROTOCOL_BROKEN
				}
			}
			
			syncDisconnect(disconnectReason)
		}
		
		override fun processDisconnect(reason: ClientDisconnectReason) {
			activeChecker.cancel()
			try {
				handler.handleDisconnect(reason)
			}
			catch (t: Throwable) {
				syncHandleError(t)
			}
		}
		
		override fun processLoss() {
			activeChecker.cancel()
			delegate = DummyConnectionDelegate()
			
			try {
				val recoveryHandler = handler.handleConnectionLost()
				val recoveryProcessor = RecoveryProcessor(handler, recoveryHandler)
				
				syncSetProcessor(recoveryProcessor)
				executor.schedule(100) {
					if (worker.alive) {
						connectionProducer.produceConnection(recoveryProcessor)
					}
				}
			}
			catch (t: Throwable) {
				syncHandleError(t)
			}
		}
		
		private fun checkActivity() {
			worker.execute {
				if (worker.alive) {
					if (active) {
						active = false
						delegate.send(ProtocolFlag.PING)
					}
					else {
						delegate.terminate()
						processLoss()
					}
				}
			}
		}
	}
	
	private inner class RecoveryProcessor(
		private val handler: ClientHandler,
		private val recoveryHandler: ConnectionRecoveryHandler
	) : AbstractInputProcessor(), InternalClientProcessor,
		ConnectionAcceptor {
		private val timeout = executor.schedule(activityTimeoutMillis, ::fail)
		
		override fun acceptSuccess(connection: Connection): ConnectionHandler {
			val d = ConnectionDelegateImpl(executor, connection, this@InternalClientImpl)
			
			worker.defer {
				if (worker.alive) {
					delegate = d
					delegate.send(ByteArray(1 + 16 + 4).also {
						it[0] = ProtocolFlag.RECOVERY
						sessionId.copyInto(it, 1)
						it.putInt(17, lastReceivedMessageId)
					})
				}
				else {
					d.close()
				}
			}
			
			return d
		}
		
		override fun acceptFail() {
			fail()
		}
		
		override fun sendMessage(message: OutgoingMessage) {}
		
		override fun processInputFlag(delegate: ConnectionDelegate, flag: Byte): Boolean {
			return if (flag == ProtocolFlag.RECOVERY) {
				switchToBody()
				true
			}
			else super.processInputFlag(delegate, flag)
		}
		
		override fun processInputBody(delegate: ConnectionDelegate, buffer: FramedInputByteBuffer): Boolean {
			return buffer.isReadable(4).alsoIf {
				timeout.cancel()
				
				sessionId = buffer.readToArray(16)
				
				val messageId = buffer.readInt()
				
				syncSetProcessor(MessagingProcessor(handler))
				
				outgoingMessages.clearTo(messageId)
				outgoingMessages.forEach(::syncSendMessage)
				
				recoveryHandler.handleConnectionRecovered()
				
				switchToFlag()
			}
		}
		
		override fun processInputClose(reason: ConnectionCloseReason) {
			val disconnectReason = when (reason) {
				ConnectionCloseReason.RECOVERY_REJECT          -> ClientDisconnectReason.CONNECTION_LOST
				ConnectionCloseReason.CLOSE                    -> ClientDisconnectReason.CLOSE
				ConnectionCloseReason.SERVER_SHUTDOWN          -> ClientDisconnectReason.SERVER_SHUTDOWN
				ConnectionCloseReason.ACTIVITY_TIMEOUT_EXPIRED -> ClientDisconnectReason.CONNECTION_LOST
				ConnectionCloseReason.CONCURRENT               -> ClientDisconnectReason.CONCURRENT
				ConnectionCloseReason.PROTOCOL_BROKEN          -> ClientDisconnectReason.PROTOCOL_BROKEN
				ConnectionCloseReason.SERVER_ERROR             -> ClientDisconnectReason.SERVER_ERROR
				else                                           -> {
					logger.error("Unexpected close reason $reason")
					ClientDisconnectReason.PROTOCOL_BROKEN
				}
			}
			
			syncDisconnect(disconnectReason)
		}
		
		override fun processDisconnect(reason: ClientDisconnectReason) {
			try {
				handler.handleDisconnect(reason)
			}
			catch (t: Throwable) {
				syncHandleError(t)
			}
		}
		
		override fun processLoss() {
			delegate.terminate()
			syncDisconnect(ClientDisconnectReason.CONNECTION_LOST)
		}
		
		private fun fail() {
			timeout.cancel()
			worker.execute {
				if (worker.alive) {
					delegate.terminate()
					syncDisconnect(ClientDisconnectReason.CONNECTION_LOST)
				}
			}
		}
	}
	
}