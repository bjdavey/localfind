package com.localfind.data

import android.content.Context
import com.localfind.embed.Embedder
import com.localfind.index.Indexer
import com.localfind.search.Searcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Singleton coordinator for on-device AI inference and local vector storage.
 *
 * Architecture and Concurrency:
 * - Single Model Instance: The EmbeddingGemma 2 740M multimodal model consumes ~485MB of RAM.
 *   Loading multiple instances simultaneously risks Out Of Memory (OOM) crashes on low-to-mid range devices.
 *   [EngineManager] ensures only a single instance is instantiated and kept warm in memory.
 * - Thread Safety: MediaPipe / LiteRT native C++ inference and SQLite operations are safely synchronized
 *   across UI threads, background Coroutines, and WorkManager tasks via [engineMutex].
 * - Non-Blocking Search vs Background Indexing: By structuring operations through [withLock],
 *   UI search queries and background WorkManager file indexing coordinate cooperatively.
 * - 100% Offline: Operates strictly with on-device model assets and local SQLite databases.
 */
object EngineManager {
    private val engineMutex = Mutex()
    private var embedder: Embedder? = null
    private var store: EmbeddingStore? = null

    /**
     * Obtains or creates the singleton [EmbeddingStore] instance.
     */
    fun getStore(context: Context): EmbeddingStore {
        return store ?: synchronized(this) {
            store ?: EmbeddingStore.create(context.applicationContext).also { store = it }
        }
    }

    /**
     * Obtains or creates the singleton [Embedder] instance.
     * Note: Initializing loads the ~485MB model file from filesDir.
     */
    fun getEmbedder(context: Context): Embedder {
        return embedder ?: synchronized(this) {
            embedder ?: Embedder(context.applicationContext).also { embedder = it }
        }
    }

    /**
     * Executes a suspending block with exclusive access to [EmbeddingStore] without initializing [Embedder].
     */
    suspend fun <T> withStore(
        context: Context,
        block: suspend (store: EmbeddingStore) -> T
    ): T {
        return engineMutex.withLock {
            val st = getStore(context)
            block(st)
        }
    }

    /**
     * Executes a suspending block with exclusive, thread-safe access to the shared [Embedder]
     * and [EmbeddingStore] under the mutual exclusion lock.
     */
    suspend fun <T> withLock(
        context: Context,
        block: suspend (embedder: Embedder, store: EmbeddingStore) -> T
    ): T {
        return engineMutex.withLock {
            val emb = getEmbedder(context)
            val st = getStore(context)
            block(emb, st)
        }
    }

    /**
     * Executes an indexing operation with a freshly configured [Indexer] under the lock.
     */
    suspend fun <T> withIndexer(
        context: Context,
        settings: AppSettings = AppSettings(),
        block: suspend (indexer: Indexer) -> T
    ): T {
        return withLock(context) { emb, st ->
            val indexer = Indexer(context.applicationContext, emb, st, settings)
            block(indexer)
        }
    }

    /**
     * Executes a semantic search query with [Searcher] under the lock.
     */
    suspend fun <T> withSearcher(
        context: Context,
        block: suspend (searcher: Searcher) -> T
    ): T {
        return withLock(context) { emb, st ->
            val searcher = Searcher(st, emb)
            block(searcher)
        }
    }

    /**
     * Releases native memory and closes open database connections.
     * Intended for application teardown or testing teardown.
     */
    fun close() {
        try {
            embedder?.close()
        } catch (_: Exception) {
            // Ignore teardown errors
        }
        embedder = null

        try {
            store?.close()
        } catch (_: Exception) {
            // Ignore teardown errors
        }
        store = null
    }
}

