package com.coursekeeper.school

/**
 * 学校接入信息（成员 C，计划书 T05）。
 *
 * 计划书 §一：基础版按"首批 1 所学校"实施。T05 实测确定目标学校后，只替换此处的
 * 官方地址与页面路径，适配器与解析层不动。数据地址一律 HTTPS；登录回跳若被 SSO
 * 指定为明文（`service` 参数），只对 [allowHttpHosts] 里的学校域名放行。
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
    /** 作业数据页；为 null 表示该学校作业源尚未接入，作业模块按失败上报而不是当成空结果。 */
    val taskListUrl: String? = null,
    /**
     * 学期标签（如 `2026AUT`）。用于给课程稳定标识加前缀：`courses` 的
     * `(source, externalId)` 是全局唯一键，不带学期会让重修撞键（B 的开放问题 6）。
     * 未确定学期时保持 null，表示沿用课程代码原样。
     */
    val termTag: String? = null,
    /**
     * 允许明文回跳的学校域名（只读白名单，不是放开全局 HTTP）。
     * 汕头大学 SSO 的 `service` 参数指向 `http://jw.stu.edu.cn/`，登录成功后 WebView
     * 会被带回明文地址；不放行就永远检测不到登录完成，也看不到门户页面。
     */
    val allowHttpHosts: Set<String> = emptySet(),
) {
    /** 数据接收域名（计划书：请求前展示目标域名）。 */
    val host: String get() = SchoolRule.hostOf(portalUrl)

    /** 登录页域名，用于在弹层里说明账号密码输入位置。 */
    val loginHost: String get() = SchoolRule.hostOf(loginUrl)
}

object SchoolCatalog {

    /**
     * 首批实测学校：汕头大学。
     *
     * - 登录走统一身份认证（SSO），`service` 为教务系统门户（明文），见 [SchoolInfo.allowHttpHosts]；
     * - 课表为正方教务 `/jsxsd` 的「学期理论课表」，课程编号在页面内「有课表课程」表；
     * - 作业在 MYSTU 学习平台（`my.stu.edu.cn`），与教务系统不同源、不同会话：
     *   登录流程在教务登录成功后再加载一次 MYSTU，复用同一 SSO 会话完成第二处登录。
     */
    private val entries = listOf(
        SchoolInfo(
            id = "stu",
            name = "汕头大学",
            loginUrl = "https://sso.stu.edu.cn/login?service=http%3A%2F%2Fjw.stu.edu.cn%2F",
            portalUrl = "https://jw.stu.edu.cn/jsxsd/framework/xsMainV.htmlx",
            scheduleUrl = "https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do",
            // MYSTU 学习平台（Moodle）的“课程概览”页，作业在 my.stu.edu.cn 域下。
            taskListUrl = "https://my.stu.edu.cn/courses/campus/my/",
            // 实测页面不含学年学期，先沿用课程代码；确定学期后填 "2026AUT" 这类标签。
            termTag = null,
            allowHttpHosts = setOf("jw.stu.edu.cn"),
        ),
    )

    val default: SchoolInfo get() = entries.first()

    /** 首期仅一所学校：按设置里的 schoolId 匹配；未登记过的 ID 视为未选校。 */
    fun byId(id: String?): SchoolInfo? = entries.firstOrNull { it.id == id }

    /** 课表解析器：首批学校为正方教务「学期理论课表」。 */
    val courseParser: SchoolPageParser get() = StuJwPageParser(default.termTag)

    /** 作业解析器：MYSTU（Moodle）课程概览。 */
    val taskParser: SchoolPageParser get() = MyStuTaskParser()
}
