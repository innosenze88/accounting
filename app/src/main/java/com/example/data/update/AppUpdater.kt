package com.example.data.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A newer version found on GitHub Releases. */
data class UpdateInfo(
    val versionName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val publishedAt: String?
)

/**
 * In-app update from GitHub Releases (no Play Store).
 *
 * Data is kept because Android installs the new APK ON TOP of the old one:
 * same package name + same signing key + higher versionCode -> database, files, readers and settings stay.
 * Before installing, the downloaded APK is checked for exactly these three things, so the app never asks
 * the user to install something that would fail or tempt them to uninstall (which WOULD delete the data).
 */
class AppUpdater(private val context: Context) {

    companion object {
        const val OWNER = "innosenze88"
        const val REPO = "accounting"
        private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val updateDir get() = File(context.cacheDir, "updates").apply { mkdirs() }
    private val apkFile get() = File(updateDir, "update.apk")

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Returns the newer release, or null when the app is up to date. */
    suspend fun checkLatest(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ResortAccountingApp/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) throw IllegalStateException("ยังไม่มีเวอร์ชันที่เผยแพร่บน GitHub (Releases ว่าง)")
                if (!resp.isSuccessful) throw IllegalStateException("GitHub ตอบกลับ ${resp.code}")
                val json = JSONObject(resp.body?.string() ?: throw IllegalStateException("ไม่มีข้อมูลจาก GitHub"))
                val tag = json.optString("tag_name")
                val assets = json.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                if (!AppVersion.isNewer(tag, currentVersion)) return@use null
                if (apk == null) throw IllegalStateException("เวอร์ชัน $tag ไม่มีไฟล์ .apk แนบไว้ใน Release")
                UpdateInfo(
                    versionName = tag.removePrefix("v"),
                    title = json.optString("name").ifBlank { tag },
                    notes = json.optString("body"),
                    apkUrl = apk.optString("browser_download_url"),
                    apkSize = apk.optLong("size"),
                    publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    /** Downloads the APK. [onProgress] gets 0..1 (or -1 when the size is unknown). */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(updateDir, "update.apk.part")
            val request = Request.Builder().url(info.apkUrl)
                .header("User-Agent", "ResortAccountingApp/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("ดาวน์โหลดไม่สำเร็จ (${resp.code})")
                val body = resp.body ?: throw IllegalStateException("ไฟล์ว่าง")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f)
                        }
                    }
                }
            }
            apkFile.delete()
            if (!tmp.renameTo(apkFile)) throw IllegalStateException("บันทึกไฟล์อัปเดตไม่ได้")
            verify(apkFile)
            apkFile
        }
    }

    /**
     * The three conditions for an update that keeps all data. Throws with a Thai explanation otherwise.
     */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw IllegalStateException("ไฟล์ที่ดาวน์โหลดไม่ใช่แอปที่ติดตั้งได้ (ไฟล์เสีย?) ลองใหม่อีกครั้ง")
        val installed = pm.getPackageInfo(context.packageName, flags)

        if (archive.packageName != context.packageName) {
            apk.delete()
            throw IllegalStateException("ไฟล์นี้เป็นแอปอื่น (${archive.packageName}) ไม่ใช่แอปนี้ — ไม่ติดตั้ง")
        }
        if (versionCodeOf(archive) <= versionCodeOf(installed)) {
            apk.delete()
            throw IllegalStateException(
                "versionCode ของไฟล์ใหม่ (${versionCodeOf(archive)}) ไม่มากกว่าตัวที่ติดตั้ง (${versionCodeOf(installed)}) — " +
                    "Android จะไม่ยอมติดตั้งทับ ต้องเพิ่ม versionCode ใน build.gradle.kts ก่อนสร้าง Release"
            )
        }
        val newSigs = signatureHashes(archive)
        // Some older Android versions cannot read the signature of a downloaded file; the installer still checks it.
        if (newSigs.isNotEmpty() && newSigs != signatureHashes(installed)) {
            apk.delete()
            throw IllegalStateException(
                "ไฟล์ใหม่ถูกเซ็นด้วยกุญแจ (keystore) คนละดอกกับแอปที่ติดตั้งอยู่ — ติดตั้งทับไม่ได้ " +
                    "และห้ามลบแอปเดิมเพื่อลงใหม่ เพราะข้อมูลจะหายทั้งหมด ให้สร้าง APK ใหม่ด้วย keystore เดิม"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signatureHashes(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            p.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            p.signatures
        } ?: emptyArray()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** Android 8+: the user must allow this app to install apps once. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the system page "Install unknown apps" for this app. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Shows Android's install screen for the downloaded APK (installs on top, data is kept). */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Opens the Releases page in the browser (fallback). */
    fun openReleasesPage() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Removes an old downloaded APK (after the update, or to free space). */
    fun cleanUp() {
        updateDir.listFiles()?.forEach { it.delete() }
    }
}
