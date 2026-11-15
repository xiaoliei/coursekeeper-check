package com.coursekeeper.school

import com.coursekeeper.data.sync.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话规则测试（计划书 T06：会话检测；不依赖网络）。 */
class SchoolSessionRuleTest {

    private val portalBody = "<html><body><h2>教务系统首页</h2>欢迎回来</body></html>"
    private val loginBody = "<html><body><form><input type=\"password\" name=\"pwd\"/>验证码</form></body></html>"

    @Test
    fun validPortalResponseClassifiesAsValid() {
        assertEquals(SessionState.VALID, SchoolRule.classify(200, portalBody))
        assertEquals(SessionState.VALID, SchoolRule.classify(204, ""))
    }

    @Test
    fun loginFormOrAuthFailuresClassifyAsExpired() {
        assertEquals(SessionState.EXPIRED, SchoolRule.classify(200, loginBody))
        assertEquals(SessionState.EXPIRED, SchoolRule.classify(401, ""))
        assertEquals(SessionState.EXPIRED, SchoolRule.classify(403, loginBody))
    }

    @Test
    fun jsRedirectShellPageClassifiesAsExpiredNotOffline() {
        // 实测（2026-11-15，汕头大学）：会话过期后门户返回 127 字节的 JS 跳转壳页，
        // 主机名不变、没有表单；此前被误判成“离线”，登录过期提示永远出不来。
        val shell = "<script languge='javascript'>window.location.href=" +
            "'https://sso.stu.edu.cn/login?service=http%3A%2F%2Fjw.stu.edu.cn%2F'</script>"
        assertEquals(SessionState.EXPIRED, SchoolRule.classify(200, shell))
        assertTrue(SchoolRule.looksLikeLoginPage(shell))
        // 正常门户页不能被误伤。
        assertFalse(SchoolRule.looksLikeLoginPage(portalBody))
    }

    @Test
    fun serverErrorsClassifyAsOfflineNotExpired() {
        assertEquals(SessionState.OFFLINE, SchoolRule.classify(500, ""))
        assertEquals(SessionState.OFFLINE, SchoolRule.classify(503, portalBody))
    }

    @Test
    fun loginCompletionRequiresHttpsAndPortalHostWithoutLoginPageMarker() {
        val login = "https://jwxt.example.edu.cn/login"
        val portal = "https://jwxt.example.edu.cn/index"
        assertTrue(SchoolRule.isLoginComplete(login, portal, "https://jwxt.example.edu.cn/index?welcome=1"))
        assertTrue(SchoolRule.isLoginComplete(login, portal, "https://jwxt.example.edu.cn/student/schedule"))
        assertFalse(SchoolRule.isLoginComplete(login, portal, "http://jwxt.example.edu.cn/index"))
        assertFalse(SchoolRule.isLoginComplete(login, portal, "https://jwxt.example.edu.cn/login?err=1"))
        assertFalse(SchoolRule.isLoginComplete(login, portal, "https://other.example.edu.cn/index"))
    }

    @Test
    fun insecurePortalCallbackIsAcceptedOnlyForAllowlistedHosts() {
        // 汕头大学 SSO 的 service 参数为 http://jw.stu.edu.cn/：登录成功会明文回跳门户。
        val login = "https://sso.stu.edu.cn/login?service=http%3A%2F%2Fjw.stu.edu.cn%2F"
        val portal = "https://jw.stu.edu.cn/jsxsd/framework/xsMainV.htmlx"
        assertTrue(
            SchoolRule.isLoginComplete(login, portal, "http://jw.stu.edu.cn/", setOf("jw.stu.edu.cn")),
        )
        assertTrue(
            SchoolRule.isLoginComplete(login, portal, "http://jw.stu.edu.cn/jsxsd/framework/xsMainV.htmlx", setOf("jw.stu.edu.cn")),
        )
        // 未配置白名单时仍按原规则拒绝明文。
        assertFalse(SchoolRule.isLoginComplete(login, portal, "http://jw.stu.edu.cn/"))
        // 白名单只对学校域名生效，别的域名依旧拒绝。
        assertFalse(SchoolRule.isLoginComplete(login, portal, "http://example.com/", setOf("jw.stu.edu.cn")))
        // HTTPS 门户随时可以判定成功。
        assertTrue(SchoolRule.isLoginComplete(login, portal, "https://jw.stu.edu.cn/jsxsd/framework/xsMainV.htmlx"))
    }

    @Test
    fun taskSourceReadyOnlyOnNonLoginPageOfTaskHost() {
        val task = "https://my.stu.edu.cn/courses/campus/my/"
        assertTrue(SchoolRule.isTaskSourceReady(task, "https://my.stu.edu.cn/courses/campus/my/"))
        assertFalse(SchoolRule.isTaskSourceReady(task, "https://my.stu.edu.cn/login/index.php"))
        assertFalse(SchoolRule.isTaskSourceReady(task, "https://sso.stu.edu.cn/login?service=x"))
        assertFalse(SchoolRule.isTaskSourceReady(task, "http://my.stu.edu.cn/courses/campus/my/"))
    }
}
