package com.coursekeeper.agent

import com.coursekeeper.data.CourseDraft
import com.coursekeeper.data.DataTestBase
import com.coursekeeper.data.TaskDraft
import com.coursekeeper.data.model.TaskType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * A03 写入工具：预览不写库、确认生效、凭证单次/过期/绑定、版本冲突、非法字段、完成与恢复语义。
 *
 * 用真实 Repository（Room in-memory + 固定时钟）而不是假 gateway：
 * 幂等（requestId）、版本冲突、completedAt 由 Repository 写，这些语义只有真实数据层才验得准。
 */
class TaskWriteToolsTest : DataTestBase() {

    private fun tools() = TaskWriteTools(
        gateway = RepositoryTaskWriteGateway(repository),
        now = { LocalDateTime.now(clock) },
    )

    private fun now(): LocalDateTime = LocalDateTime.now(clock)

    private fun ready(result: TaskWritePreviewResult): TaskWritePreview =
        (result as? TaskWritePreviewResult.Ready)?.preview
            ?: error("expected Ready, got $result")

    private fun failure(result: TaskWriteResult): TaskWriteFailure =
        (result as? TaskWriteResult.Failure)?.failure
            ?: error("expected Failure, got $result")

    private fun success(result: TaskWriteResult) =
        (result as? TaskWriteResult.Success)?.receipt
            ?: error("expected Success, got $result")

    // ---------------------------------------------------------------- 1. 预览不写库

    @Test
    fun `create preview does not write anything`() = runTest {
        val t = tools()
        val preview = ready(t.previewCreate(draft("报告")))

        assertTrue(repository.snapshot().tasks.isEmpty())
        assertEquals("报告", preview.changes.first { it.field == "title" }.after)
        assertNull(preview.target)
        assertNotNull(preview.token)
    }

    @Test
    fun `update preview lists only changed fields and keeps store untouched`() = runTest {
        val task = repository.createTask(draft("旧标题"))
        val preview = ready(tools().previewUpdate(task.id, task.version, draft("新标题")))

        assertEquals(1, preview.changes.size)
        assertEquals("旧标题", preview.changes.single().before)
        assertEquals("新标题", preview.changes.single().after)
        assertEquals(task.id, preview.target?.id)
        assertEquals(1L, preview.target?.version)
        val stored = repository.getTask(task.id)!!
        assertEquals("旧标题", stored.title)
        assertEquals(1L, stored.version)
    }

    @Test
    fun `complete preview keeps store untouched`() = runTest {
        val task = repository.createTask(draft())
        val preview = ready(tools().previewComplete(task.id, true, task.version))

        assertNull(repository.getTask(task.id)!!.completedAt)
        assertEquals("待办", preview.changes.single().before)
        assertEquals("已完成", preview.changes.single().after)
    }

    // ---------------------------------------------------------------- 2. 确认后写入且与预览一致

    @Test
    fun `confirmed create writes the previewed values`() = runTest {
        val due = now().plusDays(2)
        val t = tools()
        val preview = ready(t.previewCreate(TaskDraft(" 报告 ", TaskType.LAB_REPORT, due)))
        val receipt = success(t.confirm(preview.token))

        assertTrue(receipt.changed)
        assertEquals(1L, receipt.version)
        val stored = repository.getTask(receipt.id)!!
        assertEquals("报告", stored.title)
        assertEquals(TaskType.LAB_REPORT, stored.type)
        assertEquals(due, stored.dueAt)
        assertEquals(1, repository.snapshot().tasks.size)
    }

    @Test
    fun `confirmed update writes and bumps version with manualDirty`() = runTest {
        val task = repository.createTask(draft("旧"))
        val t = tools()
        val preview = ready(t.previewUpdate(task.id, task.version, draft("新")))
        val receipt = success(t.confirm(preview.token))

        assertEquals(2L, receipt.version)
        val stored = repository.getTask(task.id)!!
        assertEquals("新", stored.title)
        assertEquals(2L, stored.version)
        assertTrue(stored.manualDirty)
    }

    // ---------------------------------------------------------------- 3. 凭证：单次 / 过期 / 绑定

    @Test
    fun `token is single use`() = runTest {
        val t = tools()
        val preview = ready(t.previewCreate(draft("一次性")))
        success(t.confirm(preview.token))

        val again = failure(t.confirm(preview.token))
        assertEquals(TaskWriteError.TOKEN_MISMATCH, again.error)
        assertEquals(1, repository.snapshot().tasks.size)
    }

    @Test
    fun `token expires after five minutes`() = runTest {
        val t = tools()
        val preview = ready(t.previewCreate(draft("过期")))
        clock.current = clock.current.plusSeconds(301)

        val failure = failure(t.confirm(preview.token))
        assertEquals(TaskWriteError.EXPIRED_TOKEN, failure.error)
        assertTrue(repository.snapshot().tasks.isEmpty())
    }

    @Test
    fun `same token cannot be used against another task`() = runTest {
        val a = repository.createTask(draft("A"))
        val b = repository.createTask(draft("B"))
        val t = tools()
        val preview = ready(t.previewUpdate(a.id, 1, draft("A2")))

        val failure = failure(t.confirm(preview.token, preview.binding.copy(targetId = b.id)))
        assertEquals(TaskWriteError.TOKEN_MISMATCH, failure.error)
        assertEquals("A", repository.getTask(a.id)!!.title)
        assertEquals("B", repository.getTask(b.id)!!.title)

        // 绑定不符不消耗凭证：用正确绑定仍能完成
        val receipt = success(t.confirm(preview.token, preview.binding))
        assertEquals("A2", repository.getTask(a.id)!!.title)
        assertEquals(2L, receipt.version)
    }

    @Test
    fun `same token cannot be used with a different change fingerprint`() = runTest {
        val task = repository.createTask(draft("原"))
        val t = tools()
        val preview = ready(t.previewUpdate(task.id, 1, draft("改")))
        val tampered = preview.binding.copy(changeFingerprint = "tampered")

        assertEquals(TaskWriteError.TOKEN_MISMATCH, failure(t.confirm(preview.token, tampered)).error)
        assertEquals("原", repository.getTask(task.id)!!.title)
    }

    // ---------------------------------------------------------------- 4. 版本冲突不覆盖

    @Test
    fun `stale expected version is rejected without overwriting`() = runTest {
        val task = repository.createTask(draft("原"))
        val t = tools()
        val preview = ready(t.previewUpdate(task.id, task.version, draft("工具改")))
        repository.updateTask(task.id, 1, draft("外部改")) // 预览后有人改了

        val failure = failure(t.confirm(preview.token))
        assertEquals(TaskWriteError.VERSION_CONFLICT, failure.error)
        val stored = repository.getTask(task.id)!!
        assertEquals("外部改", stored.title)
        assertEquals(2L, stored.version)
    }

    @Test
    fun `stale expected version is rejected already at preview`() = runTest {
        val task = repository.createTask(draft("原"))
        repository.updateTask(task.id, 1, draft("外部改")) // 调用方手里的版本已过期
        val t = tools()
        assertEquals(
            TaskWriteError.VERSION_CONFLICT,
            (t.previewUpdate(task.id, 1, draft("工具改")) as TaskWritePreviewResult.Rejected).failure.error,
        )
        assertEquals(
            TaskWriteError.VERSION_CONFLICT,
            (t.previewComplete(task.id, true, 1) as TaskWritePreviewResult.Rejected).failure.error,
        )
        assertEquals("外部改", repository.getTask(task.id)!!.title)
    }

    // ---------------------------------------------------------------- 5. 幂等（同一 token 只生效一次）

    @Test
    fun `repeating the same confirmation never writes twice`() = runTest {
        val t = tools()
        val preview = ready(t.previewCreate(draft("只一次")))
        success(t.confirm(preview.token))
        repeat(3) { assertEquals(TaskWriteError.TOKEN_MISMATCH, failure(t.confirm(preview.token)).error) }

        val tasks = repository.snapshot().tasks
        assertEquals(1, tasks.size)
        assertEquals(1L, tasks.single().version)
    }

    // ---------------------------------------------------------------- 6. 非法字段在预览阶段即拒

    @Test
    fun `blank title is rejected at preview`() = runTest {
        val result = tools().previewCreate(draft("   "))
        assertEquals(
            TaskWriteError.INVALID_FIELD,
            (result as TaskWritePreviewResult.Rejected).failure.error,
        )
        assertTrue(repository.snapshot().tasks.isEmpty())
    }

    @Test
    fun `too long title is rejected at preview`() = runTest {
        val result = tools().previewCreate(draft("长".repeat(61)))
        assertEquals(
            TaskWriteError.INVALID_FIELD,
            (result as TaskWritePreviewResult.Rejected).failure.error,
        )
        assertTrue(repository.snapshot().tasks.isEmpty())
    }

    @Test
    fun `unknown course is rejected at preview`() = runTest {
        val result = tools().previewCreate(draft("作业").copy(courseId = "no-such-course"))
        assertEquals(
            TaskWriteError.INVALID_FIELD,
            (result as TaskWritePreviewResult.Rejected).failure.error,
        )
        assertTrue(repository.snapshot().tasks.isEmpty())
    }

    @Test
    fun `unknown task is rejected at preview`() = runTest {
        val t = tools()
        assertEquals(
            TaskWriteError.NOT_FOUND,
            (t.previewUpdate("missing", 1, draft("x")) as TaskWritePreviewResult.Rejected).failure.error,
        )
        assertEquals(
            TaskWriteError.NOT_FOUND,
            (t.previewComplete("missing", true, 1) as TaskWritePreviewResult.Rejected).failure.error,
        )
    }

    // ---------------------------------------------------------------- 7. 完成 / 恢复的 v1.1 语义

    @Test
    fun `complete writes clock instant and restore clears it`() = runTest {
        val task = repository.createTask(draft())
        val t = tools()

        val done = ready(t.previewComplete(task.id, true, 1))
        success(t.confirm(done.token))
        val completed = repository.getTask(task.id)!!
        assertEquals(now(), completed.completedAt)
        assertEquals(2L, completed.version)
        assertTrue(completed.manualDirty)

        val restore = ready(t.previewComplete(task.id, false, 2))
        success(t.confirm(restore.token))
        val restored = repository.getTask(task.id)!!
        assertNull(restored.completedAt)
        assertEquals(3L, restored.version)
    }

    @Test
    fun `completing an already completed task is a no-op`() = runTest {
        val task = repository.createTask(draft())
        repository.setTaskCompleted(task.id, true, 1)
        val t = tools()
        val preview = ready(t.previewComplete(task.id, true, 2))
        assertTrue(preview.risks.any { "已是完成状态" in it })

        val receipt = success(t.confirm(preview.token))
        assertFalse(receipt.changed)
        assertEquals(2L, receipt.version)
    }

    @Test
    fun `restore preview carries an explicit risk hint`() = runTest {
        val task = repository.createTask(draft())
        repository.setTaskCompleted(task.id, true, 1)
        val preview = ready(tools().previewComplete(task.id, false, 2))
        assertTrue(preview.risks.any { "恢复为待办" in it })
    }

    @Test
    fun `confirm after the task was deleted returns NOT_FOUND`() = runTest {
        val task = repository.createTask(draft())
        val t = tools()
        val preview = ready(t.previewComplete(task.id, true, 1))
        repository.deleteTask(task.id, 1)

        assertEquals(TaskWriteError.NOT_FOUND, failure(t.confirm(preview.token)).error)
    }

    // ---------------------------------------------------------------- 附加：风险提示与课程沿用

    @Test
    fun `create preview warns about due times with no or one reminder slot`() = runTest {
        val t = tools()
        val inThirtyMinutes = ready(t.previewCreate(draft("近").copy(dueAt = now().plusMinutes(30))))
        assertTrue(inThirtyMinutes.risks.any { "不会到点提醒" in it })

        val inTwoHours = ready(t.previewCreate(draft("两小时").copy(dueAt = now().plusHours(2))))
        assertTrue(inTwoHours.risks.any { "只有 1 小时槽位" in it })

        val past = ready(t.previewCreate(draft("已过期").copy(dueAt = now().minusHours(1))))
        assertTrue(past.risks.any { "截止时间已过" in it })
    }

    @Test
    fun `update keeps a dangling course association but rejects a new unknown one`() = runTest {
        val course = repository.createCourse(CourseDraft("高等数学"))
        val task = repository.createTask(draft("作业").copy(courseId = course.id))
        repository.deleteCourse(course.id, 1)

        val t = tools()
        // 沿用已删除的旧关联：与 Repository 同口径，允许
        val keep = ready(t.previewUpdate(task.id, 1, draft("作业2").copy(courseId = course.id)))
        success(t.confirm(keep.token))
        assertEquals("作业2", repository.getTask(task.id)!!.title)

        // 改成一个不存在的新关联：预览即拒
        val reject = t.previewUpdate(task.id, 2, draft("作业3").copy(courseId = "no-such-course"))
        assertEquals(
            TaskWriteError.INVALID_FIELD,
            (reject as TaskWritePreviewResult.Rejected).failure.error,
        )
    }
}
