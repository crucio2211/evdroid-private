package org.evvdroid

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash capture for the settings screen.
 *
 * If SettingsActivity dies, the user cannot open the app to report what
 * happened and logcat needs a PC. So the uncaught handler writes the stack
 * here, and the next launch shows it in a plain (non-Compose) TextView the
 * screen reader can read. A native SIGSEGV never reaches this handler: if
 * the app still dies with no new report, the crash is native, which is
 * itself the diagnosis.
 */
object CrashLog {

  private const val TAG = "evvdroid"
  private const val FILE = "crash-last.log"
  private const val MAX_CHARS = 12000

  @Volatile
  private var installed = false

  fun install(context: Context) {
    if (installed) return
    installed = true
    val app = context.applicationContext
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { write(app, thread, error) }
      prev?.uncaughtException(thread, error)
    }
  }

  fun write(context: Context, thread: Thread, error: Throwable) {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val cause = buildString {
      append("evvdroid crash report\n")
      append(stamp).append('\n')
      append("device: ").append(Build.MANUFACTURER).append(' ')
        .append(Build.MODEL).append(" android ").append(Build.VERSION.RELEASE)
        .append(" sdk ").append(Build.VERSION.SDK_INT).append('\n')
      append("thread: ").append(thread.name).append('\n')
      append(stackOf(error))
    }
    runCatching {
      File(context.filesDir, FILE).writeText(cause.take(MAX_CHARS))
    }
    Log.e(TAG, "crash recorded", error)
  }

  private fun stackOf(error: Throwable): String = buildString {
    var cur: Throwable? = error
    var depth = 0
    while (cur != null && depth < 4) {
      append(if (depth == 0) "" else "Caused by: ")
        .append(cur.javaClass.name).append(": ").append(cur.message).append('\n')
      cur.stackTrace.take(25).forEach { append("    at ").append(it).append('\n') }
      cur = cur.cause
      depth++
    }
  }

  fun has(context: Context): Boolean =
    runCatching { File(context.filesDir, FILE).let { it.exists() && it.length() > 0 } }
      .getOrDefault(false)

  fun read(context: Context): String =
    runCatching { File(context.filesDir, FILE).readText() }.getOrDefault("")

  fun clear(context: Context) {
    runCatching { File(context.filesDir, FILE).delete() }
  }
}
