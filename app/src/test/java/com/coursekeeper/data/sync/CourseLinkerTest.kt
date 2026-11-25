package com.coursekeeper.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * CBE-01（成员 C，2026-11-25）：导入作业自动关联课程的映射规则。
 *
 * 规则刻意保守：去掉学期标签与末尾教师名 → 规范化 → **完全相等**才命中；
 * 命中 0 条或多条不同课程一律不关联（不猜、不模糊匹配）。
 */
class CourseLinkerTest {

    private val cipher = CourseLinker.Candidate("A01CIP", "应用密码学")
    private val os = CourseLinker.Candidate("A02OS", "操作系统原理")
    private val algorithm = CourseLinker.Candidate("A03ALG", "高级算法")

    private fun resolve(name: String?, courses: List<CourseLinker.Candidate>) =
        CourseLinker.resolve(name, courses)

    @Test
    fun semesterTagAndTrailingTeacherAreStrippedBeforeMatching() {
        assertEquals("A01CIP", resolve("[2026秋] 应用密码学 方若宇", listOf(cipher, os)))
        assertEquals("A02OS", resolve("【2026秋】操作系统原理 吴俊豪", listOf(cipher, os)))
        assertEquals("A03ALG", resolve("（2026秋）高级算法 沈建国", listOf(cipher, os, algorithm)))
    }

    @Test
    fun fullNameWithoutTeacherStillMatches() {
        assertEquals("A01CIP", resolve("[2026秋] 应用密码学", listOf(cipher)))
    }

    @Test
    fun whitespaceWidthAndPunctuationAreNormalized() {
        assertEquals("A01CIP", resolve("［2026秋］应用密码学　方若宇", listOf(cipher)))
        assertEquals("A02OS", resolve("[2026秋] 操作系统原理（双语）", listOf(
            CourseLinker.Candidate("A02OS", "操作系统原理(双语)"),
        )))
    }

    @Test
    fun parenthesizedTeacherIsNotStrippedAndStaysUnlinked() {
        assertNull("括号内教师不剥离：匹配不上就保持未关联，不猜", resolve("[2026秋] 应用密码学（方若宇）", listOf(cipher)))
    }

    @Test
    fun fuzzyNamesAreNeverMatched() {
        assertNull("「高级算法导论」不等于「高级算法」", resolve("[2026秋] 高级算法导论 沈建国", listOf(algorithm)))
        assertNull("「操作系统」不等于「操作系统原理」", resolve("[2026秋] 操作系统 吴俊豪", listOf(os)))
    }

    @Test
    fun sameTitleInTwoCoursesIsAmbiguousAndStaysUnlinked() {
        val duplicated = listOf(
            CourseLinker.Candidate("A01", "应用密码学"),
            CourseLinker.Candidate("B01", "应用密码学"), // 同名不同课（不同学期/编号）
        )
        assertNull("同名不同课不猜，留待用户手动关联", resolve("[2026秋] 应用密码学 方若宇", duplicated))
    }

    @Test
    fun candidatesContradictingEachOtherAreAmbiguous() {
        val courses = listOf(
            CourseLinker.Candidate("A01", "高等数学"),
            CourseLinker.Candidate("A02", "高等数学 上册"),
        )
        // 完整名命中「高等数学 上册」，去教师名后命中「高等数学」：两条不同课程 → 歧义 → 不关联。
        assertNull(resolve("高等数学 上册", courses))
    }

    @Test
    fun unknownOrBlankInputStaysUnlinked() {
        assertNull(resolve(null, listOf(cipher)))
        assertNull(resolve("   ", listOf(cipher)))
        assertNull(resolve("[2026秋] 应用密码学 方若宇", emptyList()))
        assertNull(resolve("[2026秋]", listOf(cipher)))
    }

    @Test
    fun candidatesExposeOnlyDeterministicVariants() {
        assertEquals(
            listOf("应用密码学 方若宇", "应用密码学"),
            CourseLinker.nameCandidates("[2026秋] 应用密码学 方若宇"),
        )
        assertEquals(listOf("应用密码学"), CourseLinker.nameCandidates("[2026秋] 应用密码学"))
    }
}
