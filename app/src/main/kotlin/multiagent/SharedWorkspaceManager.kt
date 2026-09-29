package multiagent

import memory.AesGcmCipher
import memory.StandardKeystoreKeyProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GEN-MA-8 — Shared Workspace
 * Scoped intermediate storage for multi-agent roles working on an Async Job.
 *
 * Invariant Rules:
 * 1. AES-256 GCM encrypted at rest (same as GEN-MEM-6 / GEN-578).
 * 2. Scoped strictly by jobId (no code outside memory/workspace queries it directly).
 * 3. Excluded from general embedding/cosine retrieval (will never pollute daily chats).
 * 4. Panic Reset purges active workspace completely.
 * 5. Lifecycle eviction: When jobs are closed > 7 days, intermediate workspace data is deleted.
 */
data class WorkspaceArtifact(
    val artifactId: String,
    val jobId: String,
    val roleName: String,
    val content: String,
    val timestampMs: Long
)

class SharedWorkspaceManager(
    private val cipher: AesGcmCipher = AesGcmCipher(
        keyProvider = StandardKeystoreKeyProvider(),
        keyAlias = "android_agent_workspace_vault_key"
    ),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    // Stores encrypted artifact content keyed by jobId -> List<EncryptedArtifactRow>
    private data class EncryptedArtifactRow(
        val artifactId: String,
        val jobId: String,
        val roleName: String,
        val encryptedContent: String,
        val timestampMs: Long
    )

    private val workspaceVault = ConcurrentHashMap<String, CopyOnWriteArrayList<EncryptedArtifactRow>>()

    fun saveRoleOutput(jobId: String, roleName: String, content: String): WorkspaceArtifact {
        val artifactId = "art-${clock()}-${(100..999).random()}"
        val encrypted = cipher.encrypt(content)
        val row = EncryptedArtifactRow(
            artifactId = artifactId,
            jobId = jobId,
            roleName = roleName,
            encryptedContent = encrypted,
            timestampMs = clock()
        )
        workspaceVault.getOrPut(jobId) { CopyOnWriteArrayList() }.add(row)
        return WorkspaceArtifact(artifactId, jobId, roleName, content, row.timestampMs)
    }

    fun getArtifactsForJob(jobId: String): List<WorkspaceArtifact> {
        val rows = workspaceVault[jobId] ?: return emptyList()
        return rows.map { row ->
            val decrypted = cipher.decrypt(row.encryptedContent)
            WorkspaceArtifact(row.artifactId, row.jobId, row.roleName, decrypted, row.timestampMs)
        }
    }

    fun purgeJobWorkspace(jobId: String): Boolean {
        return workspaceVault.remove(jobId) != null
    }

    fun purgeAllActiveWorkspaces() {
        workspaceVault.clear()
        runtime.AgentLogger.w("SharedWorkspace", "All shared workspace data purged (Panic Reset).")
    }

    /**
     * Periodic retention cleanup: Removes intermediate data for closed jobs older than retentionDays (7 days).
     */
    fun pruneOldJobWorkspaces(closedJobIdsOlderThanRetention: List<String>): Int {
        var count = 0
        for (jobId in closedJobIdsOlderThanRetention) {
            if (workspaceVault.remove(jobId) != null) {
                count++
            }
        }
        return count
    }
}
