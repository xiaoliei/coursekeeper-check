package com.coursekeeper.school

/**
 * 学校接入信息（成员 C，计划书 T05）。
 *
 * 计划书 §一：基础版按"首批 1 所学校"实施。T05 的登录与解析可行性验证结论交付前，
 * 目录先登记占位条目；实测确定目标学校后，只替换此处的官方地址与页面路径，
 * 适配器与解析层不动。所有地址必须 HTTPS（计划书 §十一：仅接受 HTTPS）。
 */
data class SchoolInfo(
    val id: String,
    val name: String,
    /** 学校官方登录页：WebView 直接加载，账号密码与验证码在该页面内输入，本应用不经手。 */
    val loginUrl: String,
    /** 登录后入口页：轻量会话检测地址（checkSession）。 */
    val portalUrl: String,
    /** 课表数据页：fetchCourses 拉取并解析。 */
    val scheduleUrl: String,
    /** 作业公告页：fetchTasks 拉取并解析；作业源不可读取时按计划书回退手动录入。 */
    val taskListUrl: String,
    /**
     * 学期标签（如 `2026AUT`）。用于给课程稳定标识加前缀：`courses` 的
     * `(source, externalId)` 是全局唯一键，不带学期会让重修撞键（B 的开放问题 6）。
     * T05 实测前为 null，表示沿用课程代码原样。
     */
    val termTag: String? = null,
) {
    /** 展示给用户的接收域名（计划书：请求前展示目标域名）。 */
    val host: String get() = SchoolRule.hostOf(loginUrl)
}

object SchoolCatalog {

    /** 首批学校占位条目：T05 第 1 周结论（登录链路、可获取字段、脱敏样例）交付后替换为实测值。 */
    private val entries = listOf(
        SchoolInfo(
            id = "sample-university",
            // 界面会原样展示这个名称，必须让用户看出"还没接入真实学校"，不能伪装成可选学校。
            name = "示例大学（占位 · 未接入真实教务）",
            loginUrl = "https://jwxt.example.edu.cn/login",
            portalUrl = "https://jwxt.example.edu.cn/index",
            scheduleUrl = "https://jwxt.example.edu.cn/student/schedule",
            taskListUrl = "https://jwxt.example.edu.cn/student/homework",
            // T05 实测确定学期后填 "2026AUT" 这类标签；未填则课程 externalId 只有课程代码。
            termTag = null,
        ),
    )

    val default: SchoolInfo get() = entries.first()

    /** 首期仅一所学校：按设置里的 schoolId 匹配；未登记过的 ID 视为未选校。 */
    fun byId(id: String?): SchoolInfo? = entries.firstOrNull { it.id == id }
}
