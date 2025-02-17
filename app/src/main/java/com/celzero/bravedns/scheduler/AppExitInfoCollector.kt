/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.celzero.bravedns.scheduler

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_3
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.convertLongToTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class AppExitInfoCollector(val context: Context, workerParameters: WorkerParameters) :
        Worker(context, workerParameters), KoinComponent {

    private val persistentState by inject<PersistentState>()

    @RequiresApi(Build.VERSION_CODES.R)
    override fun doWork(): Result {
        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "starting app-exit-info job")
        detectAppExitInfo()
        return Result.success()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun detectAppExitInfo() {

        if (!Utilities.isAtleastR()) return

        val am = context.getSystemService(AppCompatActivity.ACTIVITY_SERVICE) as ActivityManager

        val path = ZipUtil.getBugReport(this.applicationContext)
        // gets all the historical process exit reasons.
        val appExitInfo = am.getHistoricalProcessExitReasons(null, 0, 0)

        if (appExitInfo.isEmpty()) return

        val timestamp = appExitInfo[0].timestamp

        val file = File(path)
        run returnTag@{
            appExitInfo.forEach {

                // Write only the latest exit reason
                if (persistentState.lastAppExitInfoTimestamp >= it.timestamp) return@returnTag

                val reportDetails = "${it.packageUid},${it.reason},${it.description},${it.importance},${it.pss},${it.rss},${
                    convertLongToTime(it.timestamp, TIME_FORMAT_3)
                }\n"
                file.appendText(reportDetails)
                // capture traces for ANR exit-infos
                if (it.reason == ApplicationExitInfo.REASON_ANR) {
                    ZipUtil.writeTrace(file, it.traceInputStream)
                }
            }
        }

        if (timestamp <= persistentState.lastAppExitInfoTimestamp) return

        // Store the last exit reason time stamp
        persistentState.lastAppExitInfoTimestamp = timestamp

        if (file.exists() && file.length() > 0) ZipUtil.zip(applicationContext, file)
    }
}
