package me.rerere.awara.ui.component.iwara

import android.os.Build
import me.rerere.awara.R
import me.rerere.awara.data.download.ScanFolderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载目录弹窗扫描分派策略、通知权限门控与结果提示映射单元测试。 */
class DownloadDirectoryDialogLogicTest {
    @Test
    fun resolveDirectoryScanDispatchPlanSelectsExpectedBranch() {
        val customUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        assertEquals(
            DirectoryScanDispatchPlan.ScanSavedTree(customUri),
            resolveDirectoryScanDispatchPlan(
                savedDirectoryUri = customUri,
                canScanDefaultDirectly = false,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
            ),
        )

        assertEquals(
            DirectoryScanDispatchPlan.ScanDefaultDirect,
            resolveDirectoryScanDispatchPlan(
                savedDirectoryUri = "",
                canScanDefaultDirectly = true,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
            ),
        )

        assertEquals(
            DirectoryScanDispatchPlan.RequestLegacyStoragePermission,
            resolveDirectoryScanDispatchPlan(
                savedDirectoryUri = "   ",
                canScanDefaultDirectly = false,
                sdkInt = Build.VERSION_CODES.P,
            ),
        )

        assertEquals(
            DirectoryScanDispatchPlan.RequestDefaultTreeAuthorization,
            resolveDirectoryScanDispatchPlan(
                savedDirectoryUri = "",
                canScanDefaultDirectly = false,
                sdkInt = Build.VERSION_CODES.Q,
            ),
        )
    }

    @Test
    fun shouldRequestNotificationPermissionRespectsApiLevelAndSessionFlag() {
        assertFalse(
            shouldRequestNotificationPermissionForScan(
                sdkInt = Build.VERSION_CODES.S_V2,
                hasRequestedInSession = false,
                isPermissionGranted = false,
            )
        )
        assertTrue(
            shouldRequestNotificationPermissionForScan(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                hasRequestedInSession = false,
                isPermissionGranted = false,
            )
        )
        assertFalse(
            shouldRequestNotificationPermissionForScan(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                hasRequestedInSession = true,
                isPermissionGranted = false,
            )
        )
        assertFalse(
            shouldRequestNotificationPermissionForScan(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                hasRequestedInSession = false,
                isPermissionGranted = true,
            )
        )
    }

    @Test
    fun resolveScanSummaryNoticePrioritizesMetadataThenRepairThenExistingThenEmpty() {
        val withMetadata =
            resolveScanSummaryNotice(
                ScanFolderSummary(
                    newlyImportedCount = 3,
                    repairedCount = 2,
                    metadataTargetCount = 4,
                    skippedExistingCount = 5,
                )
            )
        assertEquals(R.string.download_scan_started_message, withMetadata.messageResId)
        assertEquals(4, withMetadata.countArg)
        assertFalse(withMetadata.isSuccess)

        val repaired =
            resolveScanSummaryNotice(
                ScanFolderSummary(
                    newlyImportedCount = 0,
                    repairedCount = 2,
                    metadataTargetCount = 0,
                    skippedExistingCount = 5,
                )
            )
        assertEquals(R.string.download_scan_repaired_message, repaired.messageResId)
        assertEquals(2, repaired.countArg)
        assertTrue(repaired.isSuccess)

        val skippedOnly =
            resolveScanSummaryNotice(
                ScanFolderSummary(
                    newlyImportedCount = 0,
                    repairedCount = 0,
                    metadataTargetCount = 0,
                    skippedExistingCount = 7,
                )
            )
        assertEquals(R.string.download_scan_all_existing_message, skippedOnly.messageResId)
        assertEquals(7, skippedOnly.countArg)
        assertFalse(skippedOnly.isSuccess)

        val empty =
            resolveScanSummaryNotice(
                ScanFolderSummary(
                    newlyImportedCount = 0,
                    repairedCount = 0,
                    metadataTargetCount = 0,
                    skippedExistingCount = 0,
                )
            )
        assertEquals(R.string.download_scan_empty_message, empty.messageResId)
        assertNull(empty.countArg)
        assertFalse(empty.isSuccess)
    }
}
