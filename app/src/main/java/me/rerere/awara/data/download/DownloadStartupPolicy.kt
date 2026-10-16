package me.rerere.awara.data.download

import me.rerere.awara.data.entity.DownloadTaskStatus

const val PREFERENCE_KEY_DOWNLOAD_AUTO_RESUME = "setting.download_auto_resume"

fun isStartupResumeCandidate(status: String): Boolean =
    status == DownloadTaskStatus.QUEUED ||
        status == DownloadTaskStatus.RUNNING ||
        status == DownloadTaskStatus.PAUSED

fun shouldBlockAutomaticDownloadStart(
    status: String,
    autoResumeEnabled: Boolean,
    manuallyAuthorized: Boolean,
): Boolean =
    !autoResumeEnabled &&
        !manuallyAuthorized &&
        (status == DownloadTaskStatus.QUEUED || status == DownloadTaskStatus.RUNNING)
