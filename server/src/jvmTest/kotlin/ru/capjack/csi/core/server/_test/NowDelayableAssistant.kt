package ru.capjack.csi.core.server._test

import ru.capjack.tool.utils.Cancelable
import ru.capjack.tool.utils.assistant.TemporalAssistant
import java.util.concurrent.TimeUnit

object NowTemporalAssistant : TemporalAssistant {
	override fun execute(code: () -> Unit) {
		code.invoke()
	}
	
	override fun repeat(delayMillis: Int, code: () -> Unit): Cancelable {
		code.invoke()
		return Cancelable.DUMMY
	}
	
	override fun schedule(delayMillis: Int, code: () -> Unit): Cancelable {
		code.invoke()
		return Cancelable.DUMMY
	}
	
	override fun charge(code: () -> Unit): Cancelable {
		code.invoke()
		return Cancelable.DUMMY
	}
	
	override fun repeat(delay: Long, unit: TimeUnit, code: () -> Unit): Cancelable {
		code.invoke()
		return Cancelable.DUMMY
	}
	
	override fun schedule(delay: Long, unit: TimeUnit, code: () -> Unit): Cancelable {
		code.invoke()
		return Cancelable.DUMMY
	}
}