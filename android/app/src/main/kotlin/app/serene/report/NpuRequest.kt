package app.serene.report

import android.os.Build
import app.serene.BuildConfig
import java.net.URLEncoder

/**
 * "Request a model for this phone's NPU" as a GitHub issue: the phone's chip and model in the
 * fields of .github/ISSUE_TEMPLATE/npu-model.yml, passed as query parameters, like [IssueReport].
 */
object NpuRequest {
    private const val TEMPLATE = "npu-model.yml"

    fun url(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        val fields = linkedMapOf(
            "title" to "NPU model for ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.MODEL}",
            "soc" to soc,
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
            "app" to "${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} (${BuildConfig.GIT_SHA})",
        )
        return buildString {
            append("https://github.com/${IssueReport.REPO}/issues/new?template=$TEMPLATE")
            for ((k, v) in fields) if (v.isNotBlank()) append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8").replace("+", "%20"))
        }
    }
}
