package com.coursekeeper.data.sync

/**
 * CBE-01 导入作业自动关联课程（成员 C 提议的映射规则，2026-11-25）。
 *
 * 背景：MYSTU（Moodle）的课程是另一套 ID 体系（`course/view.php?id=`），与教务课程编号
 * （如 `A01MATH`）不同源。C 侧已在 [ImportedTask.sourceCourseId] / [ImportedTask.sourceCourseName]
 * 透传来源信息，但**不能**把数字 ID 当教务编号，也不做模糊匹配。
 *
 * 本对象只回答一个问题：给定学校侧课程名（如 `[2026秋] 应用密码学 方若宇`）与本地已同步的
 * 教务课程（[Candidate]），能否唯一确定一条关联？规则刻意保守：
 *
 * 1. 去掉开头的学期标签 `[2026秋]` / `【2026秋】` / `（2026秋）`；
 * 2. 生成两个候选名：完整名、以及去掉末尾空格分隔的教师名（`应用密码学 方若宇` → `应用密码学`）；
 *    括号里的教师名**不**剥离（保持保守，匹配不上就保持未关联，由用户手动选择）；
 * 3. 候选名与本地课程名各自规范化（去空白、去常见标点、统一小写）后**完全相等**才算命中，
 *    不做子串、编辑距离或其他模糊匹配；
 * 4. 命中 0 条或命中多条不同课程（同名不同课、候选名指向不同课程）一律返回 null —— 歧义不猜。
 *
 * 因此：能映射时是"同名同课"的确定性结果；映射失败只是保持"未关联课程"，不会错绑。
 * 用户手动选择的关联由任务级 `manualDirty` 保护，本对象不改动已有关联。
 */
object CourseLinker {

    /** 本地课程的最小投影：只用到教务课程编号与课程名。 */
    data class Candidate(val externalId: String, val title: String)

    /** 学校侧课程名开头的学期标签：`[2026秋]`、`［2026秋］`、`【2026秋】`、`（2026秋）`、`(2026秋)`。 */
    private val SEMESTER_PREFIX = Regex("""^\s*[\[【（(［][^\]】）)］]{0,20}[\]】）)］]\s*""")

    /** 名称分段：空白（含全角空格）视为教师名分隔符。 */
    private val TOKEN_SEPARATOR = Regex("""[\s\u00A0\u3000]+""")

    /** 规范化时忽略的字符：空白与常见中英文标点，不改变字母、数字和汉字。 */
    private val IGNORED = Regex("""[\s\u00A0\u3000()（）\[\]【】<>《》「」,，.。:：;；'"·•\-—_/\\|]""")

    /**
     * 尝试把学校侧课程名映射到**唯一**一条本地课程。
     *
     * @return 命中的本地课程 [Candidate.externalId]；无法唯一确定时返回 null（不猜）。
     */
    fun resolve(sourceCourseName: String?, localCourses: List<Candidate>): String? {
        if (sourceCourseName.isNullOrBlank() || localCourses.isEmpty()) return null
        val byTitle = localCourses
            .filter { it.externalId.isNotBlank() }
            .groupBy { normalize(it.title) }
        if (byTitle.isEmpty()) return null
        val hits = nameCandidates(sourceCourseName)
            .map(::normalize)
            .filter { it.isNotBlank() }
            .flatMap { key -> byTitle[key].orEmpty() }
            .map { it.externalId }
            .distinct()
        // 0 条 = 没有同名课程；>1 条 = 同名不同课或候选名互相矛盾，都保持未关联。
        return hits.singleOrNull()
    }

    /** 学校侧课程名的候选写法：去学期标签后的完整名，以及去掉末尾教师名（仅空白分隔时）。 */
    internal fun nameCandidates(rawSourceName: String): List<String> {
        val stripped = SEMESTER_PREFIX.replace(rawSourceName, "").trim()
        if (stripped.isBlank()) return emptyList()
        val candidates = linkedSetOf(stripped)
        val tokens = stripped.split(TOKEN_SEPARATOR).filter { it.isNotBlank() }
        // `应用密码学 方若宇` → `应用密码学`；单个词（无教师）时没有第二个候选。
        if (tokens.size >= 2) {
            candidates += tokens.dropLast(1).joinToString(" ")
        }
        return candidates.toList()
    }

    /** 本地课程名与学校课程名的归一化写法：只做确定性的字符级等价，不含任何语义猜测。 */
    internal fun normalize(text: String): String =
        text.lowercase().replace(IGNORED, "")
}
