package com.karelisio.echo.data.history

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val dao: EchoDao) {

    val recent: Flow<List<EchoEvent>> = dao.observeRecent(MAX_DISPLAYED)
    val lastReceived: Flow<EchoEvent?> = dao.observeLastReceived()
    val lastSent: Flow<EchoEvent?> = dao.observeLastSent()

    suspend fun lastReceivedNow(): EchoEvent? = dao.lastReceived()

    suspend fun record(direction: EchoDirection, emoji: String, pattern: String, timestamp: Long) {
        dao.insert(EchoEvent(direction = direction, emoji = emoji, pattern = pattern, timestamp = timestamp))
        dao.trimTo(MAX_STORED)
    }

    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_DISPLAYED = 500
        const val MAX_STORED = 2000
    }
}
