package com.coursekeeper.task

import com.coursekeeper.data.model.TaskRecord
import com.coursekeeper.data.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * W6 固化样例：任务异常数据口径（纯逻辑，无需 Android 环境）。
 *
 * 每条用例都对应真机上真实可能出现的一种边界输入：空白标题、伪装成空白的全角空格、
 * 截止时间恰好在"现在"、跨零点/跨年、远期日期、筛选三条件叠加、相等排序键的稳定顺序。
 *
 * 断言分两类，不要混为一谈：
 * - **v1.0 口径**（逾期判定、筛选、排序分组、必填与长度）：实现漂移就是缺陷；
 * - **固化的当前行为/界面文案**（远期日期不显示年份、UTF-16 计数、相等 dueAt 的稳定顺序）：
 *   已标注为已知限制，它们防的是"悄悄变了却没人发现"，不代表这些行为是应有功能。
 */
class TaskAnomalyTest {

    private val now = LocalDateTime.of(2026, 11, 9, 10, 0)

    private fun task(
        id: String,
        title: String = id,
        type: TaskType = TaskType.HOMEWORK,
        dueAt: LocalDateTime? = null,
        completedAt: LocalDateTime? = null,
    ) = TaskRecord(id = id, title = title, type = type, dueAt = dueAt, completedAt = completedAt)

    private fun draft(title: String, undated: Boolean = true, dueText: String = "") =
        buildTaskDraft(title, TaskType.HOMEWORK, null, undated, dueText)

    // ---- 标题边界 ----

    @Test
    fun `title of only ascii whitespace is rejected`() {
        assertEquals("请填写任务名称", draft(" \t\n ").exceptionOrNull()?.message)
    }

    @Test
    fun `title of only ideographic spaces is rejected`() {
        // 全角空格 U+3000（中文输入法下最容易出现）。Kotlin 的 String.trim() 按
        // Char.isWhitespace() 削边，涵盖 U+3000，所以这里应当被拒。
        // 该用例真正防的是"日后有人把 trim() 换成 Java 式 trim(' ') 或只削普通空格"：
        // 那样会存进一条"看起来空白"的任务，列表里一行什么都没有，用户无法解释。
        assertEquals("请填写任务名称", draft("\u3000\u3000").exceptionOrNull()?.message)
    }

    @Test
    fun `title trims surrounding ideographic spaces but keeps inner ones`() {
        val built = draft("\u3000写 作业\u3000").getOrThrow()
        assertEquals("写 作业", built.title)
    }

    @Test
    fun `title length boundary is exactly sixty`() {
        assertEquals(60, draft("任".repeat(60)).getOrThrow().title.length)
        assertEquals("任务名称过长（≤60 字）", draft("任".repeat(61)).exceptionOrNull()?.message)
    }

    @Test
    fun `title length counts utf16 units so surrogate pairs cost two`() {
        // 已知限制（固化为样例，避免日后"看起来能过"的错觉）：长度按 UTF-16 单元计，
        // 一个 emoji 占 2，因此 30 个 emoji 正好用满 60 的上限，第 31 个被拒。
        assertEquals(60, "🎓".repeat(30).length)
        assertEquals(60, draft("🎓".repeat(30)).getOrThrow().title.length)
        assertEquals("任务名称过长（≤60 字）", draft("🎓".repeat(31)).exceptionOrNull()?.message)
    }

    // ---- 日期边界 ----

    @Test
    fun `due exactly at now is not treated as overdue`() {
        val dueNow = task("due", dueAt = now)
        // 逾期判定是严格早于：恰好在截止时刻仍算待办，文案显示"今天 HH:mm"
        assertEquals(TaskStatus.PENDING, dueNow.statusAt(now))
        assertEquals("今天 10:00", dueNow.dueLabel(now))
        assertEquals(TaskStatus.OVERDUE, task("past", dueAt = now.minusNanos(1)).statusAt(now))
    }

    @Test
    fun `due label handles the last and first minute across a year boundary`() {
        val beforeNewYear = LocalDateTime.of(2026, 12, 31, 10, 0)
        assertEquals("今天 23:59", task("a", dueAt = LocalDateTime.of(2026, 12, 31, 23, 59)).dueLabel(beforeNewYear))
        assertEquals("明天 00:00", task("b", dueAt = LocalDateTime.of(2027, 1, 1, 0, 0)).dueLabel(beforeNewYear))
        assertEquals("已逾期 · 昨天 23:00", task("c", dueAt = LocalDateTime.of(2026, 12, 30, 23, 0)).dueLabel(beforeNewYear))
    }

    @Test
    fun `far future due label omits the year`() {
        // 已知限制：非"今天/明天/昨天"的日期只显示 月/日，不显示年份。
        // 对跨学年的远期 DDL 会有歧义（看起来像近期日期），记为样例而不是"正确行为"。
        assertEquals("11/10 08:00", task("far", dueAt = LocalDateTime.of(2027, 11, 10, 8, 0)).dueLabel(now))
    }

    @Test
    fun `undated task stays undated even when the due text is garbage`() {
        // 勾了"截止待定"时输入框内容一律忽略，不能让残留文本把任务变成有截止
        val built = buildTaskDraft("写作业", TaskType.HOMEWORK, null, true, "乱填的").getOrThrow()
        assertNull(built.dueAt)
    }

    @Test
    fun `due text with seconds is rejected with the friendly message`() {
        // 复制粘贴带秒的文本会被拒；文案指出应填的格式，而不是抛内部异常
        assertEquals(
            "请填写有效的截止时间，例如 2026-11-09 23:59",
            draft("写作业", undated = false, dueText = "2026-11-09 23:59:00").exceptionOrNull()?.message,
        )
    }

    @Test
    fun `invalid calendar date is rejected instead of rolled over`() {
        // STRICT 解析：11 月没有 31 日，不能"顺延"成 12-01
        assertEquals(
            "请填写有效的截止时间，例如 2026-11-09 23:59",
            draft("写作业", undated = false, dueText = "2026-11-31 10:00").exceptionOrNull()?.message,
        )
    }

    // ---- 筛选组合 ----

    @Test
    fun `status type and date filters stack as and`() {
        val views = listOf(
            task("openToday", type = TaskType.HOMEWORK, dueAt = now.plusHours(8)),
            task("openYesterday", type = TaskType.HOMEWORK, dueAt = now.minusDays(1)),
            task("doneToday", type = TaskType.LAB_REPORT, completedAt = now.minusHours(2)),
            task("doneYesterday", type = TaskType.HOMEWORK, completedAt = now.minusDays(1)),
            task("openUndated", type = TaskType.CLUB),
        ).map { it.toView(emptyList(), now) }

        // 日期条件只看"今天"这个本地日界，与时刻无关
        assertEquals(listOf("openToday"), views.visible(TaskStatusFilter.ALL, null, TaskDateFilter.DUE_TODAY, now).map { it.record.id })
        assertEquals(listOf("doneToday"), views.visible(TaskStatusFilter.ALL, null, TaskDateFilter.COMPLETED_TODAY, now).map { it.record.id })
        // 三条件叠加：任一不满足即不显示
        assertEquals(listOf("openToday"), views.visible(TaskStatusFilter.OPEN, TaskType.HOMEWORK, TaskDateFilter.DUE_TODAY, now).map { it.record.id })
        assertTrue(views.visible(TaskStatusFilter.DONE, TaskType.HOMEWORK, TaskDateFilter.DUE_TODAY, now).isEmpty())
        assertTrue(views.visible(TaskStatusFilter.OPEN, TaskType.LAB_REPORT, TaskDateFilter.ALL, now).isEmpty())
    }

    @Test
    fun `date filter uses the local day boundary not a rolling 24 hours`() {
        val views = listOf(
            task("midnight", dueAt = now.toLocalDate().atStartOfDay()),
            task("lastSecondOfYesterday", dueAt = now.toLocalDate().atStartOfDay().minusSeconds(1)),
        ).map { it.toView(emptyList(), now) }

        // 今天 00:00 属于"今日截止"；昨天 23:59:59 不属于——即使两者相差只有 1 秒
        assertEquals(listOf("midnight"), views.visible(TaskStatusFilter.ALL, null, TaskDateFilter.DUE_TODAY, now).map { it.record.id })
    }

    // ---- 排序稳定性 ----

    @Test
    fun `equal due times keep insertion order`() {
        val same = now.plusDays(1)
        val first = task("first", dueAt = same)
        val second = task("second", dueAt = same)
        assertEquals(listOf("first", "second"), listOf(first, second).sortedForDisplay().map { it.id })
        // 输入顺序反过来，输出也跟着反过来：不引入与输入无关的隐形次序
        assertEquals(listOf("second", "first"), listOf(second, first).sortedForDisplay().map { it.id })
    }

    @Test
    fun `all completed still sort after every open task`() {
        val alreadyDone = task("done", completedAt = now.minusDays(30))
        val openLongOverdue = task("open", dueAt = now.minusDays(30))
        assertEquals(listOf("open", "done"), listOf(alreadyDone, openLongOverdue).sortedForDisplay().map { it.id })
    }

    @Test
    fun `overdue and pending share the due time ordering`() {
        val overdue = task("overdue", dueAt = now.minusDays(1))
        val tomorrow = task("tomorrow", dueAt = now.plusDays(1))
        assertEquals(TaskStatus.OVERDUE, overdue.statusAt(now))
        assertEquals(TaskStatus.PENDING, tomorrow.statusAt(now))
        // 逾期不禁用、不置底：它按 dueAt 自然排在待办前面
        assertEquals(listOf("overdue", "tomorrow"), listOf(tomorrow, overdue).sortedForDisplay().map { it.id })
    }

    @Test
    fun `empty view is never reported as fully selected`() {
        // 「全选」按钮的标签依据；空视图必须为 false，否则会显示"取消全选"却没有可取消的对象
        assertFalse(TaskUiState().allVisibleSelected)
        assertFalse(TaskUiState(tasks = emptyList(), selectedIds = setOf("ghost")).allVisibleSelected)
    }

    @Test
    fun `all visible selected needs every row not just one`() {
        // 部分选中时按钮必须仍显示「全选」。若实现被写成 any / selectedIds.isNotEmpty()，
        // 只选一行就会显示「取消全选」，这里会红。
        val views = listOf(task("a"), task("b"), task("c")).map { it.toView(emptyList(), now) }
        assertFalse(TaskUiState(tasks = views, selectedIds = setOf("a")).allVisibleSelected)
        assertFalse(TaskUiState(tasks = views, selectedIds = setOf("a", "b")).allVisibleSelected)
        assertTrue(TaskUiState(tasks = views, selectedIds = setOf("a", "b", "c")).allVisibleSelected)
    }
}
