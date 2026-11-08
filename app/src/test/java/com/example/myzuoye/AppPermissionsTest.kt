package com.example.myzuoye

import android.Manifest
import com.example.myzuoye.util.AppPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限推导的单测。
 *
 * 这段分支一旦写错，表现是「连上了却怎么也传不动」——排查成本极高，
 * 所以按版本逐个钉死。
 */
class AppPermissionsTest {

    private val nearby: String = Manifest.permission.NEARBY_WIFI_DEVICES
    private val localNetwork: String = AppPermissions.ACCESS_LOCAL_NETWORK
    private val fineLocation: String = Manifest.permission.ACCESS_FINE_LOCATION
    private val storage: String = Manifest.permission.WRITE_EXTERNAL_STORAGE

    @Test
    fun android16AndAboveAlsoNeedsLocalNetwork() {
        listOf(36, 37).forEach { api ->
            val required = AppPermissions.required(api)
            assertTrue("API $api 必须申请本地网络权限", required.contains(localNetwork))
            assertTrue("API $api 仍需附近设备权限", required.contains(nearby))
        }
    }

    @Test
    fun android13To15DoesNotRequestLocalNetwork() {
        listOf(33, 34, 35).forEach { api ->
            val required = AppPermissions.required(api)
            assertFalse("API $api 上该权限还不存在，申请会被判失败", required.contains(localNetwork))
            assertEquals(listOf(nearby), required)
        }
    }

    @Test
    fun android10To12UsesLocationOnly() {
        listOf(29, 30, 31, 32).forEach { api ->
            assertEquals(listOf(fineLocation), AppPermissions.required(api))
        }
    }

    @Test
    fun android9AndBelowAlsoNeedsStorage() {
        listOf(24, 26, 28).forEach { api ->
            assertEquals(listOf(fineLocation, storage), AppPermissions.required(api))
        }
    }

    /** 权限列表不能有重复项：重复申请在部分 ROM 上会直接失败 */
    @Test
    fun noDuplicatePermissionsAtAnyLevel() {
        (24..37).forEach { api ->
            val required = AppPermissions.required(api)
            assertEquals("API $api 出现重复权限", required.size, required.distinct().size)
        }
    }
}
