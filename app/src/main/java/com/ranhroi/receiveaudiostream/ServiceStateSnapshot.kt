package com.ranhroi.receiveaudiostream
data class ServiceStateSnapshot(
    val isRunning: Boolean = false,
    val receiverState: ReceiverState = ReceiverState.SLEEP,
    val port: Int = 2027,
    val sampleRate: Int = 48000,
    val isStereo: Boolean = true,
    val rmsThreshold: Float = 30.0f,
    val currentRms: Double = 0.0,
    val packetCount: Long = 0,
    val totalBytesReceived: Long = 0,
    val lastPacketTime: Long = 0,
    val statusLogs: List<String> = emptyList(),
    val isForcedPlay: Boolean = false
)