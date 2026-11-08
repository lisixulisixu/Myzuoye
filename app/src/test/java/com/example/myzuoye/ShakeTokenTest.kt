package com.example.myzuoye

import com.example.myzuoye.p2p.ShakeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇一摇的「口令」规则。
 *
 * 这一层决定「算不算同时摇」和「谁去发起连接」，两边算错任何一处都会表现为
 * 「摇半天连不上」或者「两边同时发起互相踩」，所以边界都钉死在这里。
 */
class ShakeTokenTest {

    @Test
    fun instanceRoundTrip() {
        val instance = ShakeToken.instance(123456, "a3f91c")
        assertEquals("wf-123456-a3f91c", instance)
        val parsed = ShakeToken.parse(instance)
        assertEquals(123456L, parsed?.first)
        assertEquals("a3f91c", parsed?.second)
    }

    @Test
    fun parseRejectsForeignInstances() {
        // 别人的服务、空标识、非数字口令、没有标识，都要能被安全忽略
        assertNull(ShakeToken.parse("wfshake"))
        assertNull(ShakeToken.parse("_wfshake._tcp"))
        assertNull(ShakeToken.parse("wf-"))
        assertNull(ShakeToken.parse("wf-123"))
        assertNull(ShakeToken.parse("wf-abc-a1"))
        assertNull(ShakeToken.parse("wf-123-"))
        assertNull(ShakeToken.parse(""))
    }

    @Test
    fun tokenUsesFixedBuckets() {
        // 同一个 1.2 秒桶里的两次摇，必须得到同一个口令
        assertEquals(ShakeToken.token(0), ShakeToken.token(ShakeToken.BUCKET_MS - 1))
        assertNotEquals(ShakeToken.token(0), ShakeToken.token(ShakeToken.BUCKET_MS))
    }

    @Test
    fun matchesAllowsNeighbourBucketsOnly() {
        assertTrue(ShakeToken.matches(100, 100))
        assertTrue(ShakeToken.matches(100, 101))
        assertTrue(ShakeToken.matches(101, 100))
        // 差两个桶（约 2.4 秒以上）就不算同时摇了
        assertFalse(ShakeToken.matches(100, 102))
        assertFalse(ShakeToken.matches(100, 98))
    }

    @Test
    fun shouldInviteIsStrictlyOneSided() {
        val ids = listOf("a3f91c", "00ff01", "ffe000", "anon")
        for (self in ids) {
            for (peer in ids) {
                if (self == peer) continue
                val mine = ShakeToken.shouldInvite(self, peer)
                val theirs = ShakeToken.shouldInvite(peer, self)
                assertTrue("$self 与 $peer 必须有一方发起", mine != theirs)
            }
        }
        // 比较方向固定：大的那个发起
        assertTrue(ShakeToken.shouldInvite("ffe000", "00ff01"))
        assertFalse(ShakeToken.shouldInvite("00ff01", "ffe000"))
    }

    @Test
    fun deviceIdIsStableAndShortHex() {
        val id = ShakeToken.deviceId("9774d56d682e549c")
        assertEquals(id, ShakeToken.deviceId("9774d56d682e549c"))
        assertEquals(6, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(id, ShakeToken.deviceId("0123456789abcdef"))
        assertEquals("anon", ShakeToken.deviceId(null))
        assertEquals("anon", ShakeToken.deviceId("   "))
    }

    @Test
    fun instanceSanitizesIllegalCharacters() {
        // 实例名会被拼进 DNS 记录，空格、点、斜杠都得去掉，否则记录无效
        assertEquals("wf-1-abc", ShakeToken.instance(1, "a b.c"))
        assertEquals("wf-1-anon", ShakeToken.instance(1, "///"))
        assertTrue(ShakeToken.parse(ShakeToken.instance(7, "a b.c")) != null)
    }
}
