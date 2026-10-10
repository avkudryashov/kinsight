package app.kinsight.core.database

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/** Runs [block] in one IMMEDIATE transaction; DAO calls made inside it from the same coroutine join it. */
internal suspend fun <T> KinsightDatabase.write(block: suspend () -> T): T =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
