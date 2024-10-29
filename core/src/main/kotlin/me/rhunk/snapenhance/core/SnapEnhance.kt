package me.rhunk.snapenhance.core

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rhunk.snapenhance.bridge.ConfigStateListener
import me.rhunk.snapenhance.bridge.SyncCallback
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.ReceiversConfig
import me.rhunk.snapenhance.common.action.EnumAction
import me.rhunk.snapenhance.common.bridge.FileHandleScope
import me.rhunk.snapenhance.common.bridge.InternalFileHandleType
import me.rhunk.snapenhance.common.bridge.toWrapper
import me.rhunk.snapenhance.common.data.FriendStreaks
import me.rhunk.snapenhance.common.data.MessagingFriendInfo
import me.rhunk.snapenhance.common.data.MessagingGroupInfo
import me.rhunk.snapenhance.common.util.toSerialized
import me.rhunk.snapenhance.core.bridge.BridgeClient
import me.rhunk.snapenhance.core.data.SnapClassCache
import me.rhunk.snapenhance.core.event.events.impl.NativeUnaryCallEvent
import me.rhunk.snapenhance.core.event.events.impl.SnapWidgetBroadcastReceiveEvent
import me.rhunk.snapenhance.core.ui.InAppOverlay
import me.rhunk.snapenhance.core.util.LSPatchUpdater
import me.rhunk.snapenhance.core.util.hook.HookAdapter
import me.rhunk.snapenhance.core.util.hook.HookStage
import me.rhunk.snapenhance.core.util.hook.findRestrictedMethod
import me.rhunk.snapenhance.core.util.hook.hook
import kotlin.reflect.KClass
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis


class SnapEnhance {
    companion object {
        lateinit var classLoader: ClassLoader
            private set
        val classCache by lazy {
            SnapClassCache(classLoader)
        }
    }
    private lateinit var appContext: ModContext
    private var isBridgeInitialized = false

    private fun hookMainActivity(methodName: String, stage: HookStage = HookStage.AFTER, block: Activity.(param: HookAdapter) -> Unit) {
        Activity::class.java.hook(methodName, stage, { isBridgeInitialized }) { param ->
            val activity = param.thisObject() as Activity
            if (!activity.packageName.equals(Constants.SNAPCHAT_PACKAGE_NAME)) return@hook
            block(activity, param)
        }
    }

    fun init(context: Context) {
        appContext = ModContext(
            androidContext = context.also { classLoader = it.classLoader }
        )
        appContext.apply {
            bridgeClient = BridgeClient(this)
            initConfigListener()
            bridgeClient.addOnConnectedCallback {
                bridgeClient.registerMessagingBridge(messagingBridge)
                coroutineScope.launch {
                    runCatching {
                        syncRemote()
                    }.onFailure {
                        log.error("Failed to sync remote", it)
                    }
                }
            }
        }

        runBlocking {
            var throwable: Throwable? = null
            val canLoad = appContext.bridgeClient.connect { throwable = it }
            if (canLoad == null) {
                InAppOverlay.showCrashOverlay(
                    buildString {
                        append("Snapchat timed out while trying to connect to SnapEnhance\n\n")
                        append("Make sure you:\n")
                        append(" - Have installed the latest SnapEnhance version (https://github.com/rhunk/SnapEnhance)\n")
                        append(" - Disabled battery optimizations\n")
                        append(" - Excluded SnapEnhance and Snapchat in HideMyApplist")
                    },
                    throwable
                )
                appContext.logCritical("Cannot connect to the SnapEnhance app")
                return@runBlocking
            }
            if (!canLoad) exitProcess(1)
            runCatching {
                LSPatchUpdater.onBridgeConnected(appContext)
            }.onFailure {
                appContext.log.error("Failed to init LSPatchUpdater", it)
            }
            jetpackComposeResourceHook()
            runCatching {
                measureTimeMillis {
                    init(this)
                }.also {
                    appContext.log.verbose("init took ${it}ms")
                }

                hookMainActivity("onPostCreate") {
                    appContext.mainActivity = this
                    if (!appContext.mappings.isMappingsLoaded) return@hookMainActivity
                    appContext.isMainActivityPaused = false
                    onActivityCreate(this)
                    appContext.actionManager.onNewIntent(intent)
                }

                hookMainActivity("onPause") {
                    appContext.bridgeClient.closeOverlay()
                    appContext.isMainActivityPaused = true
                }

                hookMainActivity("onNewIntent") { param ->
                    appContext.actionManager.onNewIntent(param.argNullable(0))
                }

                hookMainActivity("onResume") {
                    if (appContext.isMainActivityPaused.also {
                        appContext.isMainActivityPaused = false
                    }) {
                        appContext.reloadConfig()
                        appContext.executeAsync {
                            syncRemote()
                        }
                    }
                }
            }.onSuccess {
                isBridgeInitialized = true
            }.onFailure {
                appContext.logCritical("Failed to initialize bridge", it)
                InAppOverlay.showCrashOverlay("SnapEnhance failed to initialize. Please check logs for more details.", it)
            }
        }
    }

    private fun init(scope: CoroutineScope) {
        with(appContext) {
            Thread::class.java.hook("dispatchUncaughtException", HookStage.BEFORE) { param ->
                runCatching {
                    val throwable = param.argNullable(0) ?: Throwable()
                    logCritical(null, throwable)
                }
            }

            reloadConfig()
            initWidgetListener()
            initNative()
            scope.launch(Dispatchers.IO) {
                translation.userLocale = getConfigLocale()
                translation.load()
            }

            mappings.init(androidContext)
            database.init()
            eventDispatcher.init()
            //if mappings aren't loaded, we can't initialize features
            if (!mappings.isMappingsLoaded) return
            features.init()
            scriptRuntime.init()
            scriptRuntime.eachModule { callFunction("module.onSnapApplicationLoad", androidContext) }
        }
    }

    private var safeMode = false

    private fun onActivityCreate(activity: Activity) {
        measureTimeMillis {
            with(appContext) {
                features.onActivityCreate(activity)
                inAppOverlay.onActivityCreate(activity)
                scriptRuntime.eachModule { callFunction("module.onSnapMainActivityCreate", activity) }
                actionManager.onActivityCreate()

                if (safeMode) {
                    appContext.inAppOverlay.showStatusToast(
                        Icons.Outlined.Cancel,
                        "Failed to load security features! Snapchat may not work properly.",
                        durationMs = 3000
                    )
                }
            }
        }.also { time ->
            appContext.log.verbose("onActivityCreate took $time")
        }
    }

    private fun initNative() {
        val nativeSigCacheFileHandle = appContext.fileHandlerManager.getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.NATIVE_SIG_CACHE.key).toWrapper()

        val oldSignatureCache = nativeSigCacheFileHandle.readBytes()
            .takeIf {
                it.isNotEmpty()
            }?.toString(Charsets.UTF_8)?.also {
                appContext.native.signatureCache = it
                appContext.log.verbose("old signature cache $it")
            }

        val lateInit = appContext.native.initOnce {
            nativeUnaryCallCallback = { request ->
                appContext.event.post(NativeUnaryCallEvent(request.uri, request.buffer)) {
                    request.buffer = buffer
                    request.canceled = canceled
                }
            }
            appContext.reloadNativeConfig()
        }.let { init ->
            {
                init()
                appContext.native.signatureCache.takeIf { it != oldSignatureCache }?.let {
                    appContext.log.verbose("new signature cache $it")
                    nativeSigCacheFileHandle.writeBytes(it.toByteArray(Charsets.UTF_8))
                }
            }
        }

        appContext.config.experimental.nativeHooks.customSharedLibrary.get().takeIf { it.isNotEmpty() }?.let {
            runCatching {
                appContext.native.loadSharedLibrary(
                    appContext.fileHandlerManager.getFileHandle(FileHandleScope.USER_IMPORT.key, it).toWrapper().readBytes()
                )
                appContext.log.verbose("loaded custom shared library")
            }.onFailure {
                appContext.log.error("Failed to load custom shared library", it)
            }
        }

        if (appContext.bridgeClient.getDebugProp("disable_sif", "false") != "true") {
            runCatching {
                appContext.native.loadSharedLibrary(
                    appContext.fileHandlerManager.getFileHandle(FileHandleScope.INTERNAL.key, InternalFileHandleType.SIF.key)
                        .toWrapper()
                        .readBytes()
                        .takeIf {
                            it.isNotEmpty()
                        } ?: throw IllegalStateException("buffer is empty")
                )
                appContext.log.verbose("loaded sif")
            }.onFailure {
                safeMode = true
                appContext.log.error("Failed to load sif", it)
            }
        } else {
            appContext.log.warn("sif is disabled")
        }

        Runtime::class.java.findRestrictedMethod {
            it.name == "loadLibrary0" && it.parameterTypes.contentEquals(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Class::class.java, String::class.java)
                else arrayOf(ClassLoader::class.java, String::class.java)
            )
        }!!.apply {
            if (safeMode) {
                hook(HookStage.BEFORE) { param ->
                    if (param.arg<String>(1) != "scplugin") return@hook
                    param.setResult(null)
                    appContext.log.warn("Can't load scplugin in safe mode")
                    runCatching {
                        Thread.sleep(Long.MAX_VALUE)
                    }.onFailure {
                        appContext.log.error(it)
                    }
                    exitProcess(1)
                }
            }

            lateinit var unhook: () -> Unit
            hook(HookStage.AFTER) { param ->
                if (param.arg<String>(1) != "client") return@hook
                unhook()
                appContext.log.verbose("libclient lateInit")
                lateInit()
            }.also { unhook = { it.unhook() } }
        }
    }

    private fun initConfigListener() {
        val tasks = linkedSetOf<() -> Unit>()
        hookMainActivity("onResume") {
            tasks.forEach { it() }
        }

        fun runLater(task: () -> Unit) {
            if (appContext.isMainActivityPaused) {
                tasks.add(task)
            } else {
                task()
            }
        }

        appContext.bridgeClient.addOnConnectedCallback {
            appContext.bridgeClient.registerConfigStateListener(object: ConfigStateListener.Stub() {
                override fun onConfigChanged() {
                    appContext.log.verbose("onConfigChanged")
                    appContext.reloadConfig()
                }

                override fun onRestartRequired() {
                    appContext.log.verbose("onRestartRequired")
                    runLater {
                        appContext.log.verbose("softRestart")
                        appContext.softRestartApp(saveSettings = false)
                    }
                }

                override fun onCleanCacheRequired() {
                    appContext.log.verbose("onCleanCacheRequired")
                    tasks.clear()
                    runLater {
                        appContext.log.verbose("cleanCache")
                        appContext.actionManager.execute(EnumAction.CLEAN_CACHE)
                    }
                }
            })
        }
    }

    private fun initWidgetListener() {
        appContext.event.subscribe(SnapWidgetBroadcastReceiveEvent::class) { event ->
            if (event.action != ReceiversConfig.BRIDGE_SYNC_ACTION) return@subscribe
            event.canceled = true
            val feedEntries = appContext.database.getFeedEntries(Int.MAX_VALUE)

            val groups = feedEntries.filter { it.friendUserId == null }.map {
                MessagingGroupInfo(
                    it.key!!,
                    it.feedDisplayName!!,
                    it.participantsSize
                )
            }

            val friends = feedEntries.filter { it.friendUserId != null }.map {
                MessagingFriendInfo(
                    it.friendUserId!!,
                    appContext.database.getConversationLinkFromUserId(it.friendUserId!!)?.clientConversationId,
                    it.friendDisplayName,
                    it.friendDisplayUsername!!.split("|")[1],
                    it.bitmojiAvatarId,
                    it.bitmojiSelfieId,
                    streaks = null
                )
            }

            appContext.bridgeClient.passGroupsAndFriends(groups, friends)
        }
    }

    private fun syncRemote() {
        appContext.bridgeClient.sync(object : SyncCallback.Stub() {
            override fun syncFriend(uuid: String): String? {
                return appContext.database.getFriendInfo(uuid)?.let {
                    MessagingFriendInfo(
                        userId = it.userId!!,
                        dmConversationId = appContext.database.getConversationLinkFromUserId(it.userId!!)?.clientConversationId,
                        displayName = it.displayName,
                        mutableUsername = it.mutableUsername!!,
                        bitmojiId = it.bitmojiAvatarId,
                        selfieId = it.bitmojiSelfieId,
                        streaks = if (it.streakLength > 0) {
                            FriendStreaks(
                                expirationTimestamp = it.streakExpirationTimestamp,
                                length = it.streakLength
                            )
                        } else null
                    ).toSerialized()
                }
            }

            override fun syncGroup(uuid: String): String? {
                return appContext.database.getFeedEntryByConversationId(uuid)?.let {
                    MessagingGroupInfo(
                        it.key!!,
                        it.feedDisplayName!!,
                        it.participantsSize
                    ).toSerialized()
                }
            }
        })
    }

    private fun jetpackComposeResourceHook() {
        fun strings(vararg classes: KClass<*>): Map<Int, String> {
            return classes.fold(mapOf()) { map, clazz ->
                map + clazz.java.fields.filter {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
                }.associate { it.getInt(null) to it.name }
            }
        }
        val stringResources = strings(androidx.compose.material3.R.string::class, androidx.compose.ui.R.string::class)
        Resources::class.java.getMethod("getString", Int::class.javaPrimitiveType).hook(HookStage.BEFORE) { param ->
            val key = param.arg<Int>(0)
            val name = stringResources[key]?.replaceFirst("m3c_", "") ?: return@hook
            param.setResult(appContext.translation.getOrNull("material3_strings.${name}") ?: "")
        }
    }
}