package com.example.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object WasooliNotificationHelper {
    const val CHANNEL_ID = "wasooli_reminders"
    private const val CHANNEL_NAME = "Wasooli Payment Reminders"
    private const val CHANNEL_DESC = "Scheduled payment collection and due date push notifications"

    const val EXTRA_BOOK_ID = "extra_book_id"
    const val EXTRA_BOOK_NAME = "extra_book_name"
    const val EXTRA_NET_BALANCE = "extra_net_balance"
    const val EXTRA_DUE_MILLIS = "extra_due_millis"
    const val EXTRA_IS_TEST = "extra_is_test"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun scheduleWasooliReminder(
        context: Context,
        bookId: Int,
        bookName: String,
        netBalance: Double,
        dueMillis: Long
    ) {
        createNotificationChannel(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val now = System.currentTimeMillis()
        val dueCal = Calendar.getInstance().apply {
            timeInMillis = dueMillis
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If the chosen date is today and 9:00 AM already passed, schedule a near-immediate push alert (10s)
        // so the user receives timely confirmation on the same day.
        val triggerAtMillis = if (dueCal.timeInMillis <= now) {
            val endOfChosenDay = Calendar.getInstance().apply {
                timeInMillis = dueMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }.timeInMillis
            if (endOfChosenDay >= now) now + 10_000L else dueCal.timeInMillis
        } else {
            dueCal.timeInMillis
        }

        if (triggerAtMillis < now - 60_000L) {
            return
        }

        val intent = Intent(context, WasooliReminderReceiver::class.java).apply {
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_BOOK_NAME, bookName)
            putExtra(EXTRA_NET_BALANCE, netBalance)
            putExtra(EXTRA_DUE_MILLIS, dueMillis)
            putExtra(EXTRA_IS_TEST, false)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            bookId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.d("WasooliNotify", "Scheduled Wasooli push for bookId=$bookId at ${Date(triggerAtMillis)}")
        } catch (e: Exception) {
            Log.e("WasooliNotify", "Failed to schedule Wasooli reminder", e)
        }
    }

    fun cancelWasooliReminder(context: Context, bookId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, WasooliReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            bookId,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun showWasooliNotification(
        context: Context,
        bookId: Int,
        bookName: String,
        netBalance: Double,
        dueMillis: Long? = null,
        isTest: Boolean = false
    ) {
        createNotificationChannel(context)
        if (!hasNotificationPermission(context)) {
            Log.w("WasooliNotify", "POST_NOTIFICATIONS permission not granted; skipping notification")
            return
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BOOK_ID, bookId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            10_000 + bookId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formattedAmount = String.format(Locale.getDefault(), "%,.0f", abs(netBalance))
        val dueDateLabel = if (dueMillis != null && dueMillis > 0L) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(dueMillis))
        } else {
            "Today"
        }

        val title = if (isTest) {
            "Wasooli Reminder Ready: $bookName"
        } else {
            "Wasooli Due ($dueDateLabel): $bookName"
        }
        val balanceDirection = if (netBalance >= 0) "Net IN Rs. $formattedAmount" else "Balance Due Rs. $formattedAmount"
        val body = if (isTest) {
            "Scheduled for $dueDateLabel • $balanceDirection. Tap to open CashBook Pro."
        } else {
            "Payment collection reminder for $bookName • $balanceDirection."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(20_000 + bookId, notification)
        } catch (e: SecurityException) {
            Log.e("WasooliNotify", "SecurityException posting notification", e)
        }
    }
}

class WasooliReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bookId = intent.getIntExtra(WasooliNotificationHelper.EXTRA_BOOK_ID, 0)
        val bookName = intent.getStringExtra(WasooliNotificationHelper.EXTRA_BOOK_NAME) ?: "Account"
        val netBalance = intent.getDoubleExtra(WasooliNotificationHelper.EXTRA_NET_BALANCE, 0.0)
        val dueMillis = intent.getLongExtra(WasooliNotificationHelper.EXTRA_DUE_MILLIS, System.currentTimeMillis())
        val isTest = intent.getBooleanExtra(WasooliNotificationHelper.EXTRA_IS_TEST, false)

        WasooliNotificationHelper.showWasooliNotification(
            context = context,
            bookId = bookId,
            bookName = bookName,
            netBalance = netBalance,
            dueMillis = dueMillis,
            isTest = isTest
        )
    }
}
