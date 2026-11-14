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
}
