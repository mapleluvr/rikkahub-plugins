package me.rerere.rikkahub.plugins

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class WorkspaceSnapshotAdmissionTest {
    @Test fun policyTighteningBetweenReadsCannotCombineOldApprovalWithNewResource() = runBlocking {
        val koin = GlobalContext.get()
        val first = WorkspaceEntity(
            "snapshot-fixture",
            "fixture",
            "fixture",
            createdAt = 0,
            updatedAt = 0,
            storageMounts = """[{"path":"/storage/emulated/0/fixture","writable":true}]"""
        )
        val tightened = first.copy(storageMounts = """[{"path":"/storage/emulated/0/fixture","writable":false}]""")
        var reads = 0
        val changing = object : WorkspaceDAO by koin.get<WorkspaceDAO>() {
            override suspend fun getById(id: String): WorkspaceEntity = if (reads++ == 0) first else tightened
        }
        val repository = WorkspaceRepository(changing, koin.get(), koin.get(), koin.get(), koin.get())
        val tool = createWorkspaceTools(first.id, repository).single { it.name == "workspace_write_file" }
        val input =
            buildJsonObject {
                put("path", "/storage/emulated/0/fixture/a.txt")
                put("content", "never written")
            }
        assertTrue(
            "tightened resource must require approval or invalidate the captured registration",
            tool.needsApproval(input) || tool.validateExecution() != null
        )
        // No backend or file effect is needed to expose the mismatch.
    }
}
