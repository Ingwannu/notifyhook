package com.notifyhook.app.service

import android.app.Notification
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.notifyhook.app.data.GlobalVariableStore
import com.notifyhook.app.data.HookStorage
import com.notifyhook.app.data.TemplateEngine
import com.notifyhook.app.model.BodySourceType
import com.notifyhook.app.model.Hook
import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.util.TextReadLimits
import com.notifyhook.app.util.TextReadLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

class NotificationListener : NotificationListenerService() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val httpDispatcher = Dispatchers.IO.limitedParallelism(
        HookExecutionPolicy.MAX_CONCURRENT_DISPATCHES
    )
    private val scope = CoroutineScope(httpDispatcher + SupervisorJob())

    // 훅 목록 메모리 캐시 (매번 SharedPreferences 파싱 방지)
    @Volatile
    private var cachedHooks: List<Hook> = emptyList()
    @Volatile
    private var cachedHooksRevision: Long = Long.MIN_VALUE

    // 앱 이름 캐시
    private val appNameCache = ConcurrentHashMap<String, String>()

    // 알림 중복 방지 캐시
    private val dedup = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > DEDUP_MAX
            }
        }
    )

    companion object {
        private const val TAG = "NotifyHookListener"
        private const val DEDUP_WINDOW_MS = 3000L
        private const val DEDUP_MAX = 512
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        // 그룹 요약 알림은 건너뛴다. 한 앱의 알림이 여러 개 쌓이면 시스템이 내용 없는 요약(ranker_group)을 자동으로 올리고,
        // 앱이 직접 만든 요약도 개별 알림 내용을 반복한다. 실제 내용은 각 개별 알림이 따로 전달되므로,
        // 요약까지 보내면 빈 본문이나 중복 웹훅이 생긴다(에뮬레이터에서 빈 웹훅 전송을 확인함).
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // 중복 체크 (가벼운 연산만)
        val now = System.currentTimeMillis()
        val extras = sbn.notification.extras
        val dedupKey = NotificationDedupKey.build(
            notificationKey = sbn.key,
            postTime = sbn.postTime,
            title = extras.getCharSequence("android.title"),
            text = extras.getCharSequence("android.text")
        )
        synchronized(dedup) {
            val lastTime = dedup[dedupKey]
            if (lastTime != null && now - lastTime < DEDUP_WINDOW_MS) return
            dedup[dedupKey] = now
        }

        // 무거운 작업은 전부 백그라운드로
        scope.launch {
            try {
                processNotification(sbn)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process notification: ${e.message}", e)
            }
        }
    }

    private suspend fun processNotification(sbn: StatusBarNotification) {
        val storage = HookStorage.getInstance(this)
        val hooks = getEnabledHooks(storage)
        if (hooks.isEmpty()) return

        val matchingHooks = hooks.filter { hook ->
            hook.targetPackages.isEmpty() || sbn.packageName in hook.targetPackages
        }
        if (matchingHooks.isEmpty()) return

        val appName = resolveAppName(sbn.packageName)
        val wakeLock = acquireWakeLock(matchingHooks)
        try {
            supervisorScope {
                matchingHooks.map { hook ->
                    async {
                        executeHook(hook, sbn, appName, storage)
                    }
                }.awaitAll()
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun getEnabledHooks(storage: HookStorage): List<Hook> {
        val revision = storage.getHooksRevision()
        if (revision == cachedHooksRevision) {
            return cachedHooks
        }
        val hooks = storage.getHooks().filter { it.enabled }
        cachedHooks = hooks
        cachedHooksRevision = revision
        return hooks
    }

    private fun resolveAppName(packageName: String): String {
        return appNameCache.getOrPut(packageName) {
            try {
                val pm = this.packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }
        }
    }

    fun invalidateHookCache() {
        cachedHooksRevision = Long.MIN_VALUE
    }

    private fun acquireWakeLock(hooks: List<Hook>): PowerManager.WakeLock {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val timeoutMillis = HookExecutionPolicy.wakeLockTimeoutMillis(hooks)
        return pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NotifyHook::NotificationDispatch"
        ).apply {
            acquire(timeoutMillis)
        }
    }

    private fun executeHook(
        hook: Hook,
        sbn: StatusBarNotification,
        appName: String,
        storage: HookStorage
    ) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        val requestProblem = HookDeliveryRequestValidator.requestProblem(
            url = hook.url,
            method = hook.method.name,
            headers = hook.headers
        )
        if (requestProblem != null) {
            storage.addLog(
                HookDelivery(
                    hookId = hook.id,
                    hookName = hook.name,
                    notificationApp = appName,
                    notificationTitle = title,
                    notificationText = text,
                    requestUrl = hook.url,
                    requestMethod = hook.method.name,
                    contentType = hook.contentType.value,
                    requestHeaders = hook.headers,
                    timeoutSeconds = HookExecutionPolicy.normalizedTimeoutSeconds(hook)
                ).toLog(
                    success = false,
                    errorMessage = "$requestProblem; delivery skipped"
                )
            )
            Log.e(TAG, "Hook '${hook.name}' has invalid request settings; delivery skipped")
            return
        }

        val bodyTemplate = when (hook.bodySourceType) {
            BodySourceType.FILE -> {
                val path = hook.bodyFilePath
                if (path.isNullOrBlank()) {
                    hook.bodyTemplate
                } else {
                    try {
                        val uri = Uri.parse(path)
                        contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader().use { reader ->
                                TextReadLimiter.read(reader, TextReadLimits.BODY_FILE_MAX_CHARS)
                            }
                        } ?: hook.bodyTemplate
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read body file: ${e.message}")
                        hook.bodyTemplate
                    }
                }
            }
            BodySourceType.TEXT -> hook.bodyTemplate
        }

        val processedBody = TemplateEngine.process(
            template = bodyTemplate,
            sbn = sbn,
            appName = appName,
            regexRules = hook.regexRules,
            globalStore = GlobalVariableStore.getInstance(this)
        )

        if (!storage.hasHook(hook.id)) {
            Log.d(TAG, "Hook '${hook.name}' was deleted before dispatch; skipping")
            return
        }

        val now = System.currentTimeMillis()
        val delivery = HookDelivery(
            hookId = hook.id,
            hookName = hook.name,
            notificationApp = appName,
            notificationTitle = title,
            notificationText = text,
            requestUrl = hook.url,
            requestMethod = hook.method.name,
            contentType = hook.contentType.value,
            // 헤더는 요청 생성 시점 값으로 고정한다. 재시도 때 최신 훅 헤더를 쓰면, 사용자가 URL을 다른 서버로 바꾼 뒤
            // 이전 URL로 가는 재시도에 새 서버의 키가 실려 나갈 수 있기 때문이다.
            requestHeaders = hook.headers,
            requestBody = processedBody,
            timeoutSeconds = HookExecutionPolicy.normalizedTimeoutSeconds(hook),
            nextAttemptAt = DeliveryRetryPolicy.initialNextAttemptAtMillis(now, hook)
        )

        val persisted = storage.savePendingDeliveryIfHookExists(delivery)
        if (persisted) {
            DeliveryWorkScheduler.enqueue(this, delivery.nextAttemptAt - now)
        } else if (!storage.hasHook(hook.id)) {
            Log.d(TAG, "Hook '${hook.name}' was deleted before dispatch; skipping")
            return
        } else {
            Log.e(TAG, "Failed to persist pending hook '${hook.name}' before immediate dispatch")
        }

        val result = HookDeliverySender.send(delivery)
        if (result.success) {
            if (persisted) {
                storage.removePendingDelivery(delivery.id)
                DeliveryWorkScheduler.cancelIfNoPending(this)
            }
            storage.addLog(
                delivery.toLog(
                    success = true,
                    responseCode = result.responseCode,
                    responseBody = result.responseBody
                )
            )
            Log.d(TAG, "Hook '${hook.name}' executed: ${result.responseCode}")
            return
        }

        val failedAt = System.currentTimeMillis()
        val failedDelivery = delivery.failedAttempt(result.errorMessage, failedAt)
        val noRetryReason = DeliveryRetryPolicy.noRetryReason(result, delivery.createdAt, failedAt)
        if (noRetryReason != null) {
            if (persisted) {
                storage.removePendingDelivery(delivery.id)
                DeliveryWorkScheduler.cancelIfNoPending(this)
            }
            storage.addLog(
                failedDelivery.toLog(
                    success = false,
                    responseCode = result.responseCode,
                    responseBody = result.responseBody,
                    errorMessage = "${result.errorMessage} ($noRetryReason)"
                )
            )
            Log.e(TAG, "Hook '${hook.name}' failed without retry: ${result.errorMessage}")
            return
        }
        val retryPersisted = persisted && storage.savePendingDeliveryIfHookExists(failedDelivery)
        if (persisted && !retryPersisted && !storage.hasHook(hook.id)) {
            storage.removePendingDelivery(delivery.id)
            DeliveryWorkScheduler.cancelIfNoPending(this)
            storage.addLog(
                failedDelivery.toLog(
                    success = false,
                    responseCode = result.responseCode,
                    responseBody = result.responseBody,
                    errorMessage = "${result.errorMessage} (hook deleted; retry skipped)"
                )
            )
            Log.e(TAG, "Hook '${hook.name}' failed after deletion: ${result.errorMessage}")
            return
        }
        if (retryPersisted) {
            DeliveryWorkScheduler.enqueue(this, failedDelivery.nextAttemptAt - failedAt)
        } else {
            Log.e(TAG, "Failed to persist retry for hook '${hook.name}'")
        }
        storage.addLog(
            failedDelivery.toLog(
                success = false,
                responseCode = result.responseCode,
                responseBody = result.responseBody,
                errorMessage = if (retryPersisted) {
                    result.errorMessage
                } else {
                    "${result.errorMessage} (retry not persisted)"
                }
            )
        )
        Log.e(TAG, "Hook '${hook.name}' failed: ${result.errorMessage}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        DeliveryWorkScheduler.enqueueIfPending(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected")
        if (NotificationListenerAccess.isEnabled(this)) {
            requestRebind(android.content.ComponentName(this, NotificationListener::class.java))
        } else {
            ForegroundService.stopIfIdle(this)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
