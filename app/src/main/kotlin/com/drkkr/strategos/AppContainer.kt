package com.drkkr.strategos

import android.content.Context
import com.drkkr.strategos.agent.AgentAssets
import com.drkkr.strategos.agent.CounselDeps
import com.drkkr.strategos.agent.KeyVault
import com.drkkr.strategos.data.db.DatabaseKeyManager
import com.drkkr.strategos.data.db.DbFactory
import com.drkkr.strategos.data.db.StrategosDatabase
import com.drkkr.strategos.data.migration.LegacySource
import com.drkkr.strategos.data.migration.LegacyStorageMigrator
import com.drkkr.strategos.data.repo.ChangeSetRepository
import com.drkkr.strategos.data.repo.ConversationRepository
import com.drkkr.strategos.data.repo.GuidanceRepository
import com.drkkr.strategos.data.repo.MemoryRepository
import com.drkkr.strategos.data.repo.ProjectBrainRepository
import com.drkkr.strategos.data.repo.ScenarioRepository
import com.drkkr.strategos.data.repo.SettingsRepository
import com.drkkr.strategos.data.secure.KeystoreCipher
import com.drkkr.strategos.data.secure.SecretStore
import com.drkkr.strategos.data.storage.LoadResult
import com.drkkr.strategos.data.storage.ScenarioStorage
import com.drkkr.strategos.domain.model.AnalysisSnapshot
import com.drkkr.strategos.llm.LlmClient
import com.drkkr.strategos.llm.ChatCompletionsClient
import com.drkkr.strategos.agent.JournalScreenerService
import com.drkkr.strategos.data.repo.JournalRepository
import com.drkkr.strategos.data.repo.TrajectoryRepository
import com.drkkr.strategos.data.secure.KeystoreTextCipher
import com.drkkr.strategos.data.secure.TextCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manual wiring (no DI framework). Everything is lazy so startup stays fast.
 * [openDatabase] and [llmOverride] exist so Robolectric tests can run without the Android Keystore or the network.
 */
class AppContainer(
    private val context: Context,
    private val openDatabase: (Context) -> StrategosDatabase = { DbFactory.open(it, DatabaseKeyManager(it).getOrCreatePassphrase()) },
    private val llmOverride: LlmClient? = null,
    private val journalCipherOverride: TextCipher? = null
) {

    private class DbHolder(val db: StrategosDatabase?, val error: String?)

    // BLUEPRINT-QUESTION: DatabaseKeyManager throws the existing data.storage.StorageUnavailableException (the blueprint names one class; this reuses it).
    private val dbHolder: DbHolder by lazy {
        try {
            DbHolder(openDatabase(context), null)
        } catch (e: Exception) {
            DbHolder(null, e.message ?: "Secure storage unavailable")
        }
    }

    /** Null when secure storage could not be opened. Callers must treat that as "storage unavailable". */
    val database: StrategosDatabase? get() = dbHolder.db
    val storageError: String? get() = dbHolder.error

    val scenarioRepository: ScenarioRepository by lazy { ScenarioRepository(database, storageError) }
    val settingsRepository: SettingsRepository? by lazy { database?.let { SettingsRepository(it) } }

    val agentAssets: AgentAssets by lazy { AgentAssets(context) }

    val secretStore: SecretStore? by lazy { database?.let { SecretStore(it.settingDao(), KeystoreCipher(SECRET_KEY_ALIAS)) } }

    /** SecretStore as a KeyVault for the AI settings screen. */
    val keyVault: KeyVault? by lazy {
        secretStore?.let { store ->
            object : KeyVault {
                override suspend fun put(key: String) = store.putXaiKey(key)
                override suspend fun get(): String? = store.getXaiKey()
                override suspend fun clear() = store.clearXaiKey()
                override suspend fun has(): Boolean = store.hasXaiKey()
            }
        }
    }

    val llmClient: LlmClient by lazy {
        // DeepSeek speaks Chat Completions. XaiResponsesClient stays in the code for a Responses-API provider.
        llmOverride ?: ChatCompletionsClient(keyProvider = { secretStore?.getXaiKey() }, callLog = database?.llmCallDao())
    }

    // Argon: Space 2 (journal) and Space 3 (trajectory).
    val trajectoryRepository: TrajectoryRepository? by lazy { database?.let { TrajectoryRepository(it, scenarioRepository) } }

    /** Overview "What the app knows": read model over the scenario + free user notes. */
    val projectBrainRepository: ProjectBrainRepository? by lazy {
        database?.let { ProjectBrainRepository(scenarioRepository, MemoryRepository(it)) }
    }

    /** Overview "What to do": standing Do's/Don'ts merged across threads and applied journal takeaways. */
    val guidanceRepository: GuidanceRepository? by lazy { database?.let { GuidanceRepository(it) } }

    private val journalCipher: TextCipher by lazy { journalCipherOverride ?: KeystoreTextCipher() }

    val journalRepository: JournalRepository? by lazy {
        val db = database ?: return@lazy null
        val settings = settingsRepository ?: return@lazy null
        JournalRepository(db, journalCipher, MemoryRepository(db), ConversationRepository(db), aiEnabled = { settings.aiReady() })
    }

    val journalScreener: JournalScreenerService? by lazy {
        val journal = journalRepository ?: return@lazy null
        val trajectory = trajectoryRepository ?: return@lazy null
        val settings = settingsRepository ?: return@lazy null
        JournalScreenerService(
            llmClient, journal, scenarioRepository, trajectory, settings = { settings.aiReady() to settings.model() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        )
    }

    /** Null when secure storage is unavailable: Counsel can't run without its database. */
    val counselDeps: CounselDeps? by lazy {
        val db = database ?: return@lazy null
        val settings = settingsRepository ?: return@lazy null
        CounselDeps(
            llm = llmClient,
            systemPrompt = { agentAssets.systemPrompt },
            tools = { agentAssets.tools },
            agentVersion = { agentAssets.agentVersion },
            conversations = ConversationRepository(db),
            changeSets = ChangeSetRepository(db),
            memory = MemoryRepository(db),
            settings = settings,
            whatIfs = db.whatIfDao(),
            hasKey = { settings.get(SettingsRepository.SECRET_XAI_KEY) != null },
            oneShot = true,     // SIMPLE PROFILE
            coach = true,       // COMPANION REPLY
            trajectory = trajectoryRepository, journal = journalRepository, journalScreener = journalScreener
        )
    }

    private val migrationLock = Mutex()
    private var migrationDone = false

    /** Runs the 1.x -> Room migration once per process (and once ever, via the `legacy.migrated` flag). Call before `load()`. */
    suspend fun prepareStorage() = migrationLock.withLock {
        if (migrationDone) return@withLock
        val db = database
        val settings = settingsRepository
        if (db != null && settings != null) {
            withContext(Dispatchers.IO) { LegacyStorageMigrator(db, settings) { legacySource() }.migrateIfNeeded() }
        }
        migrationDone = true
    }

    /** Null on a fresh install, so we never create a 1.x master key just to find nothing. */
    private fun legacySource(): LegacySource? {
        val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/strategos_scenarios.xml")
        if (!prefsFile.exists()) return null
        val storage = ScenarioStorage(context)
        return object : LegacySource {
            override fun loadAll(): LoadResult = storage.loadAll()
            override fun loadSnapshots(scenarioId: String): List<AnalysisSnapshot> = storage.loadSnapshots(scenarioId)
        }
    }

    private companion object { const val SECRET_KEY_ALIAS = "strategos_secret_v1" }
}
